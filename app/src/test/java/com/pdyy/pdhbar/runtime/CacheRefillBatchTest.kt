package com.pdyy.pdhbar.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheRefillBatchTest {
    private fun node(index: Long, cached: Boolean = false) = IgnoredOrderSnapshot(
        "node-$index", "order-$index",
        if (cached) BreakfastRightCacheNode(orderCode = "order-$index", cachedAtEpochMs = 1L, cacheIndex = index) else null,
        1L, index
    )

    @Test
    fun visitsMoreThanCapacityOnceEvenWhenEveryRequestFails() {
        val snapshots = (1L..229L).reversed().map { node(it) }
        val visited = mutableListOf<Long>()
        var cursor = 0L
        while (true) {
            val batch = selectIndexedRefillBatch(snapshots, cursor, 64)
            if (batch.isEmpty()) break
            assertTrue(batch.size <= 64)
            visited += batch.map { it.cacheIndex }
            cursor = batch.last().cacheIndex
        }
        assertEquals((1L..229L).toList(), visited)
    }

    @Test
    fun skipsInvalidCachedAndAlreadyAttemptedIndexes() {
        val snapshots = listOf(node(0), node(-1), node(1), node(2, true), node(5), node(3))
        assertEquals(listOf(3L, 5L), selectIndexedRefillBatch(snapshots, 1, 64).map { it.cacheIndex })
        assertTrue(selectIndexedRefillBatch(snapshots, 0, 0).isEmpty())
    }
}