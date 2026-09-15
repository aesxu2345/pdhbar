package com.pdyy.pdhbar.runtime

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CustomerQuerySessionsTest {
    @Test fun sameOrderRescanRejectsLateResultAndFailure() {
        val sessions = CustomerQuerySessions()
        val first = sessions.begin("order")
        sessions.clear()
        val second = sessions.begin("order")
        assertNotEquals(first.id, second.id)
        assertFalse(sessions.update(first.id) { it.copy(ignoredSourceNodeId = "stale") })
        assertFalse(sessions.fail(first.id))
        assertTrue(sessions.isCurrent(second.id))
        assertNull(sessions.current()!!.ignoredSourceNodeId)
        assertFalse(sessions.current()!!.failed)
    }

    @Test fun failureDropsTemporaryCacheAndAllowsNextScan() {
        val sessions = CustomerQuerySessions()
        val first = sessions.begin("order")
        assertTrue(sessions.update(first.id) {
            it.copy(cacheNode = BreakfastRightCacheNode("order", 1L), ignoredSourceNodeId = "node")
        })
        assertTrue(sessions.fail(first.id))
        assertNull(sessions.current()!!.cacheNode)
        assertNull(sessions.current()!!.ignoredSourceNodeId)
        assertFalse(sessions.update(first.id) { it.copy(failed = false) })
        assertFalse(sessions.fail(first.id))
        val second = sessions.begin("new-order")
        assertTrue(sessions.update(second.id) { it.copy(ignoredSourceNodeId = "new-node") })
        sessions.clear()
        assertNull(sessions.current())
    }

    @Test fun resultAlreadyBeingTransformedCannotReplaceNewSession() {
        val sessions = CustomerQuerySessions()
        val first = sessions.begin("order")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val stale = executor.submit<Boolean> {
                sessions.update(first.id) {
                    entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS))
                    it.copy(ignoredSourceNodeId = "stale")
                }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            val second = sessions.begin("order")
            release.countDown()
            assertFalse(stale.get(3, TimeUnit.SECONDS))
            assertEquals(second, sessions.current())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}