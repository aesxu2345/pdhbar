package com.pdyy.pdhbar.runtime

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class BlockingHttpRequestsTest {
    // Reused bounded executors, not a new pool/thread per test request.
    private companion object {
        val requests = BlockingHttpRequests(workerCount = 1, capacity = 2)
    }

    private fun CountDownLatch.awaitChecked() = check(await(5, TimeUnit.SECONDS))

    private suspend fun awaitIdle() {
        withTimeout(5_000) {
            while (requests.outstandingRequests != 0 || requests.pendingDeadlines != 0) delay(5)
        }
    }

    @Test fun cancellationBeforeSubmissionDoesNotRunOrScheduleWork() = runBlocking {
        val count = AtomicInteger()
        val job = Job().apply { cancel() }
        val call = async(job, start = CoroutineStart.UNDISPATCHED) {
            requests.execute(60_000) { count.incrementAndGet() }
        }
        try {
            call.await()
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(0, count.get())
        }
        awaitIdle()
    }

    @Test fun cancellationBeforeOwnershipClosesLateResourceExactlyOnce() = runBlocking {
        val entered = CountDownLatch(1)
        val register = CountDownLatch(1)
        val closes = AtomicInteger()
        val exited = CountDownLatch(1)
        val call = async {
            requests.execute(60_000) { resources ->
                try {
                    entered.countDown()
                    register.awaitChecked()
                    resources.own { closes.incrementAndGet() }
                    fail("Cancelled worker must not continue after ownership")
                } finally {
                    exited.countDown()
                }
            }
        }
        try {
            withTimeout(5_000) { while (entered.count > 0) delay(5) }
            call.cancel()
            yield()
            // Joining must NOT report success while uninterruptible work owns the slot.
            assertFalse(call.isCompleted)
            assertEquals(1, requests.outstandingRequests)
        } finally {
            register.countDown()
            withTimeout(1_000) { call.cancelAndJoin() }
        }
        assertEquals(0L, exited.count)
        assertEquals(1, closes.get())
        assertEquals(0, requests.outstandingRequests)
        assertEquals(0, requests.pendingDeadlines)
    }

    @Test fun deadlineBeforeOwnershipClosesLateResourceAndRemovesTimer() = runBlocking {
        val entered = CountDownLatch(1)
        val register = CountDownLatch(1)
        val closes = AtomicInteger()
        val call = async {
            runCatching {
                requests.execute(300) { resources ->
                    entered.countDown()
                    register.awaitChecked()
                    resources.own { closes.incrementAndGet() }
                }
            }.exceptionOrNull()
        }
        try {
            withTimeout(5_000) { while (entered.count > 0) delay(5) }
            withTimeout(2_000) { while (requests.pendingDeadlines != 0) delay(5) }
            yield()
            assertFalse(call.isCompleted)
            assertEquals(1, requests.outstandingRequests)
        } finally {
            register.countDown()
        }
        assertTrue(withTimeout(2_000) { call.await() } is SocketTimeoutException)
        assertEquals(1, closes.get())
        assertEquals(0, requests.outstandingRequests)
    }

    @Test fun successExceptionAndCancellationReleaseEachResourceOnlyOnce() = runBlocking {
        val closes = AtomicInteger()
        assertEquals(42, requests.execute(60_000) { resources ->
            resources.own { closes.incrementAndGet() }
            42
        })
        awaitIdle()
        assertEquals(1, closes.get())

        val error = runCatching {
            requests.execute<Unit>(60_000) { resources ->
                resources.own { closes.incrementAndGet(); throw IOException("close failed") }
                resources.own { closes.incrementAndGet() }
                throw IOException("read failed")
            }
        }.exceptionOrNull()
        assertTrue(error is IOException)
        awaitIdle()
        assertEquals(3, closes.get())

        val entered = CountDownLatch(1)
        val releasedByClose = CountDownLatch(1)
        val call = async {
            requests.execute<Unit>(60_000) { resources ->
                resources.own { closes.incrementAndGet(); releasedByClose.countDown() }
                entered.countDown()
                releasedByClose.awaitChecked()
                throw IOException("socket closed")
            }
        }
        withTimeout(5_000) { while (entered.count > 0) delay(5) }
        withTimeout(1_000) { call.cancelAndJoin() }
        awaitIdle()
        assertEquals(4, closes.get())
    }

    @Test fun queueDeadlineAndSaturationDoNotStartOrAccumulateWorkers() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queuedRuns = AtomicInteger()
        val first = async {
            requests.execute(60_000) {
                entered.countDown()
                release.awaitChecked()
            }
        }
        try {
            withTimeout(5_000) { while (entered.count > 0) delay(5) }
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { requests.execute(300) { queuedRuns.incrementAndGet() } }.exceptionOrNull()
            }
            assertEquals(2, requests.outstandingRequests)
            val rejected = runCatching { requests.execute(60_000) { queuedRuns.incrementAndGet() } }.exceptionOrNull()
            assertTrue(rejected is IOException)
            assertTrue(withTimeout(2_000) { second.await() } is SocketTimeoutException)
            repeat(50) {
                val queued = async(start = CoroutineStart.UNDISPATCHED) {
                    requests.execute(60_000) { queuedRuns.incrementAndGet() }
                }
                queued.cancelAndJoin()
            }
            assertEquals(0, queuedRuns.get())
            assertEquals(1, requests.outstandingRequests)
            assertEquals(1, requests.pendingDeadlines)
        } finally {
            release.countDown()
            first.cancelAndJoin()
        }
        awaitIdle()
    }

    @Test fun completionCancellationRaceDoesNotDoubleCloseOrRetainTimers() = runBlocking {
        repeat(50) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val closes = AtomicInteger()
            val call = launch {
                requests.execute(60_000) { resources ->
                    resources.own { closes.incrementAndGet() }
                    entered.countDown()
                    release.awaitChecked()
                }
            }
            withTimeout(5_000) { while (entered.count > 0) delay(5) }
            release.countDown()
            call.cancelAndJoin()
            awaitIdle()
            assertEquals(1, closes.get())
        }
    }

    @Test fun blockingCloseDoesNotBlockCallerOrTimerAndRetainsAdmission() = runBlocking {
        val enteredClose = CountDownLatch(1)
        val release = CountDownLatch(1)
        val enteredRead = CountDownLatch(1)
        val first = async {
            requests.execute(60_000) { resources ->
                resources.own { enteredClose.countDown(); release.awaitChecked() }
            }
        }
        try {
            withTimeout(5_000) { while (enteredClose.count > 0) delay(5) }
            assertFalse(first.isCompleted)
            val second = async {
                runCatching {
                    requests.execute(300) { resources ->
                        resources.own { release.awaitChecked() }
                        enteredRead.countDown()
                        release.awaitChecked()
                    }
                }.exceptionOrNull()
            }
            withTimeout(5_000) { while (enteredRead.count > 0) delay(5) }
            withTimeout(2_000) { while (requests.pendingDeadlines != 0) delay(5) }
            yield()
            assertFalse(second.isCompleted)
            assertEquals(2, requests.outstandingRequests)
            assertTrue(runCatching { requests.execute(60_000) { fail("must reject") } }.exceptionOrNull() is IOException)
            release.countDown()
            assertTrue(withTimeout(2_000) { second.await() } is SocketTimeoutException)
            withTimeout(2_000) { first.await() }
        } finally {
            release.countDown()
            first.cancelAndJoin()
        }
        assertEquals(0, requests.outstandingRequests)
        assertEquals(0, requests.pendingDeadlines)
    }

    @Test fun cancellationDuringSuccessfulCleanupWaitsAndDoesNotReturnSuccess() = runBlocking {
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = AtomicInteger()
        val closes = AtomicInteger()
        val call = launch {
            requests.execute(60_000) { resources ->
                resources.own {
                    closing.countDown()
                    release.awaitChecked()
                    closes.incrementAndGet()
                }
            }
            returned.incrementAndGet()
        }
        try {
            withTimeout(5_000) { while (closing.count > 0) delay(5) }
            call.cancel()
            yield()
            assertFalse(call.isCompleted)
            assertEquals(1, requests.outstandingRequests)
        } finally {
            release.countDown()
            withTimeout(1_000) { call.cancelAndJoin() }
        }
        assertEquals(1, closes.get())
        assertEquals(0, returned.get())
        assertEquals(0, requests.outstandingRequests)
    }

    @Test fun workerAndCleanupFatalErrorsPropagateUnchangedWithoutRetry() = runBlocking {
        for (fromClose in listOf(false, true)) {
            val fatal = OutOfMemoryError("synthetic; no memory pressure")
            val attempts = AtomicInteger()
            val closes = AtomicInteger()
            val error = runCatching {
                repeat(3) {
                    try {
                        requests.execute<Unit>(60_000) { resources ->
                            attempts.incrementAndGet()
                            resources.own {
                                closes.incrementAndGet()
                                if (fromClose) throw fatal
                            }
                            resources.own { closes.incrementAndGet() }
                            if (!fromClose) throw fatal
                        }
                    } catch (_: Exception) {
                        // Retryable failures only, matching the network callers.
                    }
                }
            }.exceptionOrNull()
            // Coroutine debug stack recovery can copy a Throwable while preserving its cause.
            assertTrue(error is OutOfMemoryError)
            assertTrue(generateSequence(error) { it.cause }.any { it === fatal })
            assertEquals(1, attempts.get())
            assertEquals(2, closes.get())
            assertEquals(0, requests.outstandingRequests)
        }
    }

    @Test fun fatalWorkerErrorAfterCancellationIsNotLostToCancelledContinuation() = runBlocking {
        supervisorScope {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val fatal = OutOfMemoryError("synthetic late worker failure")
            val call = async {
                requests.execute<Unit>(60_000) {
                    entered.countDown()
                    release.awaitChecked()
                    throw fatal
                }
            }
            try {
                withTimeout(5_000) { while (entered.count > 0) delay(5) }
                call.cancel()
            } finally {
                release.countDown()
            }
            val error = runCatching { call.await() }.exceptionOrNull()
            assertTrue(error is OutOfMemoryError)
            assertTrue(generateSequence(error) { it.cause }.any { it === fatal })
            assertEquals(0, requests.outstandingRequests)
        }
    }

    @Test fun threadsAreLazyAndAllPoolsRetireWhenIdle() = runBlocking {
        val elastic = BlockingHttpRequests(32, 32, idleTimeoutMs = 50)
        assertEquals(0, elastic.workerThreadCount)
        assertEquals(0, elastic.cleanupThreadCount)
        assertEquals(0, elastic.deadlineThreadCount)
        repeat(2) {
            assertEquals(42, elastic.execute(60_000) { resources ->
                resources.own { }
                assertEquals(1, elastic.workerThreadCount)
                42
            })
            withTimeout(5_000) {
                while (elastic.workerThreadCount != 0 || elastic.cleanupThreadCount != 0 ||
                    elastic.deadlineThreadCount != 0) delay(5)
            }
            assertEquals(0, elastic.outstandingRequests)
        }
    }

    @Test fun selfCancellationDuringResourceCreationIsNotRetried() = runBlocking {
        val attempts = AtomicInteger()
        val closes = AtomicInteger()
        val call = launch {
            val caller = currentCoroutineContext()[Job]!!
            repeat(5) {
                try {
                    requests.execute(60_000) { resources ->
                        attempts.incrementAndGet()
                        caller.cancel()
                        resources.own { closes.incrementAndGet() }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: IOException) {
                    // Same exception ordering as the production retry loops.
                }
            }
        }
        withTimeout(5_000) { call.join() }
        awaitIdle()
        assertTrue(call.isCancelled)
        assertEquals(1, attempts.get())
        assertEquals(1, closes.get())
    }
}
