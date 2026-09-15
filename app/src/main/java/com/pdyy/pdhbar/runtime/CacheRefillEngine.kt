package com.pdyy.pdhbar.runtime

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TreeMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val CACHE_REFILL_TAG = "CacheRefillEngine"
private const val CACHE_REFILL_FALLBACK_MS = 60L * 60L * 1_000L
private const val CACHE_REFILL_REQUEST_GAP_MS = 250L
private const val CACHE_REFILL_STATUS_MS = 30_000L
private const val LEGACY_BATCH_SIZE = 12

data class CacheRefillProfile(
    val bufferSize: Int,
    val workerCount: Int
) {
    init {
        require(bufferSize > 0)
        require(workerCount > 0)
    }
}

internal fun cacheRefillProfile(
    context: Context,
    configuredMinWorkers: Int? = null
): CacheRefillProfile {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val processors = Runtime.getRuntime().availableProcessors()
    val profile = cacheRefillProfile(
        memoryMb = manager.memoryClass,
        lowRam = manager.isLowRamDevice,
        processors = processors,
        configuredMinWorkers = configuredMinWorkers
    )
    Log.i(
        CACHE_REFILL_TAG,
        "device profile memoryClassMb=${manager.memoryClass} lowRam=${manager.isLowRamDevice} " +
            "processors=$processors buffer=${profile.bufferSize} workers=${profile.workerCount}"
    )
    return profile
}

internal fun cacheRefillProfile(
    memoryMb: Int,
    lowRam: Boolean,
    processors: Int,
    configuredMinWorkers: Int? = null
): CacheRefillProfile {
    val availableProcessors = processors.coerceAtLeast(1)
    val base = when {
        lowRam || memoryMb <= 128 -> CacheRefillProfile(64, 1)
        memoryMb <= 256 -> CacheRefillProfile(128, 1)
        memoryMb <= 512 -> CacheRefillProfile(256, 2)
        else -> CacheRefillProfile(512, 2)
    }
    val memoryEvaluatedWorkers = minOf(base.workerCount, availableProcessors, 2)
    val workerCount = configuredMinWorkers
        ?.coerceIn(1, MAX_CONFIGURED_CACHE_WORKERS)
        ?.let { maxOf(memoryEvaluatedWorkers, it) }
        ?: memoryEvaluatedWorkers
    return base.copy(workerCount = workerCount)
}

internal const val DEFAULT_INDEXED_BOUNDED_MIN_WORKERS = 4
internal const val MAX_CONFIGURED_CACHE_WORKERS = 32

enum class CacheRefillEngineKind { IndexedBounded, LegacyPolling }

data class IgnoredPoolObservation(
    val totalCount: Int,
    val recentCount: Int,
    val missingCount: Int
)

interface CacheRefillEngine {
    fun start()
    fun signal()
    fun stop()
    /** Configuration replacement must wait for old HTTP work, not merely request cancellation. */
    suspend fun stopAndJoin()
}

internal data class CacheRefillWorkToken(val cacheIndex: Long, val generation: Long)

internal data class CacheRefillWorkIdentity(
    val cacheIndex: Long,
    val generation: Long,
    val leaseId: Long
)

internal fun cacheRefillIdentityMatches(
    expected: CacheRefillWorkIdentity,
    cacheIndex: Long,
    generation: Long,
    leaseId: Long
): Boolean = expected.cacheIndex == cacheIndex &&
    expected.generation == generation &&
    expected.leaseId == leaseId

object CacheRefillEngineFactory {
    fun create(
        kind: CacheRefillEngineKind,
        context: Context,
        configuredMinWorkers: Int?,
        waitGroup: WaitGroupIO,
        query: suspend (String) -> WaitGroupExecutionResult,
        onObserved: (IgnoredPoolObservation) -> Unit
    ): CacheRefillEngine = when (kind) {
        CacheRefillEngineKind.IndexedBounded -> IndexedBoundedCacheRefillEngine(
            profile = cacheRefillProfile(context, configuredMinWorkers),
            waitGroup = waitGroup,
            query = query,
            onObserved = onObserved
        )
        CacheRefillEngineKind.LegacyPolling -> LegacyPollingCacheRefillEngine(
            waitGroup = waitGroup,
            query = query,
            onObserved = onObserved
        )
    }
}

