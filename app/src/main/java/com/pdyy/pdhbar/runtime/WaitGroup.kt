package com.pdyy.pdhbar.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

enum class WaitApprovalState(val value: Int) {
    Blocked(0),
    Approved(1),
    Pending(2);

    companion object {
        fun from(value: Int): WaitApprovalState = entries.firstOrNull { it.value == value }
            ?: throw IllegalArgumentException("unknown wait approval state: $value")
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
        task: WaitGroupTask,
        approvalState: WaitApprovalState = WaitApprovalState.Pending
    )
    fun len(): Int
    fun enroll()
    fun persist()
    fun destroy()
}

/** Persistent round-robin queue. All mutations are serialized by the actor. */
class WaitGroup(
    context: Context,
    private val executor: suspend (WaitGroupRequest) -> WaitGroupExecutionResult
) : WaitGroupIO {
    private data class Node(
        val id: String,
        val request: WaitGroupRequest,
        val createdAtEpochMs: Long,
        var approvalState: WaitApprovalState,
        var awaitingAction: Boolean = false,
        var nextAttemptAtEpochMs: Long = 0L,
        var next: Node? = null
    )

    private sealed interface Command {
        data class Push(val request: WaitGroupRequest) : Command
        data class Finished(
            val nodeId: String,
            val result: WaitGroupExecutionResult,
            val approvalProbe: Boolean = false
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
    private var wakeScheduled = false
    @Volatile private var enrolled = false

    init {
        restore()
        length.value = size
        scope.launch { actorLoop() }
    }

    override suspend fun push(
        orderCode: String,
        task: WaitGroupTask,
        approvalState: WaitApprovalState
    ) {
        val normalized = orderCode.trim()
        if (normalized.isEmpty()) return
        commands.send(Command.Push(WaitGroupRequest(normalized, task, approvalState)))
    }

    override fun len(): Int = length.value

    override fun enroll() {
        enrolled = true
        commands.trySend(Command.Wake)
    }

    override fun persist() {
        commands.trySend(Command.Persist)
    }

    override fun destroy() {
        commands.trySend(Command.Persist)
        commands.trySend(Command.Stop)
    }

    private suspend fun actorLoop() {
        while (scope.isActive) {
            when (val command = commands.receive()) {
                is Command.Push -> {
                    insertOrCompact(command.request)
                    persistIfChanged()
                }
                is Command.Finished -> finish(command)
                Command.Persist -> persistIfChanged(force = true)
                Command.Wake -> wakeScheduled = false
                Command.Stop -> {
                    persistIfChanged(force = true)
                    commands.close()
                    return
                }
            }
            scheduleNext()
        }
    }

    private fun scheduleNext() {
        if (!enrolled || processingNodeId != null) return
        val now = System.currentTimeMillis()
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
        current = node
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
            commands.send(Command.Finished(node.id, result, approvalProbe))
        }
    }

    private suspend fun finish(command: Command.Finished) {
        if (processingNodeId != command.nodeId) return
        processingNodeId = null
        val node = find(command.nodeId) ?: return
        if (command.approvalProbe && command.result.completed && command.result.success) {
            node.approvalState = command.result.responseText
                ?.let(::parseApprovalState)
                ?: WaitApprovalState.Blocked
            node.nextAttemptAtEpochMs = 0L
            revision += 1
            persistIfChanged()
            return
        }
        if (command.result.completed) {
            val agedOut = System.currentTimeMillis() - node.createdAtEpochMs >= WAIT_GROUP_MAX_AGE_MS
            outputs.emit(
                WaitGroupOutput(
                    id = node.id,
                    request = node.request,
                    outcome = when {
                        agedOut -> WaitGroupOutcome.AgedOut
                        command.result.success -> WaitGroupOutcome.Completed
                        else -> WaitGroupOutcome.Failed
                    },
                    result = command.result,
                    createdAtEpochMs = node.createdAtEpochMs
                )
            )
            if (
                node.request.task == WaitGroupTask.QueryBreakfastRight &&
                command.result.success &&
                !agedOut
            ) {
                node.approvalState = command.result.responseText
                    ?.let(::parseApprovalState)
                    ?: WaitApprovalState.Blocked
                node.awaitingAction = true
                current = node.next
                revision += 1
                persistIfChanged()
            } else {
                remove(node)
                persistIfChanged()
            }
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
        if (request.task != WaitGroupTask.QueryBreakfastRight) {
            matches.filter {
                it.request.task == WaitGroupTask.QueryBreakfastRight && it.id != processingNodeId
            }.forEach {
                if (it.awaitingAction) resolvedState = it.approvalState
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
        insertAfterCurrent(request.copy(approvalState = resolvedState))
    }

    private fun insertAfterCurrent(request: WaitGroupRequest) {
        val node = Node(
            UUID.randomUUID().toString(),
            request,
            System.currentTimeMillis(),
            request.approvalState
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

    private fun remove(target: Node) {
        val cursor = current ?: return
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
            if (!removed) return
        }
        revision += 1
        length.value = size
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
                put("created_at", node.createdAtEpochMs)
                put("awaiting_action", node.awaitingAction)
            })
        }
        return JSONObject().apply {
            put("schema", 4)
            put("revision", revision)
            put("nodes", records)
        }.toString()
    }

    private fun persistIfChanged(force: Boolean = false) {
        val snapshot = snapshotJson()
        val hash = snapshot.sha256()
        if (!force && hash == lastSnapshotHash) return
        if (preferences.edit().putString(WAIT_GROUP_SNAPSHOT, snapshot).commit()) {
            lastSnapshotHash = hash
        } else {
            Log.e(WAIT_GROUP_TAG, "failed to persist ring revision=$revision")
        }
    }

    private fun restore() {
        val raw = preferences.getString(WAIT_GROUP_SNAPSHOT, null) ?: return
        runCatching {
            val root = JSONObject(raw)
            val schema = root.optInt("schema")
            require(schema in 1..4)
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
                val node = Node(
                    id = item.getString("id"),
                    request = WaitGroupRequest(item.getString("order_code"), task),
                    createdAtEpochMs = item.getLong("created_at"),
                    approvalState = if (schema >= 4) {
                        WaitApprovalState.from(item.getInt("approval_state"))
                    } else {
                        WaitApprovalState.Pending
                    },
                    awaitingAction = schema >= 3 && item.optBoolean("awaiting_action")
                )
                if (current == null) current = node else last?.next = node
                last = node
                size += 1
            }
            if (current != null) last?.next = current
            lastSnapshotHash = if (schema == 4) raw.sha256() else ""
        }.onFailure {
            current = null
            size = 0
            revision = 0
            Log.e(WAIT_GROUP_TAG, "discarding invalid persisted ring", it)
        }
    }
}

private fun parseApprovalState(responseText: String): WaitApprovalState {
    return runCatching {
        val root = JSONObject(responseText)
        val payload = root.optJSONObject("data") ?: root
        if (payload.optBoolean("allow_breakfast", false)) {
            WaitApprovalState.Approved
        } else {
            WaitApprovalState.Blocked
        }
    }.getOrDefault(WaitApprovalState.Blocked)
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
