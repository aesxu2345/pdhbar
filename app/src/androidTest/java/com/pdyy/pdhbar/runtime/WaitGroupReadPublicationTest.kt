package com.pdyy.pdhbar.runtime

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real Android JSON/Context with isolated in-memory preferences and deterministic disk gates. */
@RunWith(AndroidJUnit4::class)
class WaitGroupReadPublicationTest {
    @Test
    fun unrestoredBufferReadMissesWithoutOpeningPreferences() = runBlocking {
        val context = TestContext()
        val buffer = newBuffer(context)

        assertNull(buffer.findIgnoredOrder("order"))
        assertEquals(0, context.preferenceReads.get())
    }

    @Test
    fun bufferReadDoesNotWaitForInProgressRestore() {
        val context = TestContext()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        context.ring.beforeRead = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val buffer = newBuffer(context)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val restoring = threads.submit(Callable { runBlocking { buffer.insertIgnoredOrder("order") } })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val reading = threads.submit(Callable { runBlocking { buffer.findIgnoredOrder("order") } })
            assertNull(reading.get(1, TimeUnit.SECONDS))
            assertFalse(restoring.isDone)
            release.countDown()
            assertTrue(restoring.get(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            context.ring.beforeRead = null
            stopBuffer(buffer)
            threads.shutdownNow()
        }
    }

    @Test
    fun bufferMaintenanceReturnsOnMainWhileColdRestoreIsBlocked() = runBlocking {
        val operations = listOf<(WaitGroupBuffer) -> Unit>(
            { it.persist() },
            { it.ageUnregistered() },
            { it.ageUnregistered(force = true) }
        )
        for (operation in operations) {
            val context = TestContext()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            context.ring.beforeRead = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            val buffer = newBuffer(context)
            val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            try {
                // This completion must not depend on releasing the restore gate.
                withTimeout(1_000L) { mainScope.async { operation(buffer) }.await() }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertNull(buffer.findIgnoredOrder("order"))
                release.countDown()
                withTimeout(5_000L) { buffer.ignoredOrderSnapshots() }
            } finally {
                release.countDown()
                context.ring.beforeRead = null
                mainScope.cancel()
                stopBuffer(buffer)
            }
        }
    }

    @Test
    fun bufferMaintenanceDoesNotWaitForRestoreLockOrAccumulatePersistCoroutines() = runBlocking {
        val context = TestContext()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        context.ring.beforeRead = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val buffer = newBuffer(context)
        val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val restoring = async(Dispatchers.IO) { buffer.insertIgnoredOrder("order") }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            withTimeout(1_000L) {
                mainScope.async {
                    repeat(10_000) { buffer.persist() }
                    buffer.ageUnregistered()
                    buffer.ageUnregistered(force = true)
                }.await()
            }
            assertFalse(restoring.isCompleted)
            val scopeField = WaitGroupBuffer::class.java.getDeclaredField("scope")
            scopeField.isAccessible = true
            val bufferScope = scopeField.get(buffer) as CoroutineScope
            // One persist consumer and at most the two aging calls; no per-persist launches.
            assertTrue(bufferScope.coroutineContext[Job]!!.children.count() <= 3)
            release.countDown()
            assertTrue(withTimeout(5_000L) { restoring.await() })
        } finally {
            release.countDown()
            context.ring.beforeRead = null
            restoring.cancel()
            mainScope.cancel()
            stopBuffer(buffer)
        }
    }

    @Test
    fun bufferResolvingSuspendApisLeaveMainResponsiveDuringRestore() = runBlocking {
        val operations = listOf<suspend (WaitGroupBuffer) -> Unit>(
            { it.push("approval", ApprovalQueueAction.ApproveBreakfast) },
            { it.insertIgnoredOrder("order") },
            { it.insertIgnoredOrders(listOf("order")) },
            { it.ignoredOrderSnapshots() },
            { it.ignoredOrdersMissingCache() },
            { it.overwriteIgnoredCache("missing", "order", cache()) }
        )
        for (operation in operations) {
            val context = TestContext()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            context.ring.beforeRead = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            val buffer = newBuffer(context)
            val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            val pending = mainScope.async { operation(buffer) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                withTimeout(1_000L) {
                    mainScope.async { assertFalse(pending.isCompleted) }.await()
                }
                release.countDown()
                withTimeout(5_000L) { pending.await() }
            } finally {
                release.countDown()
                context.ring.beforeRead = null
                mainScope.cancel()
                stopBuffer(buffer)
            }
        }
    }

