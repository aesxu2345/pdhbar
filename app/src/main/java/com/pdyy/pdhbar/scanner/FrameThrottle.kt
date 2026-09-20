package com.pdyy.pdhbar.scanner

/** Monotonic admission before image conversion/ML submission; rejected frames are closed by caller. */
internal class FrameThrottle(private val intervalMs: Long = 150L) {
    private var lastAccepted: Long? = null

    init { require(intervalMs >= 0L) }

    @Synchronized
    fun accept(nowMs: Long): Boolean {
        val last = lastAccepted
        if (last != null && nowMs >= last && nowMs - last < intervalMs) return false
        lastAccepted = nowMs
        return true
    }
}
