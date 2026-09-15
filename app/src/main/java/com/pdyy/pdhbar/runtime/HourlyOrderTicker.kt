package com.pdyy.pdhbar.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val HOURLY_ORDER_TICKER_TAG = "HourlyOrderTicker"
private const val HOURLY_ORDER_TICK_MS = 60L * 60L * 1_000L
private const val ORDER_RANGE_LOOKBACK_SECONDS = 8L * 60L * 60L
private const val TICKER_NETWORK_RETRY_COUNT = 1
private val ORDER_CODE_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd")

/**
 * Process-local ticker for inert state-3 records.
 *
 * Its NetTarget and Postman are deliberately separate from the interactive business Postman.
 * Ring mutations remain lock-free here and are serialized by WaitGroup's actor.
 */
class HourlyOrderTicker(
    context: Context,
    initialTickerNetTarget: NetTarget,
    private val tickerWaitGroup: WaitGroupIO,
    private val notifyOperator: (String) -> Unit = {},
    private val cacheRefillEngineKind: CacheRefillEngineKind = CacheRefillEngineKind.IndexedBounded,
    configuredMinWorkers: Int? = DEFAULT_INDEXED_BOUNDED_MIN_WORKERS
) {
    private val applicationContext = context.applicationContext
    private val tickerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tickerNetTarget = initialTickerNetTarget.copy(retry = TICKER_NETWORK_RETRY_COUNT)
    private val tickerPostman = Postman(tickerNetTarget)
    private val immediateRangeRequests = Channel<Unit>(Channel.CONFLATED)
    private var rangeTickerJob: Job? = null
    private var previouslyHadRecentNode: Boolean? = null
    private val engineLifecycle = Mutex()
    @Volatile
    private var configuredMinWorkers = configuredMinWorkers
    @Volatile
    private var cacheRefillEngine = createCacheRefillEngine()

    private fun createCacheRefillEngine() = CacheRefillEngineFactory.create(
        kind = cacheRefillEngineKind,
        context = applicationContext,
        configuredMinWorkers = configuredMinWorkers,
        waitGroup = tickerWaitGroup,
        query = tickerPostman::queryBreakfastRightForTicker,
        onObserved = ::onIgnoredPoolObserved
    )

    fun start() {
        Log.i(HOURLY_ORDER_TICKER_TAG, "start requested engine=$cacheRefillEngineKind minimumWorkers=$configuredMinWorkers")
        if (rangeTickerJob?.isActive != true) {
            rangeTickerJob = tickerScope.launch { runRangeTicker() }
        }
        tickerScope.launch {
            engineLifecycle.withLock { cacheRefillEngine.start() }
        }
    }

    fun updateBackendTarget(pipefall: PipeFallIO) {
        tickerPostman.onupdate(pipefall)
    }

    @Synchronized
    fun updateConfiguredMinWorkers(value: Int?) {
        if (configuredMinWorkers == value) return
        configuredMinWorkers = value
        tickerScope.launch {
            engineLifecycle.withLock {
                if (configuredMinWorkers != value) return@withLock
                Log.i(HOURLY_ORDER_TICKER_TAG, "replacing refill engine minimumWorkers=$value; waiting for old workers")
                cacheRefillEngine.stopAndJoin()
                Log.i(HOURLY_ORDER_TICKER_TAG, "old refill workers joined minimumWorkers=$value")
                if (configuredMinWorkers != value) return@withLock
                cacheRefillEngine = createCacheRefillEngine()
                cacheRefillEngine.start()
            }
        }
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
                if (insertedCount > 0) cacheRefillEngine.signal()
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

    private fun onIgnoredPoolObserved(observation: IgnoredPoolObservation) {
            val hasRecentNode = observation.recentCount > 0
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
                    "预缓存不刷新：当前仍有${observation.recentCount}条有效状态3记录"
                )
            }
            previouslyHadRecentNode = hasRecentNode
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
