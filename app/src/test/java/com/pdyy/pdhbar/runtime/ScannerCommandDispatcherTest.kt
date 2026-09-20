package com.pdyy.pdhbar.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class ScannerCommandDispatcherTest {
    @Test fun blockedVendorCallDoesNotBlockCallerOrAccumulateCommands() {
        val dispatcher = ScannerCommandDispatcher()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val failure = AtomicReference<Exception?>()
        val caller = Thread.currentThread()
        try {
            assertTrue(dispatcher.submit(
                action = {
                    assertNotSame(caller, Thread.currentThread())
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                },
                afterDelay = { stopped.countDown() },
                onFailure = { failure.set(it) }
            ))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(10_000) {
                assertFalse(dispatcher.submit(action = { fail("must not queue") }, onFailure = { failure.set(it) }))
            }
        } finally {
            release.countDown()
        }
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        assertNull(failure.get())
    }

    @Test fun delayedStopKeepsSingleOperationOwnership() {
        val dispatcher = ScannerCommandDispatcher()
        val stopped = CountDownLatch(1)
        val releaseStop = CountDownLatch(1)
        try {
            assertTrue(dispatcher.submit(
                action = {},
                afterDelay = { stopped.countDown(); check(releaseStop.await(5, TimeUnit.SECONDS)) },
                onFailure = { throw AssertionError(it) }
            ))
            assertTrue(stopped.await(2, TimeUnit.SECONDS))
            assertFalse(dispatcher.submit(action = { fail("stop is still active") }, onFailure = { throw AssertionError(it) }))
        } finally {
            releaseStop.countDown()
        }
    }
}
