package com.pdyy.pdhbar.runtime

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Per-lane, bounded bridge for blocking network APIs. No CallerRunsPolicy: neither the caller
 * (possibly Main) nor the deadline thread may perform blocking I/O, including disconnect().
 * Admission is retained until BOTH the worker and all resource cleanup have finished, so a
 * platform DNS/disconnect that ignores cancellation cannot cause unbounded replacement work.
 * Cancellation/deadline requests an abort, not a hard join timeout: execute's non-cancellable
 * exit barrier can wait past the deadline for such platform calls. Never abandon a worker or
 * create a replacement pool to hide that wait. Normal Socket.close unblocks socket I/O.
 */
internal class BlockingHttpRequests(
    workerCount: Int = 2,
    private val capacity: Int = 4,
    name: String = "http",
    idleTimeoutMs: Long = 30_000
) {
    init {
        require(workerCount > 0 && capacity >= workerCount && idleTimeoutMs > 0)
    }

    private val admission = Semaphore(capacity)
    private val workers = pool(workerCount, capacity, "$name-io", idleTimeoutMs)
    // At most one cleanup task per admitted request is queued/running.
    private val cleanup = pool(2, capacity, "$name-close", idleTimeoutMs)
    private val deadlines = ScheduledThreadPoolExecutor(1, threads("$name-deadline")).apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
        setKeepAliveTime(idleTimeoutMs, TimeUnit.MILLISECONDS)
        allowCoreThreadTimeOut(true)
    }

    internal val outstandingRequests: Int get() = capacity - admission.availablePermits()
    internal val pendingDeadlines: Int get() = deadlines.queue.size
    internal val workerThreadCount: Int get() = workers.poolSize
    internal val cleanupThreadCount: Int get() = cleanup.poolSize
    internal val deadlineThreadCount: Int get() = deadlines.poolSize

    suspend fun <T> execute(timeoutMs: Long, block: (Resources) -> T): T {
        require(timeoutMs > 0)
        val startedAt = System.nanoTime()
        coroutineContext.ensureActive()
        var admitted: Call<T>? = null
        val result = try {
            suspendCancellableCoroutine<T> { continuation ->
                // Allocate bookkeeping before acquiring a permit (including in low memory).
                val call = Call(continuation, block)
                if (!admission.tryAcquire()) {
                    continuation.resumeWith(Result.failure(IOException("HTTP request capacity exceeded")))
                } else {
                    admitted = call
                    call.start(timeoutMs, startedAt)
                }
            }
        } finally {
            admitted?.let { call ->
                // A cancelled continuation alone says nothing about the old socket/worker.
                // This also prevents the next retry or engine replacement overlapping it.
                withContext(NonCancellable) {
                    call.finished.await()
                    call.rethrowFailure()
                }
            }
        }
        // Cancellation can arrive during successful completion's non-cancellable cleanup.
        coroutineContext.ensureActive()
        return result
    }

    internal interface Resources {
        /** Transfers ownership, even if cancellation/deadline happened before registration. */
        fun own(close: () -> Unit)
        fun checkActive()
    }

    private inner class Call<T>(
        private val continuation: CancellableContinuation<T>,
        private val block: (Resources) -> T
    ) : Resources {
        val finished = CompletableDeferred<Unit>()
        private val lock = Any()
        private var stopped = false
        private var submitted = false
        private var setupFinished = false
        private var workerFinished = false
        private var released = false
        private var cleaning = false
        private var terminalFailure: Throwable? = null
        private var deadline: ScheduledFuture<*>? = null
        private val resources = ArrayDeque<() -> Unit>(3)
        private val task = Runnable {
            try {
                if (synchronized(lock) { !stopped }) {
                    val result = try {
                        Result.success(block(this))
                    } catch (error: Throwable) {
                        // Fatal errors must reach execute's caller even if abort already won.
                        if (error !is Exception) rememberFailure(error)
                        Result.failure(error)
                    }
                    stop(result)
                }
            } finally {
                // If starting a cleanup thread failed, only the I/O worker may take over;
                // never fall back to blocking the caller or the deadline executor.
                val drain = synchronized(lock) {
                    if (!cleaning && resources.isNotEmpty()) {
                        cleaning = true
                        true
                    } else false
                }
                try {
                    if (drain) drainResources()
                } finally {
                    synchronized(lock) {
                        workerFinished = true
                        releaseIfFinished()
                    }
                }
            }
        }

        fun start(timeoutMs: Long, startedAt: Long) {
            try {
                // May run inline, before submission or resource registration.
                continuation.invokeOnCancellation { stop(null) }
                synchronized(lock) {
                    if (stopped) return
                }
                val elapsed = System.nanoTime() - startedAt
                val remaining = (TimeUnit.MILLISECONDS.toNanos(timeoutMs) - elapsed).coerceAtLeast(0L)
                val scheduled = deadlines.schedule(
                    { stop(Result.failure(SocketTimeoutException("HTTP request deadline exceeded"))) },
                    remaining,
                    TimeUnit.NANOSECONDS
                )
                synchronized(lock) {
                    // Timer/cancellation can fire before schedule() returns its handle.
                    if (stopped) scheduled.cancel(false) else {
                        deadline = scheduled
                        submitted = true
                        try {
                            workers.execute(task)
                        } catch (error: Throwable) {
                            submitted = false
                            throw error
                        }
                    }
                }
            } catch (error: Throwable) {
                if (error !is Exception) rememberFailure(error)
                stop(Result.failure(error))
            } finally {
                synchronized(lock) {
                    // Do not reuse admission before a late timer handle is removed.
                    setupFinished = true
                    releaseIfFinished()
                }
            }
        }

        override fun own(close: () -> Unit) {
            synchronized(lock) {
                // Current transports own at most a connection plus its two streams.
                resources.addLast(close)
                if (stopped) startCleanup()
            }
            checkActive()
        }

        override fun checkActive() {
            synchronized(lock) {
                if (stopped) throw IOException("HTTP request stopped")
            }
        }

        fun stop(result: Result<T>?) {
            synchronized(lock) {
                if (stopped) return
                stopped = true
                deadline?.cancel(false)
                deadline = null
                if (!submitted || workers.remove(task)) {
                    workerFinished = true
                }
                // A dequeued task may not have acquired lock yet. Its finally must acknowledge
                // exit; remove(false) is NOT evidence that the worker has already finished.
                startCleanup()
                releaseIfFinished()
            }
            // Cancellation uses null: never translate it into a retryable IOException.
            if (result != null) continuation.resumeWith(result)
        }

        /** Called under lock; only one drainer per request, also handling late ownership. */
        private fun startCleanup() {
            if (cleaning || resources.isEmpty()) return
            cleaning = true
            try {
                cleanup.execute { drainResources() }
            } catch (error: Throwable) {
                cleaning = false
                rememberFailure(error)
                // No executor shutdown and one drainer per admitted call: saturation cannot
                // reject this task. Thread-creation failures are rethrown after worker cleanup.
            }
        }

        private fun drainResources() {
            while (true) {
                val close = synchronized(lock) {
                    if (resources.isEmpty()) {
                        cleaning = false
                        releaseIfFinished()
                        null
                    } else resources.removeFirst()
                } ?: return
                try {
                    close()
                } catch (error: Throwable) {
                    // Ordinary close exceptions are best-effort; OOM/other Errors are not.
                    // Still close remaining owned resources, then rethrow on execute's caller.
                    if (error !is Exception) rememberFailure(error)
                }
            }
        }

        private fun rememberFailure(error: Throwable) = synchronized(lock) {
            if (terminalFailure == null || (terminalFailure is Exception && error !is Exception)) {
                terminalFailure = error
            }
        }

        fun rethrowFailure() {
            synchronized(lock) { terminalFailure }?.let { throw it }
        }

        private fun releaseIfFinished() {
            if (setupFinished && workerFinished && !cleaning && resources.isEmpty() && !released) {
                released = true
                admission.release()
                finished.complete(Unit)
            }
        }
    }

    private companion object {
        val sequence = AtomicLong()

        fun threads(prefix: String) = ThreadFactory { task ->
            Thread(task, "$prefix-${sequence.incrementAndGet()}").apply { isDaemon = true }
        }

        fun pool(size: Int, capacity: Int, name: String, idleTimeoutMs: Long) = ThreadPoolExecutor(
            size, size, idleTimeoutMs, TimeUnit.MILLISECONDS, ArrayBlockingQueue(capacity), threads(name),
            ThreadPoolExecutor.AbortPolicy()
        ).apply {
            // No prestart: threads are created on demand, and even core threads retire idle.
            allowCoreThreadTimeOut(true)
        }
    }
}