    @Test
    fun publishedInsertIsReadableDuringCommitAndShutdownFailsPendingReplies() {
        val context = TestContext()
        val queue = newQueue(context)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        context.ring.beforeCommit = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        try {
            val inserting = threads.submit(Callable {
                runBlocking { runCatching { queue.insertIgnoredOrder("order") } }
            })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val reading = threads.submit(Callable { runBlocking { queue.findIgnoredOrder("order") } })
            assertNotNull(reading.get(1, TimeUnit.SECONDS))
            assertFalse(inserting.isDone)

            runBlocking {
                // UNDISPATCHED guarantees each command is submitted before shutdown.
                val pending = listOf(
                    async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { queue.insertIgnoredOrders(listOf("another")) }
                    },
                    async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { queue.ignoredOrderSnapshots() }
                    },
                    async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { queue.ignoredOrdersMissingCache() }
                    },
                    async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { queue.overwriteIgnoredCache("missing", "order", cache()) }
                    },
                    async(start = CoroutineStart.UNDISPATCHED) {
                        runCatching { queue.push("approval", ApprovalQueueAction.ApproveBreakfast) }
                    }
                )
                queue.shutdown()
                // These replies must be complete while the actor is still at the disk gate.
                try {
                    withTimeout(1_000L) {
                        pending.forEach { assertTrue(it.await().isFailure) }
                    }
                } finally {
                    pending.forEach { it.cancel() }
                }
                assertNull(queue.findIgnoredOrder("order"))
            }
            assertTrue(inserting.get(1, TimeUnit.SECONDS).isFailure)
        } finally {
            context.ring.beforeCommit = null
            release.countDown()
            queue.shutdown()
            threads.shutdownNow()
        }
    }

    @Test
    fun commandExceptionsCompleteBothPushAndNonPushReplies() {
        val context = TestContext()
        val queue = newQueue(context)
        val threads = Executors.newSingleThreadExecutor()
        try {
            context.ring.beforeCommit = { throw IOException("simulated commit failure") }
            val failureChecks = threads.submit(Callable {
                runBlocking {
                    assertTrue(runCatching {
                        queue.push("approval", ApprovalQueueAction.ApproveBreakfast)
                    }.exceptionOrNull() is IOException)
                    assertTrue(runCatching { queue.insertIgnoredOrder("order") }
                        .exceptionOrNull() is IOException)
                    assertTrue(runCatching { queue.insertIgnoredOrders(listOf("another")) }
                        .exceptionOrNull() is IOException)
                    assertTrue(runCatching { queue.ignoredOrderSnapshots() }
                        .exceptionOrNull() is IOException)
                    assertTrue(runCatching { queue.ignoredOrdersMissingCache() }
                        .exceptionOrNull() is IOException)
                    val published = checkNotNull(queue.findIgnoredOrder("order"))
                    context.cache.beforeCommit = { throw IOException("simulated cache commit failure") }
                    assertTrue(runCatching {
                        queue.overwriteIgnoredCache(published.nodeId, "order", cache())
                    }.exceptionOrNull() is IOException)
                }
            })
            failureChecks.get(5, TimeUnit.SECONDS)
        } finally {
            context.ring.beforeCommit = null
            context.cache.beforeCommit = null
            queue.shutdown()
            threads.shutdownNow()
        }
    }

    @Test
    fun overwriteRejectsDifferentIndexAndExpiredNodesButAcceptsFreshZeroIndex() = runBlocking {
        val context = TestContext()
        val queue = newQueue(context)
        try {
            assertTrue(queue.insertIgnoredOrder("order"))
            val published = checkNotNull(queue.findIgnoredOrder("order"))
            assertFalse(queue.overwriteIgnoredCache(published.nodeId, "order", cache(published.cacheIndex + 1L)))
            assertFalse(queue.overwriteIgnoredCache("old-node", "order", cache(published.cacheIndex)))
            assertEquals(0, context.cache.commits.get())
            assertTrue(queue.overwriteIgnoredCache(published.nodeId, "order", cache()))
            val filled = checkNotNull(queue.findIgnoredOrder("order"))
            val filledCache = checkNotNull(filled.cacheNode)
            assertEquals(published.cacheIndex, filledCache.cacheIndex)
            assertEquals(published.orderCode, filledCache.orderCode)
            assertNull(published.cacheNode)
            assertTrue(queue.overwriteIgnoredCache(published.nodeId, "order", cache(published.cacheIndex)))
            assertFalse(queue.overwriteIgnoredCache(published.nodeId, "order", cache().copy(
                cachedAtEpochMs = System.currentTimeMillis() - IGNORED_CACHE_MAX_AGE_MS - 1L
            )))
        } finally {
            queue.shutdown()
        }

        val expiredContext = TestContext()
        expiredContext.seed(record("expired", "order", 9L, System.currentTimeMillis() - IGNORED_CACHE_MAX_AGE_MS - 1L))
        val expiredQueue = newQueue(expiredContext)
        try {
            assertNull(expiredQueue.findIgnoredOrder("order"))
            assertFalse(expiredQueue.overwriteIgnoredCache("expired", "order", cache(9L)))
            assertNull(expiredContext.cache.getString("order", null))
        } finally {
            expiredQueue.shutdown()
        }
    }

    @Test
    fun restorePublishesMatchingNodeAndCacheIdentity() = runBlocking {
        val context = TestContext()
        val now = System.currentTimeMillis()
        context.seed(record("restored", "order", 7L, now))
        context.cache.edit().putString("order", JSONObject().apply {
            put("order_code", "order")
            put("cached_at", now)
            put("cache_index", 7L)
            put("card_no", "restored-cache")
        }.toString()).commit()
        val queue = newQueue(context)
        try {
            val restored = checkNotNull(queue.findIgnoredOrder(" order "))
            val restoredCache = checkNotNull(restored.cacheNode)
            assertEquals("restored", restored.nodeId)
            assertEquals("order", restored.orderCode)
            assertEquals(7L, restored.cacheIndex)
            assertEquals("order", restoredCache.orderCode)
            assertEquals(7L, restoredCache.cacheIndex)
            assertEquals("restored-cache", restoredCache.cardNo)
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun replacementWithdrawsOldPublishedViewBeforeRestoringNewDelegate() {
        val context = TestContext()
        val buffer = newBuffer(context)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            runBlocking { assertTrue(buffer.insertIgnoredOrder("order")) }
            val delegateField = WaitGroupBuffer::class.java.getDeclaredField("delegate")
            delegateField.isAccessible = true
            val old = delegateField.get(buffer) as WaitGroup
            val replace = WaitGroupBuffer::class.java.getDeclaredMethod("replaceFailedWaitGroup", WaitGroup::class.java)
            replace.isAccessible = true
            context.ring.beforeRead = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            val replacing = threads.submit(Callable { replace.invoke(buffer, old) })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val reading = threads.submit(Callable {
                runBlocking {
                    assertNull(buffer.findIgnoredOrder("order"))
                    assertNull(old.findIgnoredOrder("order"))
                }
            })
            reading.get(1, TimeUnit.SECONDS)
            assertFalse(replacing.isDone)
            release.countDown()
            replacing.get(5, TimeUnit.SECONDS)
            runBlocking { assertNotNull(buffer.findIgnoredOrder("order")) }
        } finally {
            context.ring.beforeRead = null
            release.countDown()
            stopBuffer(buffer)
            threads.shutdownNow()
        }
    }

    @Test
    fun partialRestoreFailureClearsPreviouslyPublishedSnapshots() = runBlocking {
        val context = TestContext()
        context.seed(record("valid", "order", 1L, System.currentTimeMillis()), JSONObject())
        val queue = newQueue(context)
        try {
            assertNull(queue.findIgnoredOrder("order"))
            assertEquals(0, queue.len())
            assertTrue(queue.insertIgnoredOrder("new-order"))
            assertNotNull(queue.findIgnoredOrder("new-order"))
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun duplicateSingleAndBatchInsertStillPersistPrunedRing() = runBlocking {
        for (batch in listOf(false, true)) {
            val context = TestContext()
            val now = System.currentTimeMillis()
            context.seed(
                record("expired", "expired-order", 1L, now - IGNORED_CACHE_MAX_AGE_MS - 1L),
                record("valid", "order", 2L, now)
            )
            val queue = newQueue(context)
            try {
                if (batch) assertEquals(0, queue.insertIgnoredOrders(listOf("order")))
                else assertFalse(queue.insertIgnoredOrder("order"))
                val records = JSONObject(context.ring.getString("ring_snapshot", null)!!).getJSONArray("nodes")
                assertEquals(1, records.length())
                assertEquals("order", records.getJSONObject(0).getString("order_code"))
            } finally {
                queue.shutdown()
            }
        }
    }

    @Test
    fun expiryPrunesWholeOrInterleavedRingsWithOneCacheCommit() = runBlocking {
        for (keepSome in listOf(false, true)) {
            val context = TestContext()
            val now = System.currentTimeMillis()
            val expiredAt = now - IGNORED_CACHE_MAX_AGE_MS - 1_000L
            val count = 3_000
            val retainedOrders = mutableListOf<String>()
            val records = (0 until count).map { index ->
                val retained = keepSome && index % 3 == 1
                if (retained) retainedOrders += "order-$index"
                record("node-$index", "order-$index", index + 1L, if (retained) now else expiredAt)
            }
            context.seed(*records.toTypedArray())
            val editor = context.cache.edit()
            repeat(count) { index ->
                editor.putString("order-$index", JSONObject().apply {
                    put("order_code", "order-$index")
                    put("cached_at", now)
                    put("cache_index", index + 1L)
                }.toString())
            }
            editor.commit()
            context.cache.commits.set(0)
            val queue = newQueue(context)
            try {
                val snapshots = queue.ignoredOrderSnapshots()
                assertEquals(retainedOrders, snapshots.map { it.orderCode })
                assertEquals(retainedOrders.size, queue.len())
                assertEquals(1, context.cache.commits.get())
                val persisted = JSONObject(context.ring.getString("ring_snapshot", null)!!)
                assertEquals(10, persisted.getInt("schema"))
                assertEquals(1L + count - retainedOrders.size, persisted.getLong("revision"))
                assertEquals(count + 1L, persisted.getLong("next_cache_index"))
                val savedNodes = persisted.getJSONArray("nodes")
                assertEquals(retainedOrders, (0 until savedNodes.length()).map {
                    savedNodes.getJSONObject(it).getString("order_code")
                })
                repeat(count) { index ->
                    val retained = keepSome && index % 3 == 1
                    assertEquals(retained, queue.findIgnoredOrder("order-$index") != null)
                    assertEquals(retained, context.cache.getString("order-$index", null) != null)
                }
                assertEquals(retainedOrders, queue.ignoredOrderSnapshots().map { it.orderCode })
                assertEquals(1, context.cache.commits.get())
                // The empty-ring and wraparound links must remain usable for a subsequent insert.
                assertTrue(queue.insertIgnoredOrder("new-order"))
                assertEquals(count + 1L, checkNotNull(queue.findIgnoredOrder("new-order")).cacheIndex)
                assertEquals(retainedOrders.size + 1, queue.ignoredOrderSnapshots().size)
            } finally {
                queue.shutdown()
            }
        }
    }

    @Test
    fun expiryBatchPreservesSurvivingOrdersAndDifferentDiskGenerations() = runBlocking {
        val context = TestContext()
        val now = System.currentTimeMillis()
        val expiredAt = now - IGNORED_CACHE_MAX_AGE_MS - 1_000L
        context.seed(
            record("old", "shared", 1L, expiredAt),
            record("new", "shared", 2L, now),
            record("stale", "different-generation", 3L, expiredAt),
            record("duplicate-first", "duplicates", 4L, expiredAt),
            record("duplicate-last", "duplicates", 5L, expiredAt)
        )
        val editor = context.cache.edit()
        for ((order, index) in listOf("shared" to 2L, "different-generation" to 99L, "duplicates" to 4L)) {
            editor.putString(order, JSONObject().apply {
                put("order_code", order)
                put("cached_at", now)
                put("cache_index", index)
            }.toString())
        }
        editor.commit()
        context.cache.commits.set(0)
        val queue = newQueue(context)
        try {
            val remaining = queue.ignoredOrderSnapshots()
            assertEquals(listOf("new"), remaining.map { it.nodeId })
            assertEquals(2L, checkNotNull(queue.findIgnoredOrder("shared")).cacheNode?.cacheIndex)
            assertNull(queue.findIgnoredOrder("different-generation"))
            assertNull(queue.findIgnoredOrder("duplicates"))
            assertEquals(0, context.cache.commits.get())
            assertEquals(2L, JSONObject(context.cache.getString("shared", null)!!).getLong("cache_index"))
            assertEquals(99L, JSONObject(context.cache.getString("different-generation", null)!!).getLong("cache_index"))
            // As before, only the last removed node may delete a duplicate order's disk cache.
            assertEquals(4L, JSONObject(context.cache.getString("duplicates", null)!!).getLong("cache_index"))
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun expiryBatchWaitsForCommitWithoutBlockingPublishedReads() = runBlocking {
        val context = TestContext()
        val now = System.currentTimeMillis()
        context.seed(
            record("expired", "expired-order", 1L, now - IGNORED_CACHE_MAX_AGE_MS - 1_000L),
            record("valid", "order", 2L, now),
            record("approval", "approval-order", 0L, now - IGNORED_CACHE_MAX_AGE_MS - 1_000L).apply {
                put("task", WaitGroupTask.Enroll.value)
                put("approval_state", WaitApprovalState.Approved.value)
            }
        )
        val queue = newQueue(context)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        context.cache.beforeCommit = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val pruning = async(Dispatchers.IO) { queue.ignoredOrderSnapshots() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertFalse(pruning.isCompleted)
            assertNull(queue.findIgnoredOrder("expired-order"))
            assertNotNull(queue.findIgnoredOrder("order"))
            assertEquals(2, queue.len())
            release.countDown()
            assertEquals(listOf("order"), withTimeout(5_000L) { pruning.await() }.map { it.orderCode })
            val records = JSONObject(context.ring.getString("ring_snapshot", null)!!).getJSONArray("nodes")
            assertEquals(listOf("valid", "approval"), (0 until records.length()).map {
                records.getJSONObject(it).getString("id")
            })
            assertEquals(1, context.cache.commits.get())
        } finally {
            release.countDown()
            context.cache.beforeCommit = null
            pruning.cancel()
            queue.shutdown()
        }
    }

    @Test
    fun schemaNineMigrationCommitsThreeThousandCachesOnce() = runBlocking {
        val context = TestContext()
        val now = System.currentTimeMillis()
        val records = JSONArray()
        val cacheEditor = context.cache.edit()
        repeat(3000) { index ->
            records.put(record("node-$index", "order-$index", 0L, now))
            cacheEditor.putString("order-$index", JSONObject().apply {
                put("order_code", "order-$index")
                put("cached_at", now)
            }.toString())
        }
        cacheEditor.commit()
        context.cache.commits.set(0)
        context.ring.edit().putString("ring_snapshot", JSONObject().apply {
            put("schema", 9)
            put("nodes", records)
        }.toString()).commit()
        val queue = newQueue(context)
        try {
            assertEquals(3000, queue.len())
            assertEquals(1, context.cache.commits.get())
            repeat(3000) { index ->
                val restored = checkNotNull(queue.findIgnoredOrder("order-$index"))
                assertTrue(restored.cacheIndex > 0)
                assertEquals(restored.cacheIndex, restored.cacheNode?.cacheIndex)
            }
            assertEquals(10, JSONObject(context.ring.getString("ring_snapshot", null)!!).getInt("schema"))
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun schemaTenDoesNotRelabelCacheFromAnotherGeneration() = runBlocking {
        val context = TestContext()
        val now = System.currentTimeMillis()
        context.seed(record("node", "order", 8L, now))
        context.cache.edit().putString("order", JSONObject().apply {
            put("order_code", "order")
            put("cached_at", now)
            put("cache_index", 7L)
        }.toString()).commit()
        context.cache.commits.set(0)
        val queue = newQueue(context)
        try {
            assertNull(checkNotNull(queue.findIgnoredOrder("order")).cacheNode)
            assertEquals(0, context.cache.commits.get())
        } finally {
            queue.shutdown()
        }
    }

    @Test
    fun stoppedEngineWaitsForBlockingWorkersAndRejectsTheirLateResults() {
        val context = TestContext()
        val queue = newQueue(context)
        val entered = CountDownLatch(4)
        val release = CountDownLatch(1)
        val joinStarted = CountDownLatch(1)
        val threads = Executors.newSingleThreadExecutor()
        val engine = IndexedBoundedCacheRefillEngine(
            CacheRefillProfile(64, 4), queue,
            query = { order ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                WaitGroupExecutionResult(true, true,
                    JSONObject().put("order_code", order).toString())
            },
            onObserved = {}
        )
        try {
            runBlocking { queue.insertIgnoredOrders((0..7).map { "order-$it" }) }
            engine.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            engine.stop()
            val joining = threads.submit(Callable {
                runBlocking {
                    joinStarted.countDown()
                    engine.stopAndJoin()
                }
            })
            assertTrue(joinStarted.await(1, TimeUnit.SECONDS))
            assertFalse(joining.isDone)
            release.countDown()
            joining.get(5, TimeUnit.SECONDS)
            assertEquals(0, context.cache.commits.get())
            runBlocking {
                assertTrue(queue.ignoredOrderSnapshots().all { it.cacheNode == null })
            }
        } finally {
            release.countDown()
            runBlocking { engine.stopAndJoin() }
            queue.shutdown()
            threads.shutdownNow()
        }
    }

    @Test
    fun refillContinuesBeyondCapacityWithoutRetryingFailedHead() {
        val context = TestContext()
        val queue = newQueue(context)
        val visits = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        val finishedPass = CountDownLatch(1)
        val engine = IndexedBoundedCacheRefillEngine(
            CacheRefillProfile(3, 2), queue,
            query = { order ->
                visits.computeIfAbsent(order) { AtomicInteger() }.incrementAndGet()
                if (order == "order-0") WaitGroupExecutionResult(completed = false)
                else WaitGroupExecutionResult(true, true,
                    JSONObject().put("order_code", order).toString())
            },
            onObserved = {
                if (visits.size == 8 && it.missingCount == 1) finishedPass.countDown()
            }
        )
        try {
            runBlocking { queue.insertIgnoredOrders((0..7).map { "order-$it" }) }
            engine.start()
            assertTrue("Remaining batches must run without an external signal", finishedPass.await(10, TimeUnit.SECONDS))
            runBlocking { engine.stopAndJoin() }
            assertEquals(8, visits.size)
            assertTrue(visits.values.all { it.get() == 1 })
            assertEquals(7, context.cache.commits.get())
        } finally {
            runBlocking { engine.stopAndJoin() }
            queue.shutdown()
        }
    }

    private fun newQueue(context: Context) = WaitGroup(context,
        executor = { WaitGroupExecutionResult(completed = false) },
        agedUnregisteredExecutor = { WaitGroupExecutionResult(completed = false) }
    )

    private fun newBuffer(context: Context) = WaitGroupBuffer(context,
        executor = { WaitGroupExecutionResult(completed = false) },
        agedUnregisteredExecutor = { WaitGroupExecutionResult(completed = false) }
    )

    private fun stopBuffer(buffer: WaitGroupBuffer) {
        val field = WaitGroupBuffer::class.java.getDeclaredField("delegate")
        field.isAccessible = true
        val target = field.get(buffer) as? WaitGroup
        field.set(buffer, null)
        target?.shutdown()
        val scopeField = WaitGroupBuffer::class.java.getDeclaredField("scope")
        scopeField.isAccessible = true
        (scopeField.get(buffer) as CoroutineScope).cancel()
    }

    private fun cache(index: Long = 0L) = BreakfastRightCacheNode(
        orderCode = "order", cachedAtEpochMs = System.currentTimeMillis(), cacheIndex = index
    )

    private fun record(id: String, order: String, index: Long, createdAt: Long) = JSONObject().apply {
        put("id", id)
        put("order_code", order)
        put("task", WaitGroupTask.QueryBreakfastRight.value)
        put("approval_state", WaitApprovalState.Ignored.value)
        put("cache_index", index)
        put("created_at", createdAt)
    }

    private class TestContext : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
        val ring = MemoryPreferences()
        val cache = MemoryPreferences()
        val preferenceReads = AtomicInteger()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            preferenceReads.incrementAndGet()
            return if (name == "breakfast_wait_group") ring else cache
        }

        fun seed(vararg records: JSONObject) {
            val root = JSONObject().apply {
                put("schema", 10)
                put("revision", 1L)
                put("next_cache_index", 100L)
                put("nodes", JSONArray().apply { records.forEach { put(it) } })
            }
            ring.edit().putString("ring_snapshot", root.toString()).commit()
        }
    }

    /** Delegate unused primitive accessors to an isolated preference file; strings stay in memory. */
    private class MemoryPreferences(
        private val unused: SharedPreferences = InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("wait-group-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    ) : SharedPreferences by unused {
        private val strings = java.util.concurrent.ConcurrentHashMap<String, String>()
        val commits = AtomicInteger()
        @Volatile var beforeRead: (() -> Unit)? = null
        @Volatile var beforeCommit: (() -> Unit)? = null

        override fun getString(key: String?, defValue: String?): String? {
            beforeRead?.invoke()
            return key?.let(strings::get) ?: defValue
        }

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by unused.edit() {
            private val updates = mutableMapOf<String, String?>()
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                updates[checkNotNull(key)] = value
            }
            override fun remove(key: String?): SharedPreferences.Editor = apply {
                updates[checkNotNull(key)] = null
            }
            override fun commit(): Boolean {
                beforeCommit?.invoke()
                updates.forEach { (key, value) ->
                    if (value == null) strings.remove(key) else strings[key] = value
                }
                commits.incrementAndGet()
                return true
            }
        }
    }
}
