package com.pdyy.pdhbar.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val WAIT_GROUP_PREFS = "breakfast_wait_group"
private const val WAIT_GROUP_SNAPSHOT = "ring_snapshot"
private const val WAIT_GROUP_CACHE_INDEX_PREFS = "breakfast_wait_group_cache_index"
private const val WAIT_GROUP_TAG = "BreakfastWaitGroup"
private const val WAIT_GROUP_RETRY_DELAY_MS = 3_000L
private const val WAIT_GROUP_MAX_AGE_MS = 90L * 24L * 60L * 60L * 1_000L
private const val WAIT_GROUP_UNREGISTERED_MAX_AGE_MS = 10L * 60L * 1_000L
private const val WAIT_GROUP_AGING_TICK_MS = 30_000L
private const val WAIT_GROUP_AGING_BATCH_SIZE = 10
internal const val IGNORED_CACHE_MAX_AGE_MS = 24L * 60L * 60L * 1_000L

/** Serializable descriptors; executable closures are rebuilt by the injected executor. */
enum class WaitGroupTask(val value: Int) {
    QueryBreakfastRight(2),
    Enroll(1),
    CancelEnrollment(0);

    companion object {
        fun from(value: Int): WaitGroupTask = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("unknown wait group task: $value")
    }
}

enum class ApprovalQueueAction {
    ApproveBreakfast,
    CancelEnrollment;

    internal fun toTask(): WaitGroupTask = when (this) {
        ApproveBreakfast -> WaitGroupTask.Enroll
        CancelEnrollment -> WaitGroupTask.CancelEnrollment
    }
}

enum class WaitApprovalState(val value: Int) {
    Blocked(0),
    Approved(1),
    Pending(2),
    Ignored(3),
    Aged(10);

    companion object {
        fun from(value: Int): WaitApprovalState = when {
            value >= Aged.value -> Aged
            else -> entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("unknown wait approval state: $value")
        }
    }
}

data class WaitGroupRequest(
    val orderCode: String,
    val task: WaitGroupTask,
    val approvalState: WaitApprovalState = WaitApprovalState.Pending
)

data class BreakfastRightCacheNode(
    val orderCode: String,
    val cachedAtEpochMs: Long,
    val cardNo: String? = null,
    val nameB64: String? = null,
    val sexB64: String? = null,
    val phone: String? = null,
    val avatarUrl: String? = null,
    val birthDate: String? = null,
    val packageNameB64: String? = null,
    val examTypeB64: String? = null,
    val organizationNameB64: String? = null,
    val examTotalPriceB64: String? = null,
    val hasBreakfast: Boolean? = null,
    val allowBreakfast: Boolean? = null,
    val breakfastStatus: Int? = null,
    val reasonB64: String? = null,
    val cacheIndex: Long = 0L
)

data class WaitGroupExecutionResult(
    /** False means a transport failure which remains queued for retry. */
    val completed: Boolean,
    val success: Boolean = false,
    val responseText: String? = null,
    val message: String? = null
)

data class AgedUnregisteredRequest(
    val orderCode: String,
    val createdAtEpochMs: Long
)

data class IgnoredCacheNodeRef(
    val id: String,
    val orderCode: String
)

data class IgnoredOrderSnapshot(
    val nodeId: String,
    val orderCode: String,
    val cacheNode: BreakfastRightCacheNode?,
    val createdAtEpochMs: Long,
    val cacheIndex: Long
)

enum class WaitGroupOutcome { Completed, Failed, AgedOut }

data class WaitGroupOutput(
    val id: String,
    val request: WaitGroupRequest,
    val outcome: WaitGroupOutcome,
    val result: WaitGroupExecutionResult,
    val createdAtEpochMs: Long,
    val cacheNode: BreakfastRightCacheNode? = null
)

interface WaitGroupIO {
    val onwaitgroup: SharedFlow<WaitGroupOutput>
    val lenUpdates: StateFlow<Int>
    suspend fun push(
        orderCode: String,
        action: ApprovalQueueAction,
        approvalState: WaitApprovalState = WaitApprovalState.Pending,
        cacheNode: BreakfastRightCacheNode? = null,
        ignoredSourceNodeId: String? = null
    ): Boolean
    suspend fun findIgnoredOrder(orderCode: String): IgnoredOrderSnapshot?
    suspend fun ignoredOrderSnapshots(): List<IgnoredOrderSnapshot>
    suspend fun insertIgnoredOrder(orderCode: String): Boolean
    suspend fun insertIgnoredOrders(orderCodes: List<String>): Int
    suspend fun ignoredOrdersMissingCache(): List<IgnoredCacheNodeRef>
    suspend fun overwriteIgnoredCache(
        nodeId: String,
        orderCode: String,
        cacheNode: BreakfastRightCacheNode
    ): Boolean
    fun len(): Int
    fun enroll()
    fun ageUnregistered(force: Boolean = false)
    fun persist()
}

/**
 * Stable object boundary around a replaceable WaitGroup instance.
 *
 * Consumers keep collecting this object's flows while a failed/closed delegate is destroyed and
 * recreated. Only the broken queue instance and its forwarding jobs are replaced.
 */
