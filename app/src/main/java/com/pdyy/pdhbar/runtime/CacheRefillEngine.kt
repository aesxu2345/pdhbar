package com.pdyy.pdhbar.runtime

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.TreeMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val CACHE_REFILL_TAG = "CacheRefillEngine"
private const val CACHE_REFILL_FALLBACK_MS = 60L * 60L * 1_000L
private const val CACHE_REFILL_REQUEST_GAP_MS = 250L
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

internal fun cacheRefillProfile(context: Context): CacheRefillProfile {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return cacheRefillProfile(
        memoryMb = manager.memoryClass,
        lowRam = manager.isLowRamDevice,
        processors = Runtime.getRuntime().availableProcessors()
    )
}

internal fun cacheRefillProfile(
    memoryMb: Int,
    lowRam: Boolean,
    processors: Int
): CacheRefillProfile {
    val availableProcessors = processors.coerceAtLeast(1)
    val base = when {
        lowRam || memoryMb <= 128 -> CacheRefillProfile(64, 1)
        memoryMb <= 256 -> CacheRefillProfile(128, 1)
        memoryMb <= 512 -> CacheRefillProfile(256, 2)
        else -> CacheRefillProfile(512, 2)
    }
    return base.copy(workerCount = minOf(base.workerCount, availableProcessors, 2))
}

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
}

object CacheRefillEngineFactory {
    fun create(
        kind: CacheRefillEngineKind,
        context: Context,
        waitGroup: WaitGroupIO,
        query: suspend (String) -> WaitGroupExecutionResult,
        onObserved: (IgnoredPoolObservation) -> Unit
    ): CacheRefillEngine = when (kind) {
        CacheRefillEngineKind.IndexedBounded -> IndexedBoundedCacheRefillEngine(
            profile = cacheRefillProfile(context),
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
private class IndexedBoundedCacheRefillEngine(
    private val profile: CacheRefillProfile,
    private val waitGroup: WaitGroupIO,
    private val query: suspend (String) -> WaitGroupExecutionResult,
    private val onObserved: (IgnoredPoolObservation) -> Unit
) : CacheRefillEngine {
    private enum class KeyPhase { Queued, Running }
    private data class KeyState(
        val cacheIndex: Long,
        val nodeId: String,
        val orderCode: String,
        var phase: KeyPhase
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val tasks = Channel<Long>(profile.bufferSize)
    private val stateLock = ReentrantLock()
    private val states = TreeMap<Long, KeyState>()
    private var coordinator: Job? = null
    private val workers = ArrayList<Job>(profile.workerCount)

    override fun start() {
        if (coordinator?.isActive == true) return
        repeat(profile.workerCount) { workerId ->
            workers += scope.launch { workerLoop(workerId) }
        }
        coordinator = scope.launch { coordinatorLoop() }
        signal()
        Log.i(
            CACHE_REFILL_TAG,
            "indexed bounded engine started buffer=${profile.bufferSize} workers=${profile.workerCount}"
        )
    }

    override fun signal() {
        signals.trySend(Unit)
    }

    override fun stop() {
        coordinator?.cancel()
        workers.forEach(Job::cancel)
        workers.clear()
        tasks.close()
        signals.close()
    }

    private suspend fun coordinatorLoop() {
        while (scope.isActive) {
            refreshIndex()
            withTimeoutOrNull(CACHE_REFILL_FALLBACK_MS) { signals.receive() }
        }
    }

    private suspend fun refreshIndex() {
        val snapshots = try {
            waitGroup.ignoredOrderSnapshots()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(CACHE_REFILL_TAG, "indexed state-3 observation failed", error)
            return
        }
        val now = System.currentTimeMillis()
        val observation = IgnoredPoolObservation(
            totalCount = snapshots.size,
            recentCount = snapshots.count { isRecentIgnoredNode(it.createdAtEpochMs, now) },
            missingCount = snapshots.count { it.cacheNode == null }
        )
        onObserved(observation)

        val candidates = snapshots.asSequence()
            .filter { it.cacheNode == null && it.cacheIndex > 0L }
            .toList()
        val liveIndexes = snapshots.asSequence().map { it.cacheIndex }.filter { it > 0L }.toSet()
        val accepted = ArrayList<Long>()
        stateLock.withLock {
            states.keys.removeAll { it !in liveIndexes }
            candidates.forEach { node ->
                if (states.size >= profile.bufferSize || states.containsKey(node.cacheIndex)) {
                    return@forEach
                }
                states[node.cacheIndex] = KeyState(
                    cacheIndex = node.cacheIndex,
                    nodeId = node.nodeId,
                    orderCode = node.orderCode,
                    phase = KeyPhase.Queued
                )
                accepted += node.cacheIndex
            }
        }
        accepted.forEach { cacheIndex ->
            if (tasks.trySend(cacheIndex).isFailure) {
                stateLock.withLock { states.remove(cacheIndex) }
            }
        }
        Log.i(
            CACHE_REFILL_TAG,
            "indexed refresh total=${observation.totalCount} recent=${observation.recentCount} " +
                "missing=${observation.missingCount} scheduled=${accepted.size}"
        )
    }

    private suspend fun workerLoop(workerId: Int) {
        for (cacheIndex in tasks) {
            val work = stateLock.withLock {
                states[cacheIndex]?.takeIf { it.phase == KeyPhase.Queued }?.also {
                    it.phase = KeyPhase.Running
                }?.copy()
            } ?: continue
            try {
                val result = query(work.orderCode)
                val parsed = if (result.completed && result.success) {
                    result.responseText?.let(::parseBreakfastRightCacheNode)
                } else null
                if (parsed != null && parsed.orderCode == work.orderCode) {
                    val indexedCache = parsed.copy(cacheIndex = work.cacheIndex)
                    val updated = waitGroup.overwriteIgnoredCache(
                        nodeId = work.nodeId,
                        orderCode = work.orderCode,
                        cacheNode = indexedCache
                    )
                    Log.i(
                        CACHE_REFILL_TAG,
                        "worker=$workerId index=${work.cacheIndex} orderCode=${work.orderCode} updated=$updated"
                    )
                } else {
                    Log.w(
                        CACHE_REFILL_TAG,
                        "worker=$workerId rejected index=${work.cacheIndex} " +
                            "orderCode=${work.orderCode} message=${result.message}"
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(CACHE_REFILL_TAG, "worker=$workerId failed index=$cacheIndex", error)
            } finally {
                stateLock.withLock {
                    states[cacheIndex]?.takeIf {
                        it.nodeId == work.nodeId && it.orderCode == work.orderCode
                    }?.let { states.remove(cacheIndex) }
                }
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
        job?.cancel()
        signals.close()
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
