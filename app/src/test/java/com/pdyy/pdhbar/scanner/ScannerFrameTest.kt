package com.pdyy.pdhbar.scanner

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ScannerFrameTest {
    @Test
    fun successfulSetupKeepsImageOpenUntilCompletion() {
        var closes = 0
        val frame = ScannerFrame({ closes++ }, { fail("Unexpected failure: $it") })

        frame.protect { /* process() accepted the frame; completion is still pending. */ }
        assertEquals(0, closes)
        frame.protect { /* Success, empty results, failed or cancelled task completion. */ }
        frame.close()
        frame.close()

        assertEquals(1, closes)
    }

    @Test
    fun nullImageOrDisposedAnalyzerCanCloseImmediately() {
        var closes = 0
        val frame = ScannerFrame({ closes++ }, { fail("Unexpected failure: $it") })

        frame.protect { frame.close() }
        frame.close()

        assertEquals(1, closes)
    }

    @Test
    fun synchronousConversionOrProcessFailureClosesOnce() {
        for (stage in listOf("fromMediaImage", "process", "listener registration")) {
            var closes = 0
            val expected = IllegalStateException(stage)
            val failures = mutableListOf<Exception>()
            val frame = ScannerFrame({ closes++ }, { failures += it })

            frame.protect { throw expected }
            frame.close() // A late completion must not close the proxy twice.

            assertEquals(1, closes)
            assertEquals(listOf(expected), failures)
        }
    }

    @Test
    fun throwingSuccessCallbackCannotEscapeAndCompletionClosesOnce() {
        var closes = 0
        val expected = IllegalArgumentException("onScanned")
        val failures = mutableListOf<Exception>()
        val frame = ScannerFrame({ closes++ }, { failures += it })

        frame.protect { /* Synchronous ML Kit setup succeeds. */ }
        try {
            frame.protect { throw expected }
        } finally {
            frame.close()
        }

        assertEquals(1, closes)
        assertEquals(listOf(expected), failures)
    }

    @Test
    fun throwingErrorReporterDoesNotEscapeOrPreventClosing() {
        var closes = 0
        val frame = ScannerFrame({ closes++ }, { throw IllegalStateException("reporter") })

        frame.protect { throw IllegalArgumentException("processing") }
        frame.close()

        assertEquals(1, closes)
    }

    @Test
    fun cancellationIsRethrownUnchangedAfterClosing() {
        var closes = 0
        var reports = 0
        val expected = CancellationException("cancelled")
        val frame = ScannerFrame({ closes++ }, { reports++ })

        try {
            frame.protect { throw expected }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        } finally {
            frame.close()
        }

        assertEquals(1, closes)
        assertEquals(0, reports)
    }

    @Test
    fun cancellationFromReporterStillClosesAndPropagates() {
        var closes = 0
        val expected = CancellationException("reporter cancelled")
        val frame = ScannerFrame({ closes++ }, { throw expected })

        try {
            frame.protect { throw IllegalStateException("processing") }
            fail("Cancellation must propagate")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
        frame.close()

        assertEquals(1, closes)
    }

    @Test
    fun throwingCloseIsAttemptedOnlyOnce() {
        var closes = 0
        val expected = IllegalStateException("close")
        val failures = mutableListOf<Exception>()
        val frame = ScannerFrame({ closes++; throw expected }, { failures += it })

        frame.close()
        frame.close()

        assertEquals(1, closes)
        assertEquals(listOf(expected), failures)
    }

    @Test
    fun concurrentCleanupAndCompletionCloseExactlyOnce() {
        val closes = AtomicInteger()
        val frame = ScannerFrame({ closes.incrementAndGet() }, { fail("Unexpected failure: $it") })
        val executor = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        try {
            val calls = (1..16).map {
                executor.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    frame.close()
                }
            }
            start.countDown()
            calls.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, closes.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