class WaitGroupBuffer(
    context: Context,
    private val executor: suspend (WaitGroupRequest) -> WaitGroupExecutionResult,
    private val agedUnregisteredExecutor: suspend (List<AgedUnregisteredRequest>) -> WaitGroupExecutionResult
) : WaitGroupIO {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val declarationLock = Any()
    private val outputs = MutableSharedFlow<WaitGroupOutput>(extraBufferCapacity = 64)
    private val length = MutableStateFlow(0)
    override val onwaitgroup: SharedFlow<WaitGroupOutput> = outputs
    override val lenUpdates: StateFlow<Int> = length.asStateFlow()

    /** Null until construction/restore finishes; scan reads must never resolve this delegate. */
    @Volatile
    private var delegate: WaitGroup? = null
    private var attachedTarget: WaitGroup? = null
    private var outputForwarder: Job? = null
    private var lengthForwarder: Job? = null

    override suspend fun push(
        orderCode: String,
        action: ApprovalQueueAction,
        approvalState: WaitApprovalState,
        cacheNode: BreakfastRightCacheNode?,
        ignoredSourceNodeId: String?
    ): Boolean {
        if (orderCode.isBlank()) return false
        val active = resolveWaitGroup()
        val accepted = try {
            active.push(orderCode, action, approvalState, cacheNode, ignoredSourceNodeId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(WAIT_GROUP_TAG, "wait group push failed; replacing delegate", error)
            false
        }
        if (accepted) {
            length.value = active.len()
            return true
        }

        val replacement = replaceFailedWaitGroup(active)
        return try {
            replacement.push(
                orderCode,
                action,
                approvalState,
                cacheNode,
                ignoredSourceNodeId
            ).also { replacementAccepted ->
                if (replacementAccepted) length.value = replacement.len()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(WAIT_GROUP_TAG, "replacement wait group rejected push", error)
            false
        }
    }

    override suspend fun insertIgnoredOrder(orderCode: String): Boolean =
        resolveWaitGroup().insertIgnoredOrder(orderCode)

    override suspend fun insertIgnoredOrders(orderCodes: List<String>): Int =
        resolveWaitGroup().insertIgnoredOrders(orderCodes)

    override suspend fun findIgnoredOrder(orderCode: String): IgnoredOrderSnapshot? {
        val active = delegate ?: return null
        val snapshot = active.findIgnoredOrder(orderCode)
        // Reject a replacement observed at this read's final delegate identity check.
        return snapshot.takeIf { delegate === active }
    }

    override suspend fun ignoredOrderSnapshots(): List<IgnoredOrderSnapshot> =
        resolveWaitGroup().ignoredOrderSnapshots()

    override suspend fun ignoredOrdersMissingCache(): List<IgnoredCacheNodeRef> =
        resolveWaitGroup().ignoredOrdersMissingCache()

    override suspend fun overwriteIgnoredCache(
        nodeId: String,
        orderCode: String,
        cacheNode: BreakfastRightCacheNode
    ): Boolean = resolveWaitGroup().overwriteIgnoredCache(nodeId, orderCode, cacheNode)

    override fun len(): Int = length.value

    override fun enroll() {
        // 启动空实现边界：调用线程只负责投递，真实队列恢复在独立 IO 协程中完成。
        scope.launch {
            withResolvedWaitGroup("enroll", Unit) { it.enroll() }
        }
    }

    override fun ageUnregistered(force: Boolean) {
        withResolvedWaitGroup("ageUnregistered", Unit) { it.ageUnregistered(force) }
    }

    override fun persist() {
        withResolvedWaitGroup("persist", Unit) { it.persist() }
    }

    private fun createWaitGroup(): WaitGroup = WaitGroup(
        context = appContext,
        executor = executor,
        agedUnregisteredExecutor = agedUnregisteredExecutor
    )

    private fun resolveWaitGroup(): WaitGroup {
        delegate?.let { return it }
        return synchronized(declarationLock) {
            delegate ?: createWaitGroup().also { replacement ->
                delegate = replacement
                attach(replacement)
            }
        }
    }

    private fun replaceFailedWaitGroup(failed: WaitGroup): WaitGroup {
        return synchronized(declarationLock) {
            val current = delegate
            if (current === failed) {
                delegate = null
                destroy(failed)
                Log.i(WAIT_GROUP_TAG, "failed wait group destroyed; declaration reset")
            }
            resolveWaitGroup().also(WaitGroup::enroll)
        }
    }

    /** 显式接收待销毁对象；销毁不是 WaitGroupIO 的继承职责。 */
    private fun destroy(target: WaitGroup) {
        if (attachedTarget === target) {
            outputForwarder?.cancel()
            lengthForwarder?.cancel()
            outputForwarder = null
            lengthForwarder = null
            attachedTarget = null
        }
        target.shutdown()
    }

    private inline fun <T> withResolvedWaitGroup(
        operation: String,
        fallback: T,
        action: (WaitGroup) -> T
    ): T {
        return try {
            val active = resolveWaitGroup()
            try {
                action(active)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(WAIT_GROUP_TAG, "$operation failed; replacing wait group", error)
                action(replaceFailedWaitGroup(active))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(WAIT_GROUP_TAG, "$operation initialization/recovery failed", error)
            fallback
        }
    }

    private fun attach(target: WaitGroup) {
        attachedTarget = target
        length.value = target.len()
        outputForwarder = scope.launch {
            target.onwaitgroup.collect { output ->
                if (delegate === target) outputs.emit(output)
            }
        }
        lengthForwarder = scope.launch {
            target.lenUpdates.collect { value ->
                if (delegate === target) length.value = value
            }
        }
        scope.launch {
            while (delegate === target) {
                delay(WAIT_GROUP_AGING_TICK_MS)
                if (delegate === target) target.ageUnregistered()
            }
        }
    }
}

/** Persistent round-robin queue. Only restore/the actor own Nodes; scan reads use published values. */
class WaitGroup(
    context: Context,
    private val executor: suspend (WaitGroupRequest) -> WaitGroupExecutionResult,
    private val agedUnregisteredExecutor: suspend (List<AgedUnregisteredRequest>) -> WaitGroupExecutionResult
) : WaitGroupIO {
    private data class Node(
        val id: String,
        var request: WaitGroupRequest,
        val createdAtEpochMs: Long,
        var approvalState: WaitApprovalState,
        var cacheNode: BreakfastRightCacheNode? = null,
        var approvalResponseText: String? = null,
        var awaitingAction: Boolean = false,
        var pendingStatusTask: WaitGroupTask? = null,
        var nextAttemptAtEpochMs: Long = 0L,
        var next: Node? = null,
        val cacheIndex: Long = 0L
    )

    private sealed interface Command {
        data class Push(
            val request: WaitGroupRequest,
            val cacheNode: BreakfastRightCacheNode?,
            val ignoredSourceNodeId: String?,
            val insertedLength: CompletableDeferred<Int>
        ) : Command
        data class InsertIgnoredOrder(
            val orderCode: String,
            val inserted: CompletableDeferred<Boolean>
        ) : Command
        data class InsertIgnoredOrders(
            val orderCodes: List<String>,
            val insertedCount: CompletableDeferred<Int>
        ) : Command
        data class ReadIgnoredMissingCache(
            val result: CompletableDeferred<List<IgnoredCacheNodeRef>>
        ) : Command
        data class ReadIgnoredOrders(
            val result: CompletableDeferred<List<IgnoredOrderSnapshot>>
        ) : Command
        data class OverwriteIgnoredCache(
            val nodeId: String,
            val orderCode: String,
            val cacheNode: BreakfastRightCacheNode,
            val updated: CompletableDeferred<Boolean>
        ) : Command
        data class Finished(
            val nodeId: String,
            val result: WaitGroupExecutionResult,
            val approvalProbe: Boolean = false
        ) : Command
        data class AgeUnregistered(val force: Boolean) : Command
        data class AgingFinished(
            val nodeIds: List<String>,
            val result: WaitGroupExecutionResult
        ) : Command
        data object Persist : Command
        data object Wake : Command
        data object Stop : Command
    }

    private val preferences = context.applicationContext
        .getSharedPreferences(WAIT_GROUP_PREFS, Context.MODE_PRIVATE)
    /** Internal persistent index; intentionally not exposed through WaitGroupIO/std. */
    private val cacheIndexPreferences = context.applicationContext
        .getSharedPreferences(WAIT_GROUP_CACHE_INDEX_PREFS, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val pendingReplies = ConcurrentHashMap.newKeySet<CompletableDeferred<*>>()
    private val ignoredReadIndex = IgnoredOrderReadIndex()
    @Volatile private var stopped = false
    private val outputs = MutableSharedFlow<WaitGroupOutput>(extraBufferCapacity = 64)
    private val length = MutableStateFlow(0)
    override val onwaitgroup: SharedFlow<WaitGroupOutput> = outputs
    override val lenUpdates: StateFlow<Int> = length.asStateFlow()

    private var current: Node? = null
    private var size = 0
    private var revision = 0L
    private var nextCacheIndex = 1L
    private var lastSnapshotHash = ""
    private var processingNodeId: String? = null
    private var agingNodeIds: Set<String> = emptySet()
    private var agingBudget = 0
    private var forceAging = false
    private var wakeScheduled = false
    @Volatile private var enrolled = false

    init {
        restore()
        length.value = ringSize()
        scope.launch { actorLoop() }
    }

    override suspend fun push(
        orderCode: String,
        action: ApprovalQueueAction,
        approvalState: WaitApprovalState,
        cacheNode: BreakfastRightCacheNode?,
        ignoredSourceNodeId: String?
    ): Boolean {
        val normalized = orderCode.trim()
        if (normalized.isEmpty()) return false
        val insertedLength = CompletableDeferred<Int>()
        val accepted = submit(
            Command.Push(
                WaitGroupRequest(normalized, action.toTask(), approvalState),
                cacheNode?.takeIf { it.orderCode == normalized },
                ignoredSourceNodeId,
                insertedLength
            )
        )
        if (!accepted) {
            Log.e(WAIT_GROUP_TAG, "push rejected because wait group is closed: orderCode=$normalized")
            return false
        }
        insertedLength.await()
        return true
    }

    override suspend fun findIgnoredOrder(orderCode: String): IgnoredOrderSnapshot? {
        val normalized = orderCode.trim()
        if (normalized.isEmpty()) return null
        return ignoredReadIndex.get(normalized)
    }

    override suspend fun ignoredOrderSnapshots(): List<IgnoredOrderSnapshot> {
        val result = CompletableDeferred<List<IgnoredOrderSnapshot>>()
        if (!submit(Command.ReadIgnoredOrders(result))) return emptyList()
        return result.await()
    }

    override suspend fun insertIgnoredOrder(orderCode: String): Boolean {
        val normalized = orderCode.trim()
        if (normalized.isEmpty()) return false
        val inserted = CompletableDeferred<Boolean>()
        if (!submit(Command.InsertIgnoredOrder(normalized, inserted))) return false
        return inserted.await()
    }

    override suspend fun insertIgnoredOrders(orderCodes: List<String>): Int {
        val normalized = orderCodes.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()
        if (normalized.isEmpty()) return 0
        val insertedCount = CompletableDeferred<Int>()
        if (!submit(Command.InsertIgnoredOrders(normalized, insertedCount))) {
            return 0
        }
        return insertedCount.await()
    }

    override suspend fun ignoredOrdersMissingCache(): List<IgnoredCacheNodeRef> {
        val result = CompletableDeferred<List<IgnoredCacheNodeRef>>()
        if (!submit(Command.ReadIgnoredMissingCache(result))) return emptyList()
        return result.await()
    }

    override suspend fun overwriteIgnoredCache(
        nodeId: String,
        orderCode: String,
        cacheNode: BreakfastRightCacheNode
    ): Boolean {
        val updated = CompletableDeferred<Boolean>()
        if (!submit(
                Command.OverwriteIgnoredCache(nodeId, orderCode, cacheNode, updated)
            )
        ) return false
        return updated.await()
    }

    override fun len(): Int = length.value

    override fun enroll() {
        enrolled = true
        commands.trySend(Command.Wake)
    }

    override fun ageUnregistered(force: Boolean) {
        commands.trySend(Command.AgeUnregistered(force))
    }

    override fun persist() {
        commands.trySend(Command.Persist)
    }

    internal fun shutdown() {
        // Withdraw reads and fail even the in-flight reply before waiting for any disk operation.
        stopped = true
        ignoredReadIndex.close()
        commands.trySend(Command.Stop)
        closeAndFailPending(IllegalStateException("wait group stopped"))
    }

    private fun Command.reply(): CompletableDeferred<*>? = when (this) {
        is Command.Push -> insertedLength
        is Command.InsertIgnoredOrder -> inserted
        is Command.InsertIgnoredOrders -> insertedCount
        is Command.ReadIgnoredMissingCache -> result
        is Command.ReadIgnoredOrders -> result
        is Command.OverwriteIgnoredCache -> updated
        else -> null
    }

    private fun submit(command: Command): Boolean {
        val reply = checkNotNull(command.reply())
        pendingReplies.add(reply)
        reply.invokeOnCompletion { pendingReplies.remove(reply) }
        if (stopped || commands.trySend(command).isFailure) {
            reply.completeExceptionally(IllegalStateException("wait group stopped"))
            return false
        }
        return true
    }

    private fun closeAndFailPending(error: Throwable) {
        stopped = true
        ignoredReadIndex.close()
        commands.close()
        pendingReplies.forEach { it.completeExceptionally(error) }
    }

    private suspend fun actorLoop() {
        var stopCause: Throwable = IllegalStateException("wait group stopped")
        try {
            for (command in commands) {
                if (stopped && command != Command.Stop) {
                    command.reply()?.completeExceptionally(stopCause)
                    continue
                }
                try {
                    when (command) {
                        is Command.Push -> {
                            removeExpiredIgnoredCaches(System.currentTimeMillis())
                            command.ignoredSourceNodeId?.let { sourceNodeId ->
                                unlinkIgnoredSource(sourceNodeId, command.request.orderCode)
                            }
                            insertOrCompact(command.request, command.cacheNode)
                            persistIfChanged()
                            command.insertedLength.complete(ringSize())
                        }
                        is Command.InsertIgnoredOrder -> {
                            removeExpiredIgnoredCaches(System.currentTimeMillis())
                            val duplicate = nodes().any {
                                it.approvalState == WaitApprovalState.Ignored &&
                                    it.request.orderCode == command.orderCode
                            }
                            if (!duplicate) {
                                insertAfterCurrent(
                                    WaitGroupRequest(
                                        orderCode = command.orderCode,
                                        task = WaitGroupTask.QueryBreakfastRight,
                                        approvalState = WaitApprovalState.Ignored
                                    )
                                )
                            }
                            // Pruning changes the ring even when every insertion is a duplicate.
                            persistIfChanged()
                            command.inserted.complete(!duplicate)
                        }
                        is Command.InsertIgnoredOrders -> {
                            removeExpiredIgnoredCaches(System.currentTimeMillis())
                            val existing = nodes().asSequence()
                                .filter { it.approvalState == WaitApprovalState.Ignored }
                                .map { it.request.orderCode }
                                .toMutableSet()
                            var insertedCount = 0
                            command.orderCodes.forEach { orderCode ->
                                if (existing.add(orderCode)) {
                                    insertAfterCurrent(
                                        WaitGroupRequest(
                                            orderCode = orderCode,
                                            task = WaitGroupTask.QueryBreakfastRight,
                                            approvalState = WaitApprovalState.Ignored
                                        )
                                    )
                                    insertedCount += 1
                                }
                            }
                            persistIfChanged()
                            command.insertedCount.complete(insertedCount)
                        }
                        is Command.ReadIgnoredMissingCache -> {
                            removeExpiredIgnoredCaches(System.currentTimeMillis())
                            persistIfChanged()
                            command.result.complete(nodes().filter {
                                it.approvalState == WaitApprovalState.Ignored && it.cacheNode == null
                            }.map { IgnoredCacheNodeRef(it.id, it.request.orderCode) })
                        }
                        is Command.ReadIgnoredOrders -> {
                            removeExpiredIgnoredCaches(System.currentTimeMillis())
                            persistIfChanged()
                            command.result.complete(nodes().filter {
                                it.approvalState == WaitApprovalState.Ignored
                            }.map { it.ignoredSnapshot() })
                        }
                        is Command.OverwriteIgnoredCache -> {
                            val node = find(command.nodeId)
                            val now = System.currentTimeMillis()
                            val expired = node != null && unlinkExpiredIgnoredCache(node, now)
                            if (expired) persistIfChanged()
                            val updated = node != null && !expired &&
                                node.approvalState == WaitApprovalState.Ignored &&
                                node.request.orderCode == command.orderCode &&
                                command.cacheNode.orderCode == command.orderCode &&
                                (command.cacheNode.cacheIndex == 0L ||
                                    command.cacheNode.cacheIndex == node.cacheIndex) &&
                                !isIgnoredCacheExpired(command.cacheNode.cachedAtEpochMs, now) &&
                                ignoredReadIndex.matches(command.orderCode, node.id, node.cacheIndex)
                            if (updated) {
                                val indexedCache = command.cacheNode.copy(cacheIndex = node!!.cacheIndex)
                                node.cacheNode = indexedCache
                                publishIgnored(node)
                                writeCacheIndex(indexedCache)
                            }
                            command.updated.complete(updated)
                        }
                        is Command.Finished -> finish(command)
                        is Command.AgeUnregistered -> {
                            removeExpiredIgnoredCaches(System.currentTimeMillis())
                            persistIfChanged()
                            agingBudget = WAIT_GROUP_AGING_BATCH_SIZE
                            forceAging = forceAging || command.force
                        }
                        is Command.AgingFinished -> finishAging(command)
                        Command.Persist -> persistIfChanged(force = true)
                        Command.Wake -> wakeScheduled = false
                        Command.Stop -> {
                            closeAndFailPending(stopCause)
                            persistIfChanged(force = true)
                            return
                        }
                    }
                    if (!stopped) scheduleNext()
                } catch (cancelled: CancellationException) {
                    command.reply()?.completeExceptionally(cancelled)
                    throw cancelled
                } catch (error: Throwable) {
                    // Fail every reply rather than reporting success or leaving the caller hanging.
                    command.reply()?.completeExceptionally(error)
                    Log.e(WAIT_GROUP_TAG, "ring command failed: $command", error)
                    if (command == Command.Stop) return
                }
            }
        } catch (error: Throwable) {
            stopCause = error
            throw error
        } finally {
            closeAndFailPending(stopCause)
            while (true) {
                val pending = commands.tryReceive().getOrNull() ?: break
                pending.reply()?.completeExceptionally(stopCause)
            }
            scope.cancel()
        }
    }

    private fun scheduleNext() {
        if (!enrolled || processingNodeId != null || agingNodeIds.isNotEmpty()) return
        val now = System.currentTimeMillis()
        val agedUnregistered = if (agingBudget > 0) {
            nodes().filter {
                // Only an answered query awaiting operator action is an
                // unregistered scan. Enroll/CancelEnrollment are status-change
                // commands and must never enter an unregistered-scan batch.
                it.approvalState != WaitApprovalState.Ignored &&
                    it.request.task == WaitGroupTask.QueryBreakfastRight &&
                    it.awaitingAction &&
                    (forceAging || now - it.createdAtEpochMs >= WAIT_GROUP_UNREGISTERED_MAX_AGE_MS)
            }.take(agingBudget)
        } else {
            emptyList()
        }
        if (agedUnregistered.isNotEmpty()) {
            agingBudget = 0
            forceAging = false
            val nodeIds = agedUnregistered.map(Node::id)
            agingNodeIds = nodeIds.toSet()
            val records = agedUnregistered.map {
                AgedUnregisteredRequest(it.request.orderCode, it.createdAtEpochMs)
            }
            scope.launch {
                val result = try {
                    agedUnregisteredExecutor(records)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    WaitGroupExecutionResult(
                        completed = false,
                        message = error.message ?: error.javaClass.simpleName
                    )
                }
                commands.trySend(Command.AgingFinished(nodeIds, result))
            }
            return
        }
        if (agingBudget > 0) agingBudget = 0
        forceAging = false
        // State 3 is a retained inert record: do not submit, age, retry or dequeue it.
        val pendingNodes = nodes().filter {
            !it.awaitingAction && it.approvalState != WaitApprovalState.Ignored
        }
        val node = pendingNodes.firstOrNull { it.nextAttemptAtEpochMs <= now }
        if (node == null) {
            val nextAttemptAt = pendingNodes.minOfOrNull { it.nextAttemptAtEpochMs } ?: return
            if (!wakeScheduled) {
                wakeScheduled = true
                scope.launch {
                    delay((nextAttemptAt - now).coerceAtLeast(1L))
                    commands.trySend(Command.Wake)
                }
            }
            return
        }
        setCurrent(node)
        if (System.currentTimeMillis() - node.createdAtEpochMs >= WAIT_GROUP_MAX_AGE_MS) {
            processingNodeId = node.id
            commands.trySend(
                Command.Finished(
                    node.id,
                    WaitGroupExecutionResult(completed = true, message = "任务已超过最长保留时间")
                )
            )
            return
        }
        processingNodeId = node.id
        if (
            node.request.task != WaitGroupTask.QueryBreakfastRight &&
            node.approvalState == WaitApprovalState.Blocked
        ) {
            commands.trySend(
                Command.Finished(
                    node.id,
                    WaitGroupExecutionResult(
                        completed = true,
                        responseText = node.approvalResponseText,
                        message = "无早餐权益：已拦截早餐状态请求"
                    )
                )
            )
            return
        }
        val approvalProbe = node.request.task != WaitGroupTask.QueryBreakfastRight &&
            node.approvalState == WaitApprovalState.Pending
        val executableRequest = if (approvalProbe) {
            node.request.copy(task = WaitGroupTask.QueryBreakfastRight)
        } else {
            node.request
        }
        scope.launch {
            val result = runCatching { executor(executableRequest) }
                .onFailure { Log.w(WAIT_GROUP_TAG, "request failed: ${it.message}") }
                .getOrElse { WaitGroupExecutionResult(completed = false, message = it.message) }
            commands.trySend(Command.Finished(node.id, result, approvalProbe))
        }
    }

    private fun finishAging(command: Command.AgingFinished) {
        agingNodeIds = emptySet()
        if (command.result.completed && command.result.success) {
            command.nodeIds.mapNotNull(::find).forEach { node ->
                // Defensive task check: cancellation/status-change nodes must bypass
                // /upload_unregistered_scan even if a stale command contains their id.
                if (
                    node.approvalState == WaitApprovalState.Ignored ||
                    node.request.task != WaitGroupTask.QueryBreakfastRight ||
                    !node.awaitingAction
                ) {
                    Log.w(WAIT_GROUP_TAG, "non-query node bypassed aging upload result task=${node.request.task}")
                    return@forEach
                }
                node.approvalState = WaitApprovalState.Aged
                val removed = remove(node)
                Log.i(
                    WAIT_GROUP_TAG,
                    "aged query uploaded and dequeued=$removed orderCode=${node.request.orderCode} length=$size"
                )
            }
            persistIfChanged()
        } else {
            Log.w(
                WAIT_GROUP_TAG,
                "aged query batch retained after upload failure: ${command.result.message ?: "unknown"}"
            )
        }
    }

    private suspend fun finish(command: Command.Finished) {
        if (processingNodeId != command.nodeId) return
        processingNodeId = null
        val node = find(command.nodeId) ?: return
        if (node.approvalState == WaitApprovalState.Ignored) {
            Log.d(
                WAIT_GROUP_TAG,
                "state-3 node retained; execution result ignored orderCode=${node.request.orderCode}"
            )
            return
        }
        if (command.approvalProbe && command.result.completed && command.result.success) {
            val parsedCacheNode = command.result.responseText?.let(::parseBreakfastRightCacheNode)
            val resolvedState = parsedCacheNode?.allowBreakfast?.toApprovalState()
                ?: command.result.responseText?.let(::parseApprovalState)
            if (resolvedState == null) {
                node.nextAttemptAtEpochMs = System.currentTimeMillis() + WAIT_GROUP_RETRY_DELAY_MS
                revision += 1
                persistIfChanged()
                return
            }
            node.approvalState = resolvedState
            if (parsedCacheNode != null) node.cacheNode = parsedCacheNode
            node.approvalResponseText = command.result.responseText
            node.nextAttemptAtEpochMs = 0L
            revision += 1
            persistIfChanged()
            return
        }
        if (command.result.completed) {
            val agedOut = System.currentTimeMillis() - node.createdAtEpochMs >= WAIT_GROUP_MAX_AGE_MS
            val completedRequest = node.request
            val output = WaitGroupOutput(
                id = node.id,
                request = completedRequest,
                outcome = when {
                    agedOut -> WaitGroupOutcome.AgedOut
                    command.result.success -> WaitGroupOutcome.Completed
                    else -> WaitGroupOutcome.Failed
                },
                result = command.result.copy(
                    responseText = command.result.responseText ?: node.approvalResponseText
                ),
                createdAtEpochMs = node.createdAtEpochMs,
                cacheNode = node.cacheNode
            )
            if (
                completedRequest.task == WaitGroupTask.QueryBreakfastRight &&
                command.result.success &&
                !agedOut
            ) {
                val parsedCacheNode = command.result.responseText?.let(::parseBreakfastRightCacheNode)
                val resolvedState = parsedCacheNode?.allowBreakfast?.toApprovalState()
                    ?: command.result.responseText?.let(::parseApprovalState)
                    ?: WaitApprovalState.Pending
                node.approvalState = resolvedState
                if (parsedCacheNode != null) node.cacheNode = parsedCacheNode
                node.approvalResponseText = command.result.responseText
                val pendingStatusTask = node.pendingStatusTask
                if (pendingStatusTask == null) {
                    node.awaitingAction = true
                } else {
                    // A background approval arrived while this query was in flight.
                    // Convert the same ring node instead of inserting a second node.
                    node.request = WaitGroupRequest(
                        orderCode = completedRequest.orderCode,
                        task = pendingStatusTask,
                        approvalState = resolvedState
                    )
                    node.pendingStatusTask = null
                    node.awaitingAction = false
                    node.nextAttemptAtEpochMs = 0L
                }
                current = node.next
                revision += 1
                persistIfChanged()
            } else {
                val lengthBeforeRemoval = size
                val removed = remove(node)
                persistIfChanged()
                Log.i(
                    WAIT_GROUP_TAG,
                    "completed node dequeued=$removed task=${node.request.task} " +
                        "orderCode=${node.request.orderCode} length=$lengthBeforeRemoval->$size"
                )
            }
            outputs.emit(output)
        } else {
            current = node.next
            node.nextAttemptAtEpochMs = System.currentTimeMillis() + WAIT_GROUP_RETRY_DELAY_MS
            revision += 1
            persistIfChanged()
        }
    }

    /** Repeated scans remain valid after completion; only equivalent pending work is compacted. */
    private fun insertOrCompact(
        request: WaitGroupRequest,
        cacheNode: BreakfastRightCacheNode?
    ) {
        val matches = nodes().filter {
            it.request.orderCode == request.orderCode &&
                it.approvalState != WaitApprovalState.Ignored
        }
        var resolvedState = request.approvalState
        var approvalResponseText: String? = null
        var resolvedCacheNode = cacheNode
        if (request.task != WaitGroupTask.QueryBreakfastRight) {
            val inFlightQuery = matches.firstOrNull {
                it.request.task == WaitGroupTask.QueryBreakfastRight && it.id == processingNodeId
            }
            if (inFlightQuery != null) {
                inFlightQuery.pendingStatusTask = request.task
                if (cacheNode != null) inFlightQuery.cacheNode = cacheNode
                revision += 1
                Log.i(
                    WAIT_GROUP_TAG,
                    "status intent attached to in-flight query without growing ring: " +
                        "orderCode=${request.orderCode} task=${request.task} length=$size"
                )
                return
            }
            matches.filter {
                it.request.task == WaitGroupTask.QueryBreakfastRight && it.id != processingNodeId
            }.forEach {
                if (it.awaitingAction) {
                    resolvedState = it.approvalState
                    approvalResponseText = it.approvalResponseText
                    if (resolvedCacheNode == null) resolvedCacheNode = it.cacheNode
                }
                remove(it)
            }
        }
        val remainingMatches = nodes().filter {
            it.request.orderCode == request.orderCode &&
                it.approvalState != WaitApprovalState.Ignored
        }
        val cancellation = remainingMatches.firstOrNull {
            it.request.task == WaitGroupTask.CancelEnrollment
        }
        if (cancellation != null) {
            if (resolvedCacheNode != null) cancellation.cacheNode = resolvedCacheNode
            remainingMatches.filter { it !== cancellation && it.id != processingNodeId }.forEach(::remove)
            return
        }
        if (request.task == WaitGroupTask.CancelEnrollment) {
            remainingMatches.filter { it.id != processingNodeId }.forEach(::remove)
        } else if (remainingMatches.any { it.request.task == request.task }) {
            if (resolvedCacheNode != null) {
                remainingMatches.first { it.request.task == request.task }.cacheNode = resolvedCacheNode
                revision += 1
            }
            return
        }
        insertAfterCurrent(
            request.copy(approvalState = resolvedState),
            resolvedCacheNode,
            approvalResponseText
        )
    }

    private fun insertAfterCurrent(
        request: WaitGroupRequest,
        cacheNode: BreakfastRightCacheNode? = null,
        approvalResponseText: String? = null
    ) {
        val node = Node(
            UUID.randomUUID().toString(),
            request,
            System.currentTimeMillis(),
            request.approvalState,
            cacheNode,
            approvalResponseText,
            cacheIndex = if (request.approvalState == WaitApprovalState.Ignored) {
                allocateCacheIndex()
            } else 0L
        )
        if (request.approvalState == WaitApprovalState.Ignored) {
            node.cacheNode = cacheNode?.takeIf { it.orderCode == request.orderCode }
                ?.copy(cacheIndex = node.cacheIndex)
        }
        val cursor = current
        if (cursor == null) {
            node.next = node
            current = node
        } else {
            node.next = cursor.next
            cursor.next = node
        }
        size += 1
        revision += 1
        length.value = size
        publishIgnored(node)
    }

    private fun Node.ignoredSnapshot(): IgnoredOrderSnapshot = IgnoredOrderSnapshot(
        nodeId = id,
        orderCode = request.orderCode,
        cacheNode = cacheNode,
        createdAtEpochMs = createdAtEpochMs,
        cacheIndex = cacheIndex
    )

    private fun publishIgnored(node: Node) {
        if (node.approvalState == WaitApprovalState.Ignored) {
            ignoredReadIndex.publish(node.ignoredSnapshot())
        }
    }

    private fun find(id: String): Node? = nodes().firstOrNull { it.id == id }

    @Suppress("UNUSED_PARAMETER")
    private fun unlinkIgnoredSource(nodeId: String, orderCode: String): Boolean {
        // 空实现位置：状态 3 命中后即使审核结果以状态 1 入队，也不摘除来源节点。
        return false
    }

    private fun removeExpiredIgnoredCaches(nowEpochMs: Long) {
        nodes().filter { isExpiredIgnoredCache(it, nowEpochMs) }.forEach(::unlink)
    }

    private fun unlinkExpiredIgnoredCache(target: Node, nowEpochMs: Long): Boolean {
        if (!isExpiredIgnoredCache(target, nowEpochMs)) return false
        return unlink(target)
    }

    private fun isExpiredIgnoredCache(target: Node, nowEpochMs: Long): Boolean {
        if (target.approvalState != WaitApprovalState.Ignored) return false
        return isIgnoredNodeExpired(
            createdAtEpochMs = target.createdAtEpochMs,
            cachedAtEpochMs = target.cacheNode?.cachedAtEpochMs,
            nowEpochMs = nowEpochMs
        )
    }

    private fun remove(target: Node): Boolean {
        if (target.approvalState == WaitApprovalState.Ignored) {
            Log.d(
                WAIT_GROUP_TAG,
                "state-3 node retained; dequeue ignored orderCode=${target.request.orderCode}"
            )
            return false
        }
        return unlink(target)
    }

    private fun unlink(target: Node): Boolean {
        val cursor = current ?: return false
        if (size == 1 && cursor === target) {
            current = null
            size = 0
        } else {
            var previous = cursor
            var removed = false
            repeat(size) {
                if (!removed && previous.next === target) {
                    previous.next = target.next
                    if (current === target) current = target.next
                    size -= 1
                    removed = true
                } else {
                    previous = previous.next ?: previous
                }
            }
            if (!removed) return false
        }
        revision += 1
        length.value = size
        if (target.approvalState == WaitApprovalState.Ignored) {
            ignoredReadIndex.remove(target.request.orderCode, target.id, target.cacheIndex)
            removeCacheIndex(target.request.orderCode, target.cacheIndex)
        }
        return true
    }

    private fun nodes(): List<Node> {
        val result = ArrayList<Node>(size)
        var node = current
        repeat(size) {
            val item = node ?: return@repeat
            result += item
            node = item.next
        }
        return result
    }

    private fun snapshotJson(): String {
        val records = JSONArray()
        nodes().forEach { node ->
            records.put(JSONObject().apply {
                put("id", node.id)
                put("order_code", node.request.orderCode)
                put("task", node.request.task.value)
                put("approval_state", node.approvalState.value)
                if (node.cacheIndex > 0L) put("cache_index", node.cacheIndex)
                put("approval_response", node.approvalResponseText)
                put("created_at", node.createdAtEpochMs)
                put("awaiting_action", node.awaitingAction)
                put("pending_status_task", node.pendingStatusTask?.value ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("schema", 10)
            put("revision", revision)
            put("next_cache_index", nextCacheIndex)
            put("nodes", records)
        }.toString()
    }

    private fun ringSize(): Int = size

    private fun setCurrent(node: Node?) {
        current = node
    }

    private fun persistIfChanged(force: Boolean = false) {
        val snapshot = snapshotJson()
        val hash = snapshot.sha256()
        if (!force && hash == lastSnapshotHash) return
        if (preferences.edit().putString(WAIT_GROUP_SNAPSHOT, snapshot).commit()) {
            lastSnapshotHash = hash
        } else {
            Log.e(WAIT_GROUP_TAG, "failed to persist ring revision=${ringRevision()}")
        }
    }

    private fun ringRevision(): Long = revision

    private fun restore() {
        runCatching {
            val raw = preferences.getString(WAIT_GROUP_SNAPSHOT, null) ?: return
            val root = JSONObject(raw)
            val schema = root.optInt("schema")
            require(schema in 1..10)
            val migratedCacheTimestamp = System.currentTimeMillis()
            revision = root.optLong("revision")
            nextCacheIndex = if (schema >= 10) {
                root.optLong("next_cache_index", 1L).coerceAtLeast(1L)
            } else 1L
            val records = root.getJSONArray("nodes")
            // Schema 9 -> 10 used to rewrite the entire cache preferences file per node.
            // Stage migration in one editor and commit only after the whole ring is validated.
            var migrationEditor: android.content.SharedPreferences.Editor? = null
            var last: Node? = null
            repeat(records.length()) { index ->
                val item = records.getJSONObject(index)
                val task = if (schema >= 2) {
                    WaitGroupTask.from(item.getInt("task"))
                } else {
                    WaitGroupTask.QueryBreakfastRight
                }
                val approvalState = if (schema >= 4) {
                    WaitApprovalState.from(item.getInt("approval_state"))
                } else {
                    WaitApprovalState.Pending
                }
                if (
                    task == WaitGroupTask.QueryBreakfastRight &&
                    approvalState != WaitApprovalState.Ignored
                ) {
                    Log.w(
                        WAIT_GROUP_TAG,
                        "discarding legacy query node; queue ingress now requires an approval action"
                    )
                    return@repeat
                }
                val restoredCacheIndex = if (approvalState == WaitApprovalState.Ignored) {
                    if (schema >= 10) item.optLong("cache_index", 0L)
                        .takeIf { it > 0L } ?: allocateCacheIndex()
                    else allocateCacheIndex()
                } else 0L
                if (restoredCacheIndex >= nextCacheIndex) nextCacheIndex = restoredCacheIndex + 1L
                val storedCache = readCacheIndex(item.getString("order_code"))
                    ?: if (schema in 7..8 && !item.isNull("cache_node")) {
                        item.getJSONObject("cache_node").toBreakfastRightCacheNode(
                            fallbackCachedAtEpochMs = migratedCacheTimestamp
                        )
                    } else null
                val restoredCache = when {
                    storedCache == null -> null
                    storedCache.cacheIndex == restoredCacheIndex -> storedCache
                    schema < 10 || storedCache.cacheIndex == 0L ->
                        storedCache.copy(cacheIndex = restoredCacheIndex)
                    else -> null // A different nonzero generation is not a migration.
                }
                if (restoredCache != null && (restoredCache !== storedCache || schema in 7..8)) {
                    val editor = migrationEditor ?: cacheIndexPreferences.edit().also { migrationEditor = it }
                    editor.putString(restoredCache.orderCode, restoredCache.toJson().toString())
                }
                val node = Node(
                    id = item.getString("id"),
                    request = WaitGroupRequest(item.getString("order_code"), task),
                    createdAtEpochMs = item.getLong("created_at"),
                    approvalState = approvalState,
                    cacheNode = restoredCache,
                    approvalResponseText = if (schema >= 5 && !item.isNull("approval_response")) {
                        item.optString("approval_response").takeIf(String::isNotBlank)
                    } else {
                        null
                    },
                    awaitingAction = schema >= 3 && item.optBoolean("awaiting_action"),
                    pendingStatusTask = if (schema >= 6 && !item.isNull("pending_status_task")) {
                        WaitGroupTask.from(item.getInt("pending_status_task"))
                    } else {
                        null
                    },
                    cacheIndex = restoredCacheIndex
                )
                if (current == null) current = node else last?.next = node
                last = node
                size += 1
                publishIgnored(node)
            }
            if (current != null) last?.next = current
            val migrationSaved = migrationEditor?.commit() ?: true
            lastSnapshotHash = if (schema == 10) raw.sha256() else ""
            if (migrationSaved) {
                if (schema in 1..9) persistIfChanged(force = true)
            } else {
                Log.e(WAIT_GROUP_TAG, "cache index migration commit failed; keeping legacy disk snapshot")
            }
        }.onFailure {
            ignoredReadIndex.clear()
            current = null
            size = 0
            revision = 0
            nextCacheIndex = 1L
            lastSnapshotHash = ""
            Log.e(WAIT_GROUP_TAG, "discarding invalid persisted ring", it)
        }
    }

    private fun allocateCacheIndex(): Long = nextCacheIndex++

    private fun readCacheIndex(orderCode: String): BreakfastRightCacheNode? =
        cacheIndexPreferences.getString(orderCode, null)?.let { raw ->
            runCatching {
                JSONObject(raw).toBreakfastRightCacheNode(System.currentTimeMillis())
            }.getOrNull()?.takeIf { it.orderCode == orderCode }
        }

    private fun writeCacheIndex(cacheNode: BreakfastRightCacheNode) {
        cacheIndexPreferences.edit()
            .putString(cacheNode.orderCode, cacheNode.toJson().toString())
            .commit()
    }

    private fun removeCacheIndex(orderCode: String, cacheIndex: Long) {
        // A late unlink must not remove the disk cache belonging to a replacement node either.
        if (nodes().any {
                it.approvalState == WaitApprovalState.Ignored && it.request.orderCode == orderCode
            }
        ) return
        val cached = readCacheIndex(orderCode)
        if (cached != null && cached.cacheIndex != cacheIndex) return
        cacheIndexPreferences.edit().remove(orderCode).commit()
    }
}

internal fun parseBreakfastRightCacheNode(responseText: String): BreakfastRightCacheNode? =
    runCatching {
        val root = JSONObject(responseText)
        val payload = root.optJSONObject("data") ?: root
        payload.toBreakfastRightCacheNode(fallbackCachedAtEpochMs = System.currentTimeMillis())
    }.getOrNull()

private fun JSONObject.toBreakfastRightCacheNode(
    fallbackCachedAtEpochMs: Long
): BreakfastRightCacheNode? {
    val orderCode = optNullableString("order_code") ?: return null
    val cachedAtEpochMs = optLong("cached_at", fallbackCachedAtEpochMs)
        .takeIf { it > 0L } ?: fallbackCachedAtEpochMs
    return BreakfastRightCacheNode(
        orderCode = orderCode,
        cachedAtEpochMs = cachedAtEpochMs,
        cardNo = optNullableString("card_no"),
        nameB64 = optNullableString("name_b64"),
        sexB64 = optNullableString("sex_b64"),
        phone = optNullableString("phone"),
        avatarUrl = optNullableString("avatar_url"),
        birthDate = optNullableString("birth_date"),
        packageNameB64 = optNullableString("package_name_b64"),
        examTypeB64 = optNullableString("exam_type_b64"),
        organizationNameB64 = optNullableString("organization_name_b64"),
        examTotalPriceB64 = optNullableString("exam_total_price_b64"),
        hasBreakfast = optNullableBoolean("has_breakfast"),
        allowBreakfast = optNullableBoolean("allow_breakfast"),
        breakfastStatus = optNullableInt("breakfast_status"),
        reasonB64 = optNullableString("reason_b64"),
        cacheIndex = optLong("cache_index", 0L).coerceAtLeast(0L)
    )
}

private fun BreakfastRightCacheNode.toJson(): JSONObject = JSONObject().apply {
    put("order_code", orderCode)
    put("cached_at", cachedAtEpochMs)
    putNullable("card_no", cardNo)
    putNullable("name_b64", nameB64)
    putNullable("sex_b64", sexB64)
    putNullable("phone", phone)
    putNullable("avatar_url", avatarUrl)
    putNullable("birth_date", birthDate)
    putNullable("package_name_b64", packageNameB64)
    putNullable("exam_type_b64", examTypeB64)
    putNullable("organization_name_b64", organizationNameB64)
    putNullable("exam_total_price_b64", examTotalPriceB64)
    putNullable("has_breakfast", hasBreakfast)
    putNullable("allow_breakfast", allowBreakfast)
    putNullable("breakfast_status", breakfastStatus)
    putNullable("reason_b64", reasonB64)
    if (cacheIndex > 0L) put("cache_index", cacheIndex)
}

internal fun isIgnoredCacheExpired(cachedAtEpochMs: Long, nowEpochMs: Long): Boolean =
    cachedAtEpochMs > 0L &&
        nowEpochMs >= cachedAtEpochMs &&
        nowEpochMs - cachedAtEpochMs > IGNORED_CACHE_MAX_AGE_MS

/**
 * State 3 has a hard lifetime from node creation, even when cache refill never succeeds.
 * A successfully filled cache keeps its own existing TTL as an additional expiry condition.
 */
internal fun isIgnoredNodeExpired(
    createdAtEpochMs: Long,
    cachedAtEpochMs: Long?,
    nowEpochMs: Long
): Boolean = isIgnoredCacheExpired(createdAtEpochMs, nowEpochMs) ||
    (cachedAtEpochMs != null && isIgnoredCacheExpired(cachedAtEpochMs, nowEpochMs))

private fun JSONObject.putNullable(name: String, value: Any?) {
    put(name, value ?: JSONObject.NULL)
}

private fun JSONObject.optNullableString(name: String): String? =
    if (!has(name) || isNull(name)) null else optString(name).takeIf(String::isNotBlank)

private fun JSONObject.optNullableBoolean(name: String): Boolean? =
    if (!has(name) || isNull(name) || opt(name) !is Boolean) null else getBoolean(name)

private fun JSONObject.optNullableInt(name: String): Int? =
    if (!has(name) || isNull(name)) null else (opt(name) as? Number)?.toInt()

private fun Boolean.toApprovalState(): WaitApprovalState =
    if (this) WaitApprovalState.Approved else WaitApprovalState.Blocked

private fun parseApprovalState(responseText: String): WaitApprovalState? {
    val allowBreakfast = runCatching {
        val root = JSONObject(responseText)
        val payload = root.optJSONObject("data") ?: root
        if (!payload.has("allow_breakfast") || payload.isNull("allow_breakfast")) {
            null
        } else {
            when (val value = payload.opt("allow_breakfast")) {
                is Boolean -> value
                is Number -> value.toInt() != 0
                is String -> when (value.trim().lowercase()) {
                    "true", "1" -> true
                    "false", "0" -> false
                    else -> null
                }
                else -> null
            }
        }
    }.getOrNull() ?: Regex(
        "\"allow_breakfast\"\\s*:\\s*(?:\"(true|false|1|0)\"|(true|false|1|0))",
        RegexOption.IGNORE_CASE
    )
        .find(responseText)
        ?.groupValues
        ?.drop(1)
        ?.firstOrNull { it.isNotEmpty() }
        ?.lowercase()
        ?.let { it == "true" || it == "1" }
    return when (allowBreakfast) {
        true -> WaitApprovalState.Approved
        false -> WaitApprovalState.Blocked
        null -> null
    }
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
