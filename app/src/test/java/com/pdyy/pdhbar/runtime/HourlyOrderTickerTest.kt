package com.pdyy.pdhbar.runtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HourlyOrderTickerTest {
    private val hourMs = 60L * 60L * 1_000L
    private val now = 1_786_598_400_000L

    @Test
    fun nodeCreatedWithinHourIsRecent() {
        assertTrue(isRecentIgnoredNode(now - hourMs + 1L, now))
    }

    @Test
    fun nodeCreatedExactlyHourAgoIsNotRecent() {
        assertFalse(isRecentIgnoredNode(now - hourMs, now))
    }

    @Test
    fun futureNodeIsNotRecent() {
        assertFalse(isRecentIgnoredNode(now + 1L, now))
    }

    @Test
    fun firstEmptyScanRequestsRangeImmediately() {
        assertTrue(shouldRequestRangeImmediately(null, false))
    }

    @Test
    fun transitionToEmptyRequestsRangeImmediately() {
        assertTrue(shouldRequestRangeImmediately(true, false))
    }

    @Test
    fun continuouslyEmptyPoolDoesNotFloodRequests() {
        assertFalse(shouldRequestRangeImmediately(false, false))
    }

    @Test
    fun nonEmptyPoolDoesNotRequestRangeImmediately() {
        assertFalse(shouldRequestRangeImmediately(null, true))
        assertFalse(shouldRequestRangeImmediately(true, true))
    }

    @Test
    fun rangeRequestLooksBackEightHours() {
        val endUtcSeconds = 1_786_602_849L

        assertEquals(8L * 60L * 60L, endUtcSeconds - orderRangeStartUtc(endUtcSeconds))
    }

    @Test
    fun refillBatchLimitsWorkAndRotatesCursor() {
        val first = selectRefillBatch((0 until 20).toList(), startIndex = 0, limit = 12)
        val second = selectRefillBatch((0 until 20).toList(), startIndex = first.nextIndex, limit = 12)

        assertEquals((0 until 12).toList(), first.nodes)
        assertEquals(12, first.nextIndex)
        assertEquals((12 until 20).toList() + (0 until 4).toList(), second.nodes)
        assertEquals(4, second.nextIndex)
    }

    @Test
    fun refillBatchHandlesEmptyPoolWithoutWork() {
        val batch = selectRefillBatch(emptyList<String>(), startIndex = 100, limit = 12)

        assertTrue(batch.nodes.isEmpty())
        assertEquals(0, batch.nextIndex)
    }

    @Test
    fun orderCodeDatePrefixKeepsOnlyBusinessDate() {
        val date = LocalDate.of(2026, 8, 13)

        assertTrue(orderCodeBelongsToDate("2608130090", date))
        assertTrue(orderCodeBelongsToDate("2608130090-1", date))
        assertTrue(orderCodeBelongsToDate("TEST-2608130090", date))
        assertTrue(orderCodeBelongsToDate("测试2608130090-1", date))
        assertFalse(orderCodeBelongsToDate("2608129999", date))
        assertFalse(orderCodeBelongsToDate("TEST-2608129999-1", date))
        assertFalse(orderCodeBelongsToDate("260813", date))
        assertFalse(orderCodeBelongsToDate("260813ABC", date))
        assertFalse(orderCodeBelongsToDate("invalid", date))
    }
}
