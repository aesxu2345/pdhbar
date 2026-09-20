package com.pdyy.pdhbar

import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.View
import java.util.concurrent.atomic.AtomicLong

/** Do not pass routes, card numbers, JS payloads or arbitrary exception messages. */
internal object HostBrowserDebug {
    private val sequence = AtomicLong()
    fun id(value: Any?): String = value?.let {
        "${it.javaClass.simpleName}@${Integer.toHexString(System.identityHashCode(it))}"
    } ?: "null"

    fun event(owner: Any?, event: String, view: View? = null, detail: String = "", stack: Boolean = false) {
        // Opt-in via Android log configuration. Do not allocate stack traces during normal use.
        if (!Log.isLoggable("BreakfastBrowserDebug", Log.DEBUG)) return
        try {
            val state = view?.let {
                " view=${id(it)} parent=${id(it.parent)} attached=${it.isAttachedToWindow}" +
                    " visibility=${it.visibility} window=${it.windowVisibility} shown=${it.isShown}"
            } ?: ""
            val origin = if (stack) Throwable().stackTrace.drop(1).take(16)
                .joinToString(" <- ", prefix = " caller=") else ""
            Log.i("[aar debug]", "[aar debug] source=HOST build=browser-debug-v1 seq=${sequence.incrementAndGet()}" +
                " pid=${Process.myPid()} tid=${Process.myTid()} t=${SystemClock.elapsedRealtime()}" +
                " owner=${id(owner)} event=$event$state $detail$origin")
        } catch (_: Exception) {
            // Diagnostics must not break the fallback path.
        }
    }
}
