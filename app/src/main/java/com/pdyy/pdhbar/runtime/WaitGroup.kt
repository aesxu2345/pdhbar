package com.pdyy.pdhbar.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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
    ): Boolean
    fun len(): Int
    fun enroll()
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
    private val executor: suspend (WaitGroupRequest) -> WaitGroupExecutionResult
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
        task: WaitGroupTask,
        approvalState: WaitApprovalState
    ): Boolean {
        if (orderCode.isBlank()) return false
        val active = resolveWaitGroup()
        val accepted = try {
            active.push(orderCode, task, approvalState)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(WAIT_GROUP_TAG, "wait group push failed; replacing delegate", error)
            false
        }
        if (accepted) return true

        val replacement = replaceFailedWaitGroup(active)
        return try {
            replacement.push(orderCode, task, approvalState)
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

    override fun persist() {
        withResolvedWaitGroup("persist", Unit) { it.persist() }
    }

    private fun createWaitGroup(): WaitGroup = WaitGroup(appContext, executor)

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
    }
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
        var approvalResponseText: String? = null,
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
    ): Boolean {
        val normalized = orderCode.trim()
        if (normalized.isEmpty()) return false
        val result = commands.trySend(
            Command.Push(WaitGroupRequest(normalized, task, approvalState))
        )
        if (result.isFailure) {
            Log.e(WAIT_GROUP_TAG, "push rejected because wait group is closed: orderCode=$normalized")
        }
        return result.isSuccess
    }

    override fun len(): Int = length.value

    override fun enroll() {
        enrolled = true
        commands.trySend(Command.Wake)
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
            outputs.emit(
                WaitGroupOutput(
                    id = node.id,
                    request = node.request,
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
            )
            if (
                node.request.task == WaitGroupTask.QueryBreakfastRight &&
                command.result.success &&
                !agedOut
            ) {
                node.approvalState = command.result.responseText
                    ?.let(::parseApprovalState)
                    ?: WaitApprovalState.Pending
                node.approvalResponseText = command.result.responseText
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
        var approvalResponseText: String? = null
        if (request.task != WaitGroupTask.QueryBreakfastRight) {
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
                put("approval_response", node.approvalResponseText)
                put("created_at", node.createdAtEpochMs)
                put("awaiting_action", node.awaitingAction)
            })
        }
        return JSONObject().apply {
            put("schema", 5)
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
            require(schema in 1..5)
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
                    approvalResponseText = if (schema >= 5 && !item.isNull("approval_response")) {
                        item.optString("approval_response").takeIf(String::isNotBlank)
                    } else {
                        null
                    },
                    awaitingAction = schema >= 3 && item.optBoolean("awaiting_action")
                )
                if (current == null) current = node else last?.next = node
                last = node
                size += 1
            }
            if (current != null) last?.next = current
            lastSnapshotHash = if (schema == 5) raw.sha256() else ""
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
