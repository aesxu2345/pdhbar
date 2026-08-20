package com.pdyy.pdhbar.runtime

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
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val HOURLY_ORDER_TICKER_TAG = "HourlyOrderTicker"
private const val HOURLY_ORDER_TICK_MS = 60L * 60L * 1_000L
private const val ORDER_RANGE_LOOKBACK_SECONDS = 8L * 60L * 60L
private const val TICKER_NETWORK_RETRY_COUNT = 1
private const val CACHE_REFILL_BATCH_SIZE = 12
private const val CACHE_REFILL_REQUEST_GAP_MS = 250L
private val ORDER_CODE_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd")

/**
 * Process-local ticker for inert state-3 records.
 *
 * Its NetTarget and Postman are deliberately separate from the interactive business Postman.
 * Ring mutations remain lock-free here and are serialized by WaitGroup's actor.
 */
class HourlyOrderTicker(
    initialTickerNetTarget: NetTarget,
    private val tickerWaitGroup: WaitGroupIO,
    private val notifyOperator: (String) -> Unit = {}
) {
    private val tickerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tickerNetTarget = initialTickerNetTarget.copy(retry = TICKER_NETWORK_RETRY_COUNT)
    private val tickerPostman = Postman(tickerNetTarget)
    private val immediateRangeRequests = Channel<Unit>(Channel.CONFLATED)
    private val cacheRefillRequests = Channel<Unit>(Channel.CONFLATED)
    private var rangeTickerJob: Job? = null
    private var cacheRefillJob: Job? = null
    private var cacheRefillCursor = 0

    fun start() {
        if (rangeTickerJob?.isActive != true) {
            rangeTickerJob = tickerScope.launch { runRangeTicker() }
        }
        if (cacheRefillJob?.isActive != true) {
            cacheRefillJob = tickerScope.launch { runCacheRefillTicker() }
        }
    }

    fun updateBackendTarget(pipefall: PipeFallIO) {
        tickerPostman.onupdate(pipefall)
    }

    private suspend fun runRangeTicker() {
        var requestReason = "应用启动"
        while (tickerScope.isActive) {
            try {
                val endUtc = Instant.now().epochSecond
                val startUtc = orderRangeStartUtc(endUtc)
                notifyOperator("触发预缓存刷新：$requestReason，正在拉取最近八小时记录")
                Log.i(
                    HOURLY_ORDER_TICKER_TAG,
                    "requesting order range startUtc=$startUtc endUtc=$endUtc"
                )
                val returnedOrderCodes = tickerPostman.queryOrderCodesByTimeRange(startUtc, endUtc)
                val businessDate = LocalDate.now()
                val orderCodes = returnedOrderCodes.filter {
                    orderCodeBelongsToDate(it, businessDate)
                }
                val insertedCount = tickerWaitGroup.insertIgnoredOrders(orderCodes)
                if (insertedCount > 0) cacheRefillRequests.trySend(Unit)
                Log.i(
                    HOURLY_ORDER_TICKER_TAG,
                    "order range applied returned=${returnedOrderCodes.size} " +
                        "sameDay=${orderCodes.size} excludedOtherDay=" +
                        "${returnedOrderCodes.size - orderCodes.size} inserted=$insertedCount " +
                        "duplicates=${orderCodes.size - insertedCount} orderCodes=$orderCodes"
                )
                notifyOperator(
                    "预缓存刷新完成：获取${returnedOrderCodes.size}条，当天${orderCodes.size}条，" +
                        "新增${insertedCount}条，重复${orderCodes.size - insertedCount}条"
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(HOURLY_ORDER_TICKER_TAG, "hourly order range query ignored", error)
                notifyOperator("预缓存刷新失败：${error.message ?: "网络或响应异常"}")
            }
            // The just-finished request already satisfies an empty-pool signal emitted while it
            // was running. Drain it so startup cannot immediately issue the same range twice.
            immediateRangeRequests.tryReceive()
            val immediateRequest = withTimeoutOrNull(HOURLY_ORDER_TICK_MS) {
                immediateRangeRequests.receive()
                Log.i(
                    HOURLY_ORDER_TICKER_TAG,
                    "recent state-3 cache count is zero; requesting range immediately"
                )
                true
            } ?: false
            requestReason = if (immediateRequest) "近期状态3记录为空" else "每小时定时检查"
        }
    }

    private suspend fun runCacheRefillTicker() {
        var previouslyHadRecentNode: Boolean? = null
        while (tickerScope.isActive) {
            val snapshots = try {
                tickerWaitGroup.ignoredOrderSnapshots()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(HOURLY_ORDER_TICKER_TAG, "state-3 cache observation ignored", error)
                emptyList()
            }
            val nowEpochMs = System.currentTimeMillis()
            val recentNodes = snapshots.filter {
                isRecentIgnoredNode(it.createdAtEpochMs, nowEpochMs)
            }
            Log.i(
                HOURLY_ORDER_TICKER_TAG,
                "state-3 event scan total=${snapshots.size} recent=${recentNodes.size} " +
                    "missing=${snapshots.count { it.cacheNode == null }} now=$nowEpochMs"
            )
            val hasRecentNode = recentNodes.isNotEmpty()
            if (shouldRequestRangeImmediately(previouslyHadRecentNode, hasRecentNode)) {
                val signalled = immediateRangeRequests.trySend(Unit).isSuccess
                Log.i(
                    HOURLY_ORDER_TICKER_TAG,
                    "state-3 recent cache is empty; immediate range request signalled=$signalled " +
                        "previous=$previouslyHadRecentNode"
                )
            } else if (
                hasRecentNode &&
                (previouslyHadRecentNode == null || previouslyHadRecentNode == false)
            ) {
                notifyOperator(
                    "预缓存不刷新：当前仍有${recentNodes.size}条有效状态3记录"
                )
            }
            previouslyHadRecentNode = hasRecentNode

            val missingNodes = snapshots.filter { it.cacheNode == null }
            val refillBatch = selectRefillBatch(
                nodes = missingNodes,
                startIndex = cacheRefillCursor,
                limit = CACHE_REFILL_BATCH_SIZE
            )
            cacheRefillCursor = refillBatch.nextIndex

            refillBatch.nodes.forEachIndexed { index, node ->
                try {
                    val result = tickerPostman.queryBreakfastRightForTicker(node.orderCode)
                    val cacheNode = if (result.completed && result.success) {
                        result.responseText?.let(::parseBreakfastRightCacheNode)
                    } else {
                        null
                    }
                    if (cacheNode != null && cacheNode.orderCode == node.orderCode) {
                        try {
                            // Pessimistic write: overwrite regardless of the value currently stored.
                            val updated = tickerWaitGroup.overwriteIgnoredCache(
                                nodeId = node.nodeId,
                                orderCode = node.orderCode,
                                cacheNode = cacheNode
                            )
                            Log.i(
                                HOURLY_ORDER_TICKER_TAG,
                                "state-3 cache refill orderCode=${node.orderCode} updated=$updated " +
                                    "response=${result.responseText}"
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            Log.w(
                                HOURLY_ORDER_TICKER_TAG,
                                "state-3 cache write failed orderCode=${node.orderCode}",
                                error
                            )
                        }
                    } else {
                        Log.w(
                            HOURLY_ORDER_TICKER_TAG,
                            "state-3 cache refill rejected orderCode=${node.orderCode} " +
                                "completed=${result.completed} success=${result.success} " +
                                "response=${result.responseText} message=${result.message}"
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.d(
                        HOURLY_ORDER_TICKER_TAG,
                        "cache refill will retry orderCode=${node.orderCode}: ${error.message}"
                    )
                }
                if (index < refillBatch.nodes.lastIndex) delay(CACHE_REFILL_REQUEST_GAP_MS)
            }
            // No 100ms full-ring polling. New range data wakes this worker; an hourly timeout is
            // only a low-cost recovery path for failed requests and cache expiration.
            withTimeoutOrNull(HOURLY_ORDER_TICK_MS) {
                cacheRefillRequests.receive()
            }
        }
    }
}

internal data class RefillBatch<T>(val nodes: List<T>, val nextIndex: Int)

internal fun <T> selectRefillBatch(nodes: List<T>, startIndex: Int, limit: Int): RefillBatch<T> {
    if (nodes.isEmpty() || limit <= 0) return RefillBatch(emptyList(), 0)
    val normalizedStart = Math.floorMod(startIndex, nodes.size)
    val count = minOf(limit, nodes.size)
    val selected = List(count) { offset -> nodes[(normalizedStart + offset) % nodes.size] }
    return RefillBatch(selected, (normalizedStart + count) % nodes.size)
}

internal fun isRecentIgnoredNode(createdAtEpochMs: Long, nowEpochMs: Long): Boolean {
    val ageMs = nowEpochMs - createdAtEpochMs
    return ageMs >= 0L && ageMs < HOURLY_ORDER_TICK_MS
}

internal fun orderRangeStartUtc(endUtcSeconds: Long): Long =
    endUtcSeconds - ORDER_RANGE_LOOKBACK_SECONDS

internal fun orderCodeBelongsToDate(orderCode: String, date: LocalDate): Boolean {
    val normalized = orderCode.trim()
    if (normalized.isEmpty()) return false
    val datePrefix = Regex.escape(date.format(ORDER_CODE_DATE_FORMATTER))
    // Weak validation by design: tolerate test prefixes and health-certificate suffixes such as
    // "-1". A record is considered current-day only when it contains YYMMDD plus a plausible
    // four-or-more-digit sequence; everything malformed is handled as a non-current-day record.
    return Regex("$datePrefix\\d{4,}").containsMatchIn(normalized)
}

internal fun shouldRequestRangeImmediately(
    previouslyHadRecentNode: Boolean?,
    hasRecentNode: Boolean
): Boolean = !hasRecentNode && previouslyHadRecentNode != false
