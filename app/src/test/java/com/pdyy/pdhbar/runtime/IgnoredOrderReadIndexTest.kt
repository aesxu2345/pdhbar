package com.pdyy.pdhbar.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** JVM-only tests: no Context, SharedPreferences, actor, coroutine scheduler or Android mocks. */
class IgnoredOrderReadIndexTest {
    private val now = 10_000L

    @Test
    fun readCompletesWhileWriterIsBlockedOnSimulatedDiskCommit() {
        val index = IgnoredOrderReadIndex()
        val snapshot = snapshot(1L)
        val diskEntered = CountDownLatch(1)
        val releaseDisk = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val writer = threads.submit {
                index.publish(snapshot)
                diskEntered.countDown()
                // Simulate the actor's synchronous commit after publishing its new snapshot.
                check(releaseDisk.await(5, TimeUnit.SECONDS))
            }
            assertTrue(diskEntered.await(5, TimeUnit.SECONDS))
            val reader = threads.submit(Callable {
                assertSame(snapshot, index.get("order", now))
                assertNull(index.get("missing", now))
                assertNull(index.get("order", now + IGNORED_CACHE_MAX_AGE_MS + 1L))
            })
            // A bounded read must finish BEFORE the disk gate is released (not just eventually).
            reader.get(1, TimeUnit.SECONDS)
            assertFalse(writer.isDone)
            assertEquals(1L, releaseDisk.count)
            releaseDisk.countDown()
            writer.get(5, TimeUnit.SECONDS)
        } finally {
            releaseDisk.countDown()
            threads.shutdownNow()
        }
    }

    @Test
    fun delayedRemovalAfterSameOrderReplacementKeepsNewSnapshot() {
        val index = IgnoredOrderReadIndex()
        val original = snapshot(1L)
        val replacement = snapshot(2L)
        val replacementPublished = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        index.publish(original)
        try {
            val delayedRemoval = threads.submit(Callable {
                check(replacementPublished.await(5, TimeUnit.SECONDS))
                index.remove(original.orderCode, original.nodeId, original.cacheIndex)
            })
            threads.submit {
                index.publish(replacement)
                replacementPublished.countDown()
            }.get(5, TimeUnit.SECONDS)
            assertFalse(delayedRemoval.get(5, TimeUnit.SECONDS))
            assertSame(replacement, index.get("order", now))
            assertTrue(index.remove("order", replacement.nodeId, replacement.cacheIndex))
            assertNull(index.get("order", now))
        } finally {
            replacementPublished.countDown()
            threads.shutdownNow()
        }
    }

    @Test
    fun removalRequiresBothNodeIdAndCacheIndex() {
        val index = IgnoredOrderReadIndex()
        val published = snapshot(7L)
        index.publish(published)

        assertFalse(index.remove("order", "different-node", 7L))
        assertFalse(index.remove("order", published.nodeId, 8L))
        assertFalse(index.remove("other-order", published.nodeId, 7L))
        assertSame(published, index.get("order", now))
    }

    @Test
    fun concurrentReadsAlwaysSeeOneCompleteOrderNodeIndexAndCacheIdentity() {
        val index = IgnoredOrderReadIndex()
        index.publish(snapshot(1L))
        index.publish(snapshot(1L, "other-order"))
        val threads = Executors.newFixedThreadPool(5)
        val start = CyclicBarrier(5)
        try {
            val writer = threads.submit {
                start.await(5, TimeUnit.SECONDS)
                for (generation in 2L..20_000L) {
                    index.publish(snapshot(generation))
                    index.publish(snapshot(generation, "other-order"))
                }
            }
            val readers = (1..4).map { readerId ->
                threads.submit {
                    start.await(5, TimeUnit.SECONDS)
                    repeat(20_000) { iteration ->
                        val order = if ((iteration + readerId) % 2 == 0) "order" else "other-order"
                        val observed = checkNotNull(index.get(order, now))
                        val cache = checkNotNull(observed.cacheNode)
                        assertEquals(order, observed.orderCode)
                        assertEquals("$order-node-${observed.cacheIndex}", observed.nodeId)
                        assertEquals(order, cache.orderCode)
                        assertEquals(observed.cacheIndex, cache.cacheIndex)
                        assertEquals("payload-${observed.cacheIndex}", cache.cardNo)
                        assertEquals(now, observed.createdAtEpochMs)
                        assertEquals(now, cache.cachedAtEpochMs)
                    }
                }
            }
            writer.get(10, TimeUnit.SECONDS)
            readers.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            threads.shutdownNow()
        }
    }

    @Test
    fun ttlBoundaryIsInclusiveAndExpiredReadDoesNotDeleteSnapshot() {
        val index = IgnoredOrderReadIndex()
        val published = snapshot(1L)
        index.publish(published)

        assertSame(published, index.get("order", now + IGNORED_CACHE_MAX_AGE_MS))
        assertNull(index.get("order", now + IGNORED_CACHE_MAX_AGE_MS + 1L))
        // Rewinding the supplied clock demonstrates that a read miss performed no deletion.
        assertSame(published, index.get("order", now))
        assertNull(index.get("missing", now))
        assertNull(index.get("", now))
    }

    @Test
    fun unfilledNodeExpiresAndRefillCannotExtendNodeLifetime() {
        val index = IgnoredOrderReadIndex()
        val unfilled = snapshot(1L).copy(cacheNode = null)
        index.publish(unfilled)
        assertSame(unfilled, index.get("order", now + IGNORED_CACHE_MAX_AGE_MS))
        assertNull(index.get("order", now + IGNORED_CACHE_MAX_AGE_MS + 1L))

        val refilled = unfilled.copy(cacheNode = snapshot(1L).cacheNode!!.copy(
            cachedAtEpochMs = now + IGNORED_CACHE_MAX_AGE_MS
        ))
        index.publish(refilled)
        assertNull(index.get("order", now + IGNORED_CACHE_MAX_AGE_MS + 1L))
    }

    @Test
    fun olderCacheExpiresEvenWhenNodeIsRecent() {
        val index = IgnoredOrderReadIndex()
        val published = snapshot(1L).copy(createdAtEpochMs = now + IGNORED_CACHE_MAX_AGE_MS)
        index.publish(published)
        assertSame(published, index.get("order", now + IGNORED_CACHE_MAX_AGE_MS))
        assertNull(index.get("order", now + IGNORED_CACHE_MAX_AGE_MS + 1L))
    }

    @Test
    fun clearingFailedRestoreAllowsLaterPublicationButClosingDoesNot() {
        val index = IgnoredOrderReadIndex()
        index.publish(snapshot(1L))
        index.clear()
        assertNull(index.get("order", now))
        val replacement = snapshot(2L)
        index.publish(replacement)
        assertSame(replacement, index.get("order", now))

        index.close()
        index.publish(snapshot(3L))
        assertNull(index.get("order", now))
        assertFalse(index.matches("order", replacement.nodeId, replacement.cacheIndex))
    }

    @Test
    fun closeRacingWithWriterCannotRepublishStoppedView() {
        val index = IgnoredOrderReadIndex()
        val start = CyclicBarrier(2)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val writer = threads.submit {
                start.await(5, TimeUnit.SECONDS)
                for (generation in 1L..20_000L) index.publish(snapshot(generation))
            }
            val closer = threads.submit {
                start.await(5, TimeUnit.SECONDS)
                index.close()
                repeat(20_000) { assertNull(index.get("order", now)) }
            }
            writer.get(10, TimeUnit.SECONDS)
            closer.get(10, TimeUnit.SECONDS)
            assertNull(index.get("order", now))
            // Also ensure no hidden entry survived close (get alone intentionally masks it).
            assertFalse(index.remove("order", "order-node-20000", 20_000L))
        } finally {
            threads.shutdownNow()
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun publicationRejectsMismatchedCacheOrder() {
        val published = snapshot(1L)
        IgnoredOrderReadIndex().publish(published.copy(
            cacheNode = published.cacheNode!!.copy(orderCode = "wrong-order")
        ))
    }

    @Test(expected = IllegalArgumentException::class)
    fun publicationRejectsMismatchedCacheIndex() {
        val published = snapshot(1L)
        IgnoredOrderReadIndex().publish(published.copy(
            cacheNode = published.cacheNode!!.copy(cacheIndex = 2L)
        ))
    }

    private fun snapshot(generation: Long, orderCode: String = "order") = IgnoredOrderSnapshot(
        nodeId = "$orderCode-node-$generation",
        orderCode = orderCode,
        cacheNode = BreakfastRightCacheNode(
            orderCode = orderCode,
            cachedAtEpochMs = now,
            cardNo = "payload-$generation",
            cacheIndex = generation
        ),
        createdAtEpochMs = now,
        cacheIndex = generation
    )
}
