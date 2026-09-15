package com.pdyy.pdhbar.scanner

import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one CameraX frame across synchronous setup and asynchronous completion. */
internal class ScannerFrame(
    private val closeImage: () -> Unit,
    private val onFailure: (Exception) -> Unit
) {
    private val closed = AtomicBoolean(false)

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            closeImage()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportFailure(error)
        }
    }

    fun protect(block: () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            try {
                close()
            } finally {
                throw cancelled
            }
        } catch (error: Exception) {
            try {
                reportFailure(error)
            } finally {
                close()
            }
        }
    }

    private fun reportFailure(error: Exception) {
        try {
            onFailure(error)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failure reporter is also an external callback; never let it kill
            // the CameraX executor or the main-thread ML Kit completion listener.
        }
    }
}
