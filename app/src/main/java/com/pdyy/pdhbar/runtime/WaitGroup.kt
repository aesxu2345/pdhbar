package com.pdyy.pdhbar.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

private const val WAIT_GROUP_PREFS = "breakfast_wait_group"
private const val WAIT_GROUP_SNAPSHOT = "ring_snapshot"
private const val WAIT_GROUP_TAG = "BreakfastWaitGroup"
private const val WAIT_GROUP_RETRY_DELAY_MS = 3_000L
private const val WAIT_GROUP_MAX_AGE_MS = 90L * 24L * 60L * 60L * 1_000L
private const val WAIT_GROUP_UNREGISTERED_MAX_AGE_MS = 10L * 60L * 1_000L
private const val WAIT_GROUP_AGING_TICK_MS = 30_000L
private const val WAIT_GROUP_AGING_BATCH_SIZE = 10

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
    Aged(4);

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

enum class WaitGroupOutcome { Completed, Failed, AgedOut }

data class WaitGroupOutput(
    val id: String,
    val request: WaitGroupRequest,
    val outcome: WaitGroupOutcome,
    val result: WaitGroupExecutionResult,
    val createdAtEpochMs: Long
)

interface WaitGroupIO {
    val onwaitgroup: SharedFlow<WaitGroupOutput>
    val lenUpdates: StateFlow<Int>
    suspend fun push(
        orderCode: String,
        action: ApprovalQueueAction,
        approvalState: WaitApprovalState = WaitApprovalState.Pending
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

    /** 空方法声明；真实 WaitGroup 仅在 push/len/enroll/persist 执行时替换进来。 */
    private val emptyWaitGroupDeclaration: () -> WaitGroup? = { null }
    @Volatile
    private var waitGroupDeclaration: () -> WaitGroup? = emptyWaitGroupDeclaration
    private var attachedTarget: WaitGroup? = null
    private var outputForwarder: Job? = null
    private var lengthForwarder: Job? = null

    override suspend fun push(
        orderCode: String,
        action: ApprovalQueueAction,
        approvalState: WaitApprovalState
    ): Boolean {
        if (orderCode.isBlank()) return false
        val active = resolveWaitGroup()
        val accepted = try {
            active.push(orderCode, action, approvalState)
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
            replacement.push(orderCode, action, approvalState).also { replacementAccepted ->
                if (replacementAccepted) length.value = replacement.len()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(WAIT_GROUP_TAG, "replacement wait group rejected push", error)
            false
        }
    }

    override fun len(): Int = withResolvedWaitGroup("len", length.value) { it.len() }

    override fun enroll() {
        withResolvedWaitGroup("enroll", Unit) { it.enroll() }
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
        waitGroupDeclaration()?.let { return it }
        return synchronized(declarationLock) {
            waitGroupDeclaration() ?: createWaitGroup().also { replacement ->
                waitGroupDeclaration = { replacement }
                attach(replacement)
            }
        }
    }

    private fun replaceFailedWaitGroup(failed: WaitGroup): WaitGroup {
        return synchronized(declarationLock) {
            val current = waitGroupDeclaration()
            if (current === failed) {
                destroy(failed)
                waitGroupDeclaration = emptyWaitGroupDeclaration
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
        val active = resolveWaitGroup()
        return try {
            action(active)
        } catch (error: Throwable) {
            Log.e(WAIT_GROUP_TAG, "$operation failed; replacing wait group", error)
            val replacement = replaceFailedWaitGroup(active)
            runCatching { action(replacement) }
                .onFailure { Log.e(WAIT_GROUP_TAG, "replacement $operation failed", it) }
                .getOrDefault(fallback)
        }
    }

    private fun attach(target: WaitGroup) {
        attachedTarget = target
        length.value = target.len()
        outputForwarder = scope.launch {
            target.onwaitgroup.collect { output ->
                if (waitGroupDeclaration() === target) outputs.emit(output)
            }
        }
        lengthForwarder = scope.launch {
            target.lenUpdates.collect { value ->
                if (waitGroupDeclaration() === target) length.value = value
            }
        }
        scope.launch {
            while (waitGroupDeclaration() === target) {
                delay(WAIT_GROUP_AGING_TICK_MS)
                if (waitGroupDeclaration() === target) target.ageUnregistered()
            }
        }
    }
}

/** Persistent round-robin queue. Mutations are actor-serialized and intentionally lock-free. */
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
        var approvalResponseText: String? = null,
        var awaitingAction: Boolean = false,
        var pendingStatusTask: WaitGroupTask? = null,
        var nextAttemptAtEpochMs: Long = 0L,
        var next: Node? = null
    )

    private sealed interface Command {
        data class Push(
            val request: WaitGroupRequest,
            val insertedLength: CompletableDeferred<Int>
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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val outputs = MutableSharedFlow<WaitGroupOutput>(extraBufferCapacity = 64)
    private val length = MutableStateFlow(0)
    override val onwaitgroup: SharedFlow<WaitGroupOutput> = outputs
    override val lenUpdates: StateFlow<Int> = length.asStateFlow()

    private var current: Node? = null
    private var size = 0
    private var revision = 0L
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
        approvalState: WaitApprovalState
    ): Boolean {
        val normalized = orderCode.trim()
        if (normalized.isEmpty()) return false
        val insertedLength = CompletableDeferred<Int>()
        val result = commands.trySend(
            Command.Push(
                WaitGroupRequest(normalized, action.toTask(), approvalState),
                insertedLength
            )
        )
        if (result.isFailure) {
            Log.e(WAIT_GROUP_TAG, "push rejected because wait group is closed: orderCode=$normalized")
            return false
        }
        insertedLength.await()
        return true
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
        commands.trySend(Command.Persist)
        commands.trySend(Command.Stop)
    }

    private suspend fun actorLoop() {
        while (scope.isActive) {
            val command = commands.receive()
            try {
                when (command) {
                    is Command.Push -> {
                        insertOrCompact(command.request)
                        persistIfChanged()
                        command.insertedLength.complete(ringSize())
                    }
                    is Command.Finished -> finish(command)
                    is Command.AgeUnregistered -> {
                        agingBudget = WAIT_GROUP_AGING_BATCH_SIZE
                        forceAging = forceAging || command.force
                    }
                    is Command.AgingFinished -> finishAging(command)
                    Command.Persist -> persistIfChanged(force = true)
                    Command.Wake -> wakeScheduled = false
                    Command.Stop -> {
                        persistIfChanged(force = true)
                        commands.close()
                        return
                    }
                }
                scheduleNext()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Queue work is best-effort: retain the ring and wait for the next command/tick.
                Log.e(WAIT_GROUP_TAG, "ring command ignored after failure: $command", error)
                if (command is Command.Push && !command.insertedLength.isCompleted) {
                    command.insertedLength.complete(ringSize())
                }
            }
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
        val pendingNodes = nodes().filter { !it.awaitingAction }
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
                if (node.request.task != WaitGroupTask.QueryBreakfastRight || !node.awaitingAction) {
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
        if (command.approvalProbe && command.result.completed && command.result.success) {
            val resolvedState = command.result.responseText?.let(::parseApprovalState)
            if (resolvedState == null) {
                node.nextAttemptAtEpochMs = System.currentTimeMillis() + WAIT_GROUP_RETRY_DELAY_MS
                revision += 1
                persistIfChanged()
                return
            }
            node.approvalState = resolvedState
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
                createdAtEpochMs = node.createdAtEpochMs
            )
            if (
                completedRequest.task == WaitGroupTask.QueryBreakfastRight &&
                command.result.success &&
                !agedOut
            ) {
                val resolvedState = command.result.responseText
                    ?.let(::parseApprovalState)
                    ?: WaitApprovalState.Pending
                node.approvalState = resolvedState
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
    private fun insertOrCompact(request: WaitGroupRequest) {
        val matches = nodes().filter { it.request.orderCode == request.orderCode }
        var resolvedState = request.approvalState
        var approvalResponseText: String? = null
        if (request.task != WaitGroupTask.QueryBreakfastRight) {
            val inFlightQuery = matches.firstOrNull {
                it.request.task == WaitGroupTask.QueryBreakfastRight && it.id == processingNodeId
            }
            if (inFlightQuery != null) {
                inFlightQuery.pendingStatusTask = request.task
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
                }
                remove(it)
            }
        }
        val remainingMatches = nodes().filter { it.request.orderCode == request.orderCode }
        val cancellation = remainingMatches.firstOrNull {
            it.request.task == WaitGroupTask.CancelEnrollment
        }
        if (cancellation != null) {
            remainingMatches.filter { it !== cancellation && it.id != processingNodeId }.forEach(::remove)
            return
        }
        if (request.task == WaitGroupTask.CancelEnrollment) {
            remainingMatches.filter { it.id != processingNodeId }.forEach(::remove)
        } else if (remainingMatches.any { it.request.task == request.task }) {
            return
        }
        insertAfterCurrent(request.copy(approvalState = resolvedState), approvalResponseText)
    }

    private fun insertAfterCurrent(
        request: WaitGroupRequest,
        approvalResponseText: String? = null
    ) {
        val node = Node(
            UUID.randomUUID().toString(),
            request,
            System.currentTimeMillis(),
            request.approvalState,
            approvalResponseText
        )
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
    }

    private fun find(id: String): Node? = nodes().firstOrNull { it.id == id }

    private fun remove(target: Node): Boolean {
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
                put("approval_response", node.approvalResponseText)
                put("created_at", node.createdAtEpochMs)
                put("awaiting_action", node.awaitingAction)
                put("pending_status_task", node.pendingStatusTask?.value ?: JSONObject.NULL)
            })
        }
        return JSONObject().apply {
            put("schema", 6)
            put("revision", revision)
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
        val raw = preferences.getString(WAIT_GROUP_SNAPSHOT, null) ?: return
        runCatching {
            val root = JSONObject(raw)
            val schema = root.optInt("schema")
            require(schema in 1..6)
            revision = root.optLong("revision")
            val records = root.getJSONArray("nodes")
            var last: Node? = null
            repeat(records.length()) { index ->
                val item = records.getJSONObject(index)
                val task = if (schema >= 2) {
                    WaitGroupTask.from(item.getInt("task"))
                } else {
                    WaitGroupTask.QueryBreakfastRight
                }
                if (task == WaitGroupTask.QueryBreakfastRight) {
                    Log.w(
                        WAIT_GROUP_TAG,
                        "discarding legacy query node; queue ingress now requires an approval action"
                    )
                    return@repeat
                }
                val node = Node(
                    id = item.getString("id"),
                    request = WaitGroupRequest(item.getString("order_code"), task),
                    createdAtEpochMs = item.getLong("created_at"),
                    approvalState = if (schema >= 4) {
                        WaitApprovalState.from(item.getInt("approval_state"))
                    } else {
                        WaitApprovalState.Pending
                    },
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
                    }
                )
                if (current == null) current = node else last?.next = node
                last = node
                size += 1
            }
            if (current != null) last?.next = current
            lastSnapshotHash = if (schema == 6) raw.sha256() else ""
        }.onFailure {
            current = null
            size = 0
            revision = 0
            Log.e(WAIT_GROUP_TAG, "discarding invalid persisted ring", it)
        }
    }
}

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
