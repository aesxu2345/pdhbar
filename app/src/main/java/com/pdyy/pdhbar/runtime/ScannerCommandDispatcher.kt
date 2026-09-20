package com.pdyy.pdhbar.runtime

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One bounded vendor operation, including its delayed stop. Never run Binder on the UI thread. */
internal class ScannerCommandDispatcher {
    private val busy = AtomicBoolean(false)
    private val executor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "scanner-service-commands").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun submit(
        action: () -> Unit,
        afterDelay: (() -> Unit)? = null,
        delayMs: Long = 50L,
        onFailure: (Exception) -> Unit
    ): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        try {
            executor.execute {
                var delayed = false
                try {
                    action()
                    if (afterDelay != null) {
                        executor.schedule({
                            try {
                                afterDelay()
                            } catch (error: Exception) {
                                onFailure(error)
                            } finally {
                                busy.set(false)
                            }
                        }, delayMs, TimeUnit.MILLISECONDS)
                        delayed = true
                    }
                } catch (error: Exception) {
                    onFailure(error)
                } finally {
                    if (!delayed) busy.set(false)
                }
            }
        } catch (error: Exception) {
            busy.set(false)
            onFailure(error)
            return false
        }
        return true
    }
}