/**
 * PDA-oriented cache refill scheduler.
 *
 * The bounded channel contains only stable cache indexes. A TreeMap (JVM red-black tree) owns key
 * state. One lock protects the tree and transitions, and is never held across HTTP, persistence,
 * delay, logging, or callbacks. Workers revalidate each key before and after external work.
 */
internal class IndexedBoundedCacheRefillEngine(
    private val profile: CacheRefillProfile,
    private val waitGroup: WaitGroupIO,
    private val query: suspend (String) -> WaitGroupExecutionResult,
    private val onObserved: (IgnoredPoolObservation) -> Unit
) : CacheRefillEngine {
    private enum class KeyPhase { Queued, Running, Committing }
    private data class KeyState(
        val cacheIndex: Long,
        val nodeId: String,
        val orderCode: String,
        val generation: Long,
        var leaseId: Long,
        var phase: KeyPhase
    )
    private data class WorkLease(
        val identity: CacheRefillWorkIdentity,
        val nodeId: String,
        val orderCode: String
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val completions = Channel<Unit>(Channel.CONFLATED)
    private val tasks = Channel<CacheRefillWorkToken>(profile.bufferSize)
    private val stateLock = ReentrantLock()
    private val states = TreeMap<Long, KeyState>()
    private var nextGeneration = 1L
    private var nextLeaseId = 1L
    private var coordinator: Job? = null
    @Volatile private var coordinatorPhase = "not-started"
    @Volatile private var lastObservation: IgnoredPoolObservation? = null
    @Volatile private var lastObservationAt = 0L
    private val workers = ArrayList<Job>(profile.workerCount)

    override fun start() {
        if (!scope.isActive) return
        if (coordinator?.isActive == true) return
        repeat(profile.workerCount) { workerSlot ->
            val workerId = workerSlot + 1
            workers += scope.launch { workerLoop(workerId) }
        }
        coordinator = scope.launch { coordinatorLoop() }
        scope.launch {
            while (isActive) {
                delay(CACHE_REFILL_STATUS_MS)
                val counts = stateLock.withLock {
                    KeyPhase.entries.map { phase -> states.values.count { it.phase == phase } }
                }
                Log.i(CACHE_REFILL_TAG,
                    "indexed status workers=${profile.workerCount} coordinator=${coordinator?.isActive} " +
                        "phase=$coordinatorPhase queued=${counts[0]} running=${counts[1]} committing=${counts[2]} " +
                        "lastTotal=${lastObservation?.totalCount} lastMissing=${lastObservation?.missingCount} " +
                        "observedAt=$lastObservationAt")
            }
        }
        Log.i(
            CACHE_REFILL_TAG,
            "indexed bounded engine started buffer=${profile.bufferSize} " +
                "workerCount=${profile.workerCount} workerIds=1..${profile.workerCount}"
        )
    }

    override fun signal() {
        signals.trySend(Unit)
    }

    override fun stop() {
        scope.cancel()
        tasks.cancel()
        signals.cancel()
        completions.cancel()
        Log.i(CACHE_REFILL_TAG, "indexed engine stopping workers=${profile.workerCount}")
    }

    override suspend fun stopAndJoin() {
        stop()
        scope.coroutineContext[Job]?.join()
        stateLock.withLock { states.clear() }
        workers.clear()
        Log.i(CACHE_REFILL_TAG, "indexed engine stopped workers=${profile.workerCount}")
    }

    private suspend fun coordinatorLoop() {
        while (scope.isActive) {
            var afterIndex = 0L
            try {
                while (scope.isActive) {
                    coordinatorPhase = "observing"
                    val nextIndex = refreshIndex(afterIndex) ?: break
                    afterIndex = nextIndex
                    coordinatorPhase = "draining-batch"
                    // Completion only wakes the coordinator. Never scan the ring per worker result.
                    while (stateLock.withLock { states.isNotEmpty() }) completions.receive()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(CACHE_REFILL_TAG, "indexed pass failed; awaiting next signal", error)
            }
            coordinatorPhase = "waiting-signal-or-hourly"
            Log.i(CACHE_REFILL_TAG,
                "indexed pass finished workers=${profile.workerCount} lastIndex=$afterIndex; " +
                    "failed nodes retry on next external signal/hourly pass")
            withTimeoutOrNull(CACHE_REFILL_FALLBACK_MS) { signals.receive() }
        }
    }

    private suspend fun refreshIndex(afterIndex: Long): Long? {
        val snapshots = waitGroup.ignoredOrderSnapshots()
        val now = System.currentTimeMillis()
        val observation = IgnoredPoolObservation(
            totalCount = snapshots.size,
            recentCount = snapshots.count { isRecentIgnoredNode(it.createdAtEpochMs, now) },
            missingCount = snapshots.count { it.cacheNode == null }
        )
        lastObservation = observation
        lastObservationAt = now
        onObserved(observation)

        val candidates = selectIndexedRefillBatch(snapshots, afterIndex, profile.bufferSize)
        val accepted = ArrayList<CacheRefillWorkToken>()
        stateLock.withLock {
            candidates.forEach { node ->
                if (states.size >= profile.bufferSize || states.containsKey(node.cacheIndex)) {
                    return@forEach
                }
                val generation = nextGeneration++
                states[node.cacheIndex] = KeyState(
                    cacheIndex = node.cacheIndex,
                    nodeId = node.nodeId,
                    orderCode = node.orderCode,
                    generation = generation,
                    leaseId = 0L,
                    phase = KeyPhase.Queued
                )
                accepted += CacheRefillWorkToken(node.cacheIndex, generation)
            }
        }
        accepted.forEach { token ->
            if (tasks.trySend(token).isFailure) {
                stateLock.withLock {
                    states[token.cacheIndex]?.takeIf {
                        it.generation == token.generation && it.phase == KeyPhase.Queued
                    }?.let { states.remove(token.cacheIndex) }
                }
            }
        }
        Log.i(
            CACHE_REFILL_TAG,
            "indexed refresh total=${observation.totalCount} recent=${observation.recentCount} " +
                "missing=${observation.missingCount} scheduled=${accepted.size} " +
                "workerCount=${profile.workerCount}"
        )
        return candidates.lastOrNull()?.cacheIndex
    }

    private suspend fun workerLoop(workerId: Int) {
        for (token in tasks) {
            val work = stateLock.withLock {
                states[token.cacheIndex]?.takeIf {
                    it.generation == token.generation && it.phase == KeyPhase.Queued
                }?.also {
                    it.leaseId = nextLeaseId++
                    it.phase = KeyPhase.Running
                }?.let {
                    WorkLease(
                        identity = CacheRefillWorkIdentity(
                            it.cacheIndex,
                            it.generation,
                            it.leaseId
                        ),
                        nodeId = it.nodeId,
                        orderCode = it.orderCode
                    )
                }
            } ?: continue
            try {
                Log.d(CACHE_REFILL_TAG, "worker=$workerId index=${work.identity.cacheIndex} phase=requesting")
                val result = query(work.orderCode)
                // Blocking I/O or a callback can return after cancellation without suspending.
                currentCoroutineContext().ensureActive()
                val parsed = if (result.completed && result.success) {
                    result.responseText?.let(::parseBreakfastRightCacheNode)
                } else null
                if (parsed != null && parsed.orderCode == work.orderCode) {
                    val mayCommit = stateLock.withLock {
                        states[work.identity.cacheIndex]?.takeIf {
                            cacheRefillIdentityMatches(
                                work.identity,
                                it.cacheIndex,
                                it.generation,
                                it.leaseId
                            ) && it.phase == KeyPhase.Running
                        }?.also { it.phase = KeyPhase.Committing } != null
                    }
                    if (mayCommit) {
                        currentCoroutineContext().ensureActive()
                        val indexedCache = parsed.copy(cacheIndex = work.identity.cacheIndex)
                        val updated = waitGroup.overwriteIgnoredCache(
                            nodeId = work.nodeId,
                            orderCode = work.orderCode,
                            cacheNode = indexedCache
                        )
                        Log.i(
                            CACHE_REFILL_TAG,
                            "worker=$workerId index=${work.identity.cacheIndex} " +
                                "generation=${work.identity.generation} orderCode=${work.orderCode} " +
                                "updated=$updated"
                        )
                    } else {
                        Log.i(
                            CACHE_REFILL_TAG,
                            "worker=$workerId discarded stale refill index=" +
                                "${work.identity.cacheIndex} generation=${work.identity.generation}"
                        )
                    }
                } else {
                    Log.w(
                        CACHE_REFILL_TAG,
                        "worker=$workerId rejected index=${work.identity.cacheIndex} " +
                            "orderCode=${work.orderCode} message=${result.message}"
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(CACHE_REFILL_TAG, "worker=$workerId failed index=${token.cacheIndex}", error)
            } finally {
                stateLock.withLock {
                    states[work.identity.cacheIndex]?.takeIf {
                        cacheRefillIdentityMatches(
                            work.identity,
                            it.cacheIndex,
                            it.generation,
                            it.leaseId
                        ) && it.phase != KeyPhase.Queued
                    }?.let { states.remove(work.identity.cacheIndex) }
                }
                completions.trySend(Unit)
            }
            delay(CACHE_REFILL_REQUEST_GAP_MS)
        }
    }
}

/** Preserved maintenance factory: bounded batches but intentionally uses the original full scan. */
private class LegacyPollingCacheRefillEngine(
    private val waitGroup: WaitGroupIO,
    private val query: suspend (String) -> WaitGroupExecutionResult,
    private val onObserved: (IgnoredPoolObservation) -> Unit
) : CacheRefillEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    private var cursor = 0

    override fun start() {
        if (!scope.isActive) return
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                bruteForceScanOnce()
                withTimeoutOrNull(CACHE_REFILL_FALLBACK_MS) { signals.receive() }
            }
        }
        signal()
    }

    override fun signal() {
        signals.trySend(Unit)
    }

    override fun stop() {
        scope.cancel()
        signals.cancel()
    }

    override suspend fun stopAndJoin() {
        stop()
        scope.coroutineContext[Job]?.join()
    }

    private suspend fun bruteForceScanOnce() {
        val snapshots = waitGroup.ignoredOrderSnapshots()
        val now = System.currentTimeMillis()
        onObserved(
            IgnoredPoolObservation(
                snapshots.size,
                snapshots.count { isRecentIgnoredNode(it.createdAtEpochMs, now) },
                snapshots.count { it.cacheNode == null }
            )
        )
        val batch = selectRefillBatch(snapshots.filter { it.cacheNode == null }, cursor, LEGACY_BATCH_SIZE)
        cursor = batch.nextIndex
        batch.nodes.forEach { node ->
            val result = query(node.orderCode)
            currentCoroutineContext().ensureActive()
            val parsed = if (result.completed && result.success) {
                result.responseText?.let(::parseBreakfastRightCacheNode)
            } else null
            if (parsed != null && parsed.orderCode == node.orderCode) {
                waitGroup.overwriteIgnoredCache(
                    node.nodeId,
                    node.orderCode,
                    parsed.copy(cacheIndex = node.cacheIndex)
                )
            }
            delay(CACHE_REFILL_REQUEST_GAP_MS)
        }
    }
}
