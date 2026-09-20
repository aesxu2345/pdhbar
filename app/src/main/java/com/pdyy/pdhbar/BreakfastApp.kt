package com.pdyy.pdhbar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import com.pdyy.pdhbar.runtime.IPv4Address
import com.pdyy.pdhbar.runtime.MAX_CONFIGURED_CACHE_WORKERS
import com.pdyy.pdhbar.runtime.NetRoute
import com.pdyy.pdhbar.runtime.UiCommand
import com.pdyy.pdhbar.runtime.std
import com.pdyy.pdhbar.scanner.MLKitBarcodeAnalyzer
import com.uikit.insight.NewUIInsightPlay
import com.uikit.insight.OnCardNo
import com.uikit.insight.UIEventStruct
import com.uikit.insight.UIInsightCss
import com.uikit.insight.UIInsightPlayConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val ScannerLineColor = Color(0xFF0A653A)
private val ScannerIconLineColor = Color(0xFFF4FFF9)
private val FlashlightGreen = ScannerLineColor
private val GlassBlack = Color(0xD90A0B0F)
private const val APP_TAG = "BreakfastApp"
private const val ScannerGateTimeoutMs = 7_000L
private const val UiSettingsPrefsName = "breakfast_ui_settings"
private const val IdPhotoWidthPx = 358
private const val IdPhotoHeightPx = 441
private const val InsightPanelAddressKey = "insight_panel_address"
private const val DefaultInsightPanelAddress = "172.16.203.56"
private const val InsightFirstRouteKey = "insight_first_route"
private const val InsightSecondRouteKey = "insight_second_route"
private const val DefaultInsightRoute = "http://172.16.18.167:18082/ZbXiOJvl_u0yYmWK/BKheVq-FzHT3oXfIozIanpfrXFG9wJB7ts9WNw8Snvn5XoSj8Fi08ju1YfaH8fk1Zeuug9QKq5u7YbACoxHpV7KCiOPoDREU1MhXj_5bbHUX54-dDiyEaKFULdhQ8wMAaaASUfkQ3GdmKyHtOTUc6Nkv9gXXuYI3asu6NmtYdJ_O5hKypr9QV8Cmcaycud4voUdeszcJrHM6ViDFCGi5uFaFX4xfQ-GaxKzyvBz9qUIooQ_lAw9Zy7A8wW7NPpQ5c3aB9Ewc7WFPWcHj79NgeQv73bslsat1DhAtirGjXcMIfvTv8fjED6oWdmm95T9pq_TWa8wpz8BZH8dVK-bJOQ"
private const val DefaultInsightFirstRoute = "$DefaultInsightRoute/provider"

private fun defaultInsightFirstRoute(): String = DefaultInsightFirstRoute

private fun defaultInsightSecondRoute(): String = DefaultInsightRoute

private class BreakfastInsightConfig(
    insightPanelAddress: String,
    firstRouteUrl: String,
    secondRouteUrl: String
) : UIInsightPlayConfig {
    override val ip: String = insightPanelAddress.ifBlank { DefaultInsightPanelAddress }
    override val firstRoute: String = firstRouteUrl.ifBlank { defaultInsightFirstRoute() }
    override val secondRoute: String = secondRouteUrl.ifBlank { defaultInsightSecondRoute() }
    // Production invariant: signed-route verification cannot be disabled by settings or JSON.
    override val bypass: Boolean = false
}

private fun createUIKitInsight(
    config: BreakfastInsightConfig,
    css: UIInsightCss
): NewUIInsightPlay {
    check(!config.bypass) { "pdhbar requires UIKit signed-route verification" }
    val insight = UIKitInsightFactory.create(config, css)
    check(!insight.bypass) { "UIKit factory unexpectedly enabled route signature bypass" }
    Log.i(
        APP_TAG,
        "UIKit signed-route verification enabled"
    )
    return insight
}

private fun applyUIKitWaitGroupCorrection(
    insight: NewUIInsightPlay,
    waitGroupLength: Int
): Boolean {
    val value = waitGroupLength.coerceAtLeast(0)
    return runCatching {
        UIKitInsightFactory.updateScan2Fail(insight, value)
        val actualValue = UIKitInsightFactory.readScan2Fail(insight)
        check(actualValue == value) {
            "UIKit scan2fail value mismatch: expected=$value, actual=$actualValue"
        }
        Log.d(APP_TAG, "WaitGroup length delivered to UIKit scan2fail: $value")
    }.onFailure { error ->
        Log.w(APP_TAG, "Unable to deliver WaitGroup length to UIKit scan2fail: $value", error)
    }.isSuccess
}

private class UIKitInsightHostView(context: Context) : FrameLayout(context) {
    val mountState = InsightMountState()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        HostBrowserDebug.event(this, "container.attached", this, "children=$childCount")
    }

    override fun onDetachedFromWindow() {
        HostBrowserDebug.event(this, "container.detached", this, "children=$childCount", stack = true)
        super.onDetachedFromWindow()
    }
}

private fun renderUIKitInsight(
    insight: NewUIInsightPlay,
    host: UIKitInsightHostView,
    waitGroupLength: Int,
    onRendererGone: (crashed: Boolean) -> Unit
) {
    host.mountState.update(
        insight,
        waitGroupLength,
        mount = {
            HostBrowserDebug.event(insight, "mount.begin", host, "children=${host.childCount}", stack = true)
            host.removeAllViews()
            val browser = insight.Display(host.context, host)
            HostBrowserDebug.event(insight, "mount.display.returned", browser)
            // Route renderer failures through the host's whole-instance recovery.
            browser.webViewClient = object : WebViewClient() {
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    HostBrowserDebug.event(insight, "renderer.gone", view,
                        "crashed=${detail.didCrash()} priority=${detail.rendererPriorityAtExit()}")
                    Log.e(APP_TAG, "UIKit renderer gone: crashed=${detail.didCrash()}, priority=${detail.rendererPriorityAtExit()}")
                    // Detach first: the new AAR cleans up the dead renderer but only offers
                    // recovery/recreation when it still has a parent. Avoid two recovery owners.
                    try {
                        HostBrowserDebug.event(insight, "renderer.detach.for.host.recovery", view)
                        (view.parent as? ViewGroup)?.removeView(view)
                        insight.onRenderProcessGone(view, detail)
                        HostBrowserDebug.event(insight, "renderer.sdk.cleanup.returned", view)
                    } catch (error: Exception) {
                        HostBrowserDebug.event(insight, "renderer.cleanup.failed", detail = "type=${error.javaClass.simpleName}")
                        Log.e(APP_TAG, "UIKit renderer cleanup failed", error)
                    } finally {
                        HostBrowserDebug.event(insight, "renderer.host.fallback.begin")
                        onRendererGone(detail.didCrash())
                        HostBrowserDebug.event(insight, "renderer.host.fallback.returned")
                    }
                    return true
                }
            }
            HostBrowserDebug.event(insight, "mount.complete", browser)
        },
        correct = { applyUIKitWaitGroupCorrection(insight, it) }
    )
}

private data class UiJsonSettings(
    val scheme: String,
    val host: String,
    val port: Int,
    val insightPanelAddress: String,
    val insightFirstRoute: String,
    val insightSecondRoute: String,
    val indexedBoundedMinWorkers: Int?
)

private val OpenSourceLicenseText = """
浦医早餐权益审核

作者 / Contributor:
LuYuHeng <aesxu2345@outlook.com>

项目源码协议：MIT License

Copyright (c) 2026 LuYuHeng <aesxu2345@outlook.com>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

独立许可组件 / Separately Licensed Component

UIKitInsight / insight-board-kit
项目来源：https://github.com/aesxu2345/insight-board-kit

本应用内置的 UIKitInsight AAR 是带完整性校验的独立许可组件，不属于 pdhbar 的 MIT License 授权范围。
制品在构建时通过 SHA-256 清单验证。其使用须符合以下任一许可：
- PolyForm Free Trial License 1.0.0；或
- 在有效 UIKitInsight Commercial Integration License 证书及其约定授权范围内使用。

本声明不单独证明特定部署已经获得商业授权；商业使用资格以实际签发的有效证书、书面协议及其授权范围为准。
完整条款请参阅随应用源码提供的 uikit/LICENSE、uikit/COMMERCIAL-INTEGRATION-LICENSE.md 与 uikit/NOTICE。

Third-Party Notices

本项目使用以下开源组件。各组件版权归其原作者所有，并遵循其各自许可协议。

Android / Kotlin
- Android Gradle Plugin — Android Open Source Project / Google，Apache License 2.0
- Kotlin Gradle Plugin — JetBrains，Apache License 2.0
- Kotlinx Coroutines Android — JetBrains，Apache License 2.0
- Desugar JDK Libs — Android Open Source Project / Google，Apache License 2.0

AndroidX / Compose
- AndroidX Core KTX — Android Open Source Project / Google，Apache License 2.0
- AndroidX Activity Compose — Android Open Source Project / Google，Apache License 2.0
- AndroidX Lifecycle Runtime KTX — Android Open Source Project / Google，Apache License 2.0
- Jetpack Compose UI / Material3 / Tooling / Test — Android Open Source Project / Google，Apache License 2.0
- AndroidX AppCompat — Android Open Source Project / Google，Apache License 2.0
- AndroidX ConstraintLayout — Android Open Source Project / Google，Apache License 2.0
- AndroidX CameraX Camera2 / Lifecycle / View — Android Open Source Project / Google，Apache License 2.0

Google / Test
- Google Material Components for Android — Google，Apache License 2.0
- Google ML Kit Barcode Scanning — Google，Google ML Kit Terms / bundled notices
- JUnit — Eclipse Public License 1.0
- AndroidX Test JUnit / Espresso — Android Open Source Project / Google，Apache License 2.0

Research / Reference Thanks
- scannerX — maulikhirani，https://github.com/maulikhirani/scannerX。该仓库当前未声明 License，本项目仅作调研与致谢记录，不按该仓库代码授权进行再分发。
""".trimIndent()
private val DialogButtonColors
    @Composable get() = ButtonDefaults.buttonColors(
        containerColor = FlashlightGreen,
        contentColor = Color.White,
        disabledContainerColor = FlashlightGreen.copy(alpha = 0.35f),
        disabledContentColor = Color.White.copy(alpha = 0.65f)
    )
private val DialogTextButtonColors
    @Composable get() = ButtonDefaults.textButtonColors(
        contentColor = FlashlightGreen,
        disabledContentColor = FlashlightGreen.copy(alpha = 0.45f)
    )

private fun reportUiFailure(context: Context, message: String, error: Exception) {
    Log.e(APP_TAG, message, error)
    val notify = Runnable {
        try {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (toastError: Exception) {
            Log.e(APP_TAG, "Unable to show failure toast", toastError)
        }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) {
        notify.run()
    } else {
        try {
            Handler(Looper.getMainLooper()).post(notify)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (postError: Exception) {
            Log.e(APP_TAG, "Unable to post failure toast", postError)
        }
    }
}

private fun releaseScannerResources(vararg releases: () -> Unit) {
    var cancellation: CancellationException? = null
    releases.forEach { release ->
        try {
            release()
        } catch (cancelled: CancellationException) {
            if (cancellation == null) cancellation = cancelled
        } catch (error: Exception) {
            Log.w(APP_TAG, "Unable to release scanner resource", error)
        }
    }
    cancellation?.let { throw it }
}

private class ScannerGate<T>(
    context: Context,
    private val action: (T, ScannerGate<T>) -> Unit
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val lockedAt = AtomicLong(0L)
    private val cancelled = AtomicBoolean(false)

    fun submit(value: T, trace: ((String) -> Unit)? = null, isActive: () -> Boolean = { true }) {
        // The first decoded result owns the gate immediately. Do not wait for the
        // query coroutine or customer dialog to update Compose state. A stale lock
        // can be atomically replaced after seven seconds, so it cannot deadlock.
        try {
            if (cancelled.get()) {
                trace?.invoke("gate.reject.cancelled")
                return
            }
            if (!isActive()) {
                trace?.invoke("gate.reject.inactive")
                return
            }
            if (!tryAcquire()) {
                trace?.invoke("gate.reject.locked")
                return
            }
            trace?.invoke("gate.acquired")
            val acquiredAt = lockedAt.get()
            val posted = handler.post {
                try {
                    if (cancelled.get() || !isActive()) {
                        trace?.invoke("gate.reject.retired_before_dispatch")
                        lockedAt.compareAndSet(acquiredAt, 0L)
                        return@post
                    }
                    if (!appContext.isWifiConnected()) {
                        trace?.invoke("gate.reject.wifi_disconnected")
                        lockedAt.compareAndSet(acquiredAt, 0L)
                        Toast.makeText(appContext, "WiFi未连接", Toast.LENGTH_SHORT).show()
                        return@post
                    }
                    trace?.invoke("gate.dispatch")
                    action(value, this)
                } catch (cancelled: CancellationException) {
                    trace?.invoke("gate.dispatch.cancelled")
                    lockedAt.compareAndSet(acquiredAt, 0L)
                    throw cancelled
                } catch (error: Exception) {
                    trace?.invoke("gate.dispatch.failed")
                    releaseAfterFailure(error)
                }
            }
            if (!posted) {
                trace?.invoke("gate.post.rejected")
                lockedAt.compareAndSet(acquiredAt, 0L)
            }
        } catch (cancelled: CancellationException) {
            lockedAt.set(0L)
            throw cancelled
        } catch (error: Exception) {
            releaseAfterFailure(error)
        }
    }

    fun releaseAfterFailure(error: Exception) {
        setBlocked(false)
        reportUiFailure(appContext, "扫码处理失败，请重试", error)
    }

    fun setBlocked(blocked: Boolean) {
        if (!cancelled.get()) {
            if (blocked) {
                // Preserve the timestamp captured by submit(); later Compose dialog
                // state must not restart the seven-second lock window.
                val acquiredAt = lockTimestamp()
                if (lockedAt.compareAndSet(0L, acquiredAt)) scheduleUnlock(acquiredAt)
            } else {
                lockedAt.set(0L)
            }
        }
    }

    fun cancel() {
        cancelled.set(true)
        lockedAt.set(lockTimestamp())
        handler.removeCallbacksAndMessages(null)
    }

    private fun tryAcquire(): Boolean {
        while (true) {
            val previous = lockedAt.get()
            val now = lockTimestamp()
            if (previous != 0L && now - previous < ScannerGateTimeoutMs) return false
            if (lockedAt.compareAndSet(previous, now)) {
                scheduleUnlock(now)
                return true
            }
        }
    }

    private fun scheduleUnlock(acquiredAt: Long) {
        handler.postDelayed(
            { lockedAt.compareAndSet(acquiredAt, 0L) },
            ScannerGateTimeoutMs
        )
    }

    private fun lockTimestamp(): Long = SystemClock.elapsedRealtime().coerceAtLeast(1L)
}

private fun Context.isWifiConnected(): Boolean {
    val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return false
    val activeNetwork = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
}

private fun beepOnScan(scanPlayer: MediaPlayer) {
    try {
        if (scanPlayer.isPlaying) {
            scanPlayer.seekTo(0)
        } else {
            scanPlayer.start()
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(APP_TAG, "Unable to play scan feedback", error)
    }
}

private fun vibrateOnScan(context: Context) {
    try {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        } ?: return

        if (!vibrator.hasVibrator()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(70L, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(70L)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.w(APP_TAG, "Unable to vibrate on scan", error)
    }
}

private fun scanFeedback(context: Context, scanPlayer: MediaPlayer) {
    vibrateOnScan(context)
    beepOnScan(scanPlayer)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun exitApp(context: Context) {
    context.findActivity()?.finishAndRemoveTask()
    Handler(Looper.getMainLooper()).postDelayed({
        Process.killProcess(Process.myPid())
    }, 120L)
}

private fun readInsightPanelAddress(context: Context): String {
    return context.getSharedPreferences(UiSettingsPrefsName, Context.MODE_PRIVATE)
        .getString(InsightPanelAddressKey, DefaultInsightPanelAddress)
        ?.takeIf(String::isNotBlank)
        ?: DefaultInsightPanelAddress
}

private fun readInsightFirstRoute(context: Context): String {
    val fallback = defaultInsightFirstRoute()
    return context.getSharedPreferences(UiSettingsPrefsName, Context.MODE_PRIVATE)
        .getString(InsightFirstRouteKey, fallback)
        ?.takeIf(String::isNotBlank)
        ?: fallback
}

private fun readInsightSecondRoute(context: Context): String {
    val fallback = defaultInsightSecondRoute()
    return context.getSharedPreferences(UiSettingsPrefsName, Context.MODE_PRIVATE)
        .getString(InsightSecondRouteKey, fallback)
        ?.takeIf(String::isNotBlank)
        ?: fallback
}

private fun writeInsightPanelAddress(context: Context, address: String) {
    context.getSharedPreferences(UiSettingsPrefsName, Context.MODE_PRIVATE)
        .edit()
        .putString(InsightPanelAddressKey, address.ifBlank { DefaultInsightPanelAddress })
        .apply()
}

private fun writeInsightRoutes(context: Context, firstRoute: String, secondRoute: String) {
    context.getSharedPreferences(UiSettingsPrefsName, Context.MODE_PRIVATE)
        .edit()
    .putString(InsightFirstRouteKey, firstRoute.ifBlank { defaultInsightFirstRoute() })
    .putString(InsightSecondRouteKey, secondRoute.ifBlank { defaultInsightSecondRoute() })
        .apply()
}

private fun UiJsonSettings.toPrettyJson(): String {
    val root = JSONObject()
        .put("backendApi", JSONObject().put("scheme", scheme).put("host", host).put("port", port))
        .put("insightPanelAddress", insightPanelAddress)
        .put("insightRoutes", JSONObject().put("firstRoute", insightFirstRoute).put("secondRoute", insightSecondRoute))
    indexedBoundedMinWorkers?.let {
        root.put(
            "runtimeSharedConfig",
            JSONObject().put("indexedBoundedMinWorkers", it)
        )
    }
    return root.toString(2)
}

private fun parseUiJsonSettings(text: String): Result<UiJsonSettings> {
    return runCatching {
        val root = JSONObject(text)
        val backend = root.optJSONObject("backendApi") ?: throw JSONException("缺少 backendApi 对象")
        val scheme = backend.optString("scheme").trim().lowercase()
        val host = backend.optString("host").trim()
        val port = backend.optInt("port", -1)
        val insightPanelAddress = root.optString("insightPanelAddress").trim()
        val insightRoutes = root.optJSONObject("insightRoutes") ?: throw JSONException("缺少 insightRoutes 对象")
        val insightFirstRoute = insightRoutes.optString("firstRoute").trim()
        val insightSecondRoute = insightRoutes.optString("secondRoute").trim()
        val runtimeSharedConfig = root.optJSONObject("runtimeSharedConfig")
        val indexedBoundedMinWorkers = if (
            runtimeSharedConfig?.has("indexedBoundedMinWorkers") == true
        ) {
            runtimeSharedConfig.optInt("indexedBoundedMinWorkers", -1).also {
                require(it in 1..MAX_CONFIGURED_CACHE_WORKERS) {
                    "runtimeSharedConfig.indexedBoundedMinWorkers 必须在 1..$MAX_CONFIGURED_CACHE_WORKERS"
                }
            }
        } else null

        require(scheme == "http" || scheme == "https") { "backendApi.scheme 只能是 http 或 https" }
        require(host.isNotBlank()) { "backendApi.host 不能为空" }
        require(port in 1..65535) { "backendApi.port 必须在 1..65535" }
        require(insightPanelAddress.isNotBlank()) { "insightPanelAddress 不能为空" }
        require(insightFirstRoute.startsWith("http://") || insightFirstRoute.startsWith("https://")) { "insightRoutes.firstRoute 必须是 http/https URL" }
        require(insightSecondRoute.startsWith("http://") || insightSecondRoute.startsWith("https://")) { "insightRoutes.secondRoute 必须是 http/https URL" }

        UiJsonSettings(
            scheme = scheme,
            host = host,
            port = port,
            insightPanelAddress = insightPanelAddress,
            insightFirstRoute = insightFirstRoute,
            insightSecondRoute = insightSecondRoute,
            indexedBoundedMinWorkers = indexedBoundedMinWorkers
        )
    }
}

@Composable
fun BreakfastApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var customer by remember { mutableStateOf<UiCommand.ShowCustomer?>(null) }
    var manualInputOpen by remember { mutableStateOf(false) }
    var backendDialogOpen by remember { mutableStateOf(false) }
    var licenseDialogOpen by remember { mutableStateOf(false) }
    var exitConfirmOpen by remember { mutableStateOf(false) }
    var submittingBreakfast by remember { mutableStateOf(false) }
    var queuedBreakfast by remember { mutableStateOf<UiCommand.BreakfastStatusQueued?>(null) }
    var uiLayerVisible by remember { mutableStateOf(true) }
    var manualCode by remember { mutableStateOf("") }
    var scannerGunMode by remember { mutableStateOf(std.run().scannerGunMode) }
    var insightPanelAddress by remember { mutableStateOf(readInsightPanelAddress(context)) }
    var insightFirstRoute by remember { mutableStateOf(readInsightFirstRoute(context)) }
    var insightSecondRoute by remember { mutableStateOf(readInsightSecondRoute(context)) }
    val scanPlayer = remember(context) {
        try {
            MediaPlayer.create(context, R.raw.tingko)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportUiFailure(context, "扫码提示音初始化失败", error)
            null
        }
    }
    val scannerGate = remember(context, scope, scanPlayer) {
        ScannerGate<String>(context) { code, gate ->
            scope.launch {
                try {
                    std.run().barscanner.onScan(code)
                    scanPlayer?.let { scanFeedback(context, it) }
                } catch (cancelled: CancellationException) {
                    gate.setBlocked(false)
                    throw cancelled
                } catch (error: Exception) {
                    gate.releaseAfterFailure(error)
                }
            }
        }
    }
    LaunchedEffect(scannerGate, customer != null || submittingBreakfast || queuedBreakfast != null) {
        scannerGate.setBlocked(customer != null || submittingBreakfast || queuedBreakfast != null)
    }
    DisposableEffect(scannerGate, scanPlayer) {
        onDispose {
            releaseScannerResources({ scannerGate.cancel() }, { scanPlayer?.release() })
        }
    }

    LaunchedEffect(scannerGate) {
        val runtime = std.run()
        runtime.pipefall.commands.collect { command ->
            try {
                when (command) {
                    is UiCommand.Toast -> Toast.makeText(context, command.message, Toast.LENGTH_SHORT).show()
                    is UiCommand.SubmissionChanged -> {
                        submittingBreakfast = command.submitting
                        if (command.submitting) {
                            customer = null
                            manualInputOpen = false
                            backendDialogOpen = false
                            licenseDialogOpen = false
                        }
                    }
                    is UiCommand.BreakfastStatusQueued -> {
                        customer = null
                        queuedBreakfast = command
                    }
                    is UiCommand.ShowCustomer -> {
                        if (command.queryId == 0L || runtime.pipefall.isCurrentCustomerQuery(command.queryId)) {
                            customer = command
                        }
                    }
                    is UiCommand.CustomerQueryFailed -> {
                        if (runtime.pipefall.isCurrentCustomerQuery(command.queryId)) {
                            customer = null
                            scannerGate.setBlocked(false)
                            // Runtime already owns query failure cleanup; do not enqueue DialogCancel again.
                            Toast.makeText(context, command.message, Toast.LENGTH_LONG).show()
                        }
                    }
                    UiCommand.CloseDialog -> customer = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (command is UiCommand.ShowCustomer) {
                    customer = null
                    scannerGate.setBlocked(false)
                    try {
                        runtime.uifurge.onDialogCancelClick()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (cleanupError: Exception) {
                        Log.e(APP_TAG, "Unable to finish failed ShowCustomer command", cleanupError)
                    }
                }
                reportUiFailure(context, "界面处理失败，请重试", error)
            }
        }
    }

    LaunchedEffect(queuedBreakfast) {
        val queued = queuedBreakfast ?: return@LaunchedEffect
        delay(3_000L)
        if (queuedBreakfast == queued) queuedBreakfast = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050806))
            .navigationBarsPadding()
    ) {
        ScannerPreviewCard(
            modifier = Modifier.fillMaxSize(),
            scannerGunMode = scannerGunMode,
            cameraRequested = !uiLayerVisible && customer == null && !manualInputOpen &&
                !submittingBreakfast && queuedBreakfast == null,
            scanGate = scannerGate,
            onBackToInsight = { uiLayerVisible = true },
            onPencilClick = {
                std.run().uifurge.onPencilButtonClick()
                manualInputOpen = true
            }
        )
        InsightRecoveryHost(
            visible = uiLayerVisible,
            onReopen = { uiLayerVisible = true }
        ) { restartInsight ->
                UIKitInsightLayer(
                    modifier = Modifier.fillMaxSize(),
                    onManualInputClick = {
                        uiLayerVisible = false
                        manualInputOpen = true
                    },
                    onBackendTargetClick = { backendDialogOpen = true },
                    onCameraInfraredSwitch = {
                        val enabled = !scannerGunMode
                        scannerGunMode = enabled
                        std.run().setScannerGunMode(enabled)
                        std.save(context.applicationContext)
                        Log.d(APP_TAG, "scanner gun mode saved=$enabled, waiting app exit before hardware switch")
                        exitConfirmOpen = true
                    },
                    onLicenseClick = { licenseDialogOpen = true },
                    onOpenScanner = { uiLayerVisible = false },
                    onRestartInsight = restartInsight,
                    insightPanelAddress = insightPanelAddress,
                    insightFirstRoute = insightFirstRoute,
                    insightSecondRoute = insightSecondRoute,
                    onInsightCardNo = { cardNo, isActive, trace ->
                        when {
                            customer != null -> trace("ui.reject.customer_dialog")
                            submittingBreakfast -> trace("ui.reject.submitting")
                            queuedBreakfast != null -> trace("ui.reject.queued_confirmation")
                            else -> scannerGate.submit(cardNo, trace = trace, isActive = isActive)
                        }
                    }
                )
        }
        if (submittingBreakfast || queuedBreakfast != null) {
            BackHandler(enabled = true) {}
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.68f))
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                awaitPointerEvent().changes.forEach { it.consume() }
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (queuedBreakfast == null) {
                        CircularProgressIndicator(color = Color.White)
                        Text("正在加入后台队列…", color = Color.White, fontSize = 16.sp)
                    } else {
                        Text("✓", color = Color(0xFF7CFF9A), fontSize = 46.sp)
                        Text(queuedBreakfast?.message.orEmpty(), color = Color.White, fontSize = 18.sp)
                        Text("任务将在后台继续执行", color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp)
                    }
                }
            }
        }
    }

    BackHandler(enabled = !uiLayerVisible && !exitConfirmOpen) {
        uiLayerVisible = true
    }

    customer?.let { data ->
        CustomerDialog(
            customer = data,
            onDone = {
                std.run().uifurge.onApproveBreakfastClick(data.orderCode)
            },
            onCancelBreakfast = {
                std.run().uifurge.onCancelEnrollmentClick(data.orderCode)
            },
            onDismiss = {
                customer = null
                std.run().uifurge.onDialogCancelClick()
            }
        )
    }

    if (manualInputOpen) {
        ManualInputDialog(
            value = manualCode,
            onValueChange = { manualCode = it },
            onSubmit = {
                try {
                    std.run().uifurge.onScanInput(manualCode.trim())
                    manualInputOpen = false
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    scannerGate.releaseAfterFailure(error)
                }
            },
            onDismiss = { manualInputOpen = false }
        )
    }

    if (backendDialogOpen) {
        BackendTargetDialog(
            insightPanelAddress = insightPanelAddress,
            insightFirstRoute = insightFirstRoute,
            insightSecondRoute = insightSecondRoute,
            onDismiss = { backendDialogOpen = false },
            onConfirm = { settings ->
                val runtime = std.run()
                // “保存”本身就是一次提交触发器，不以配置内容是否发生变化为条件。
                val systemPipe = runtime.pipefall
                systemPipe.writeBackendTarget(
                    address = IPv4Address(settings.host),
                    route = NetRoute(scheme = settings.scheme, port = settings.port)
                )
                runtime.updateBackendTarget(systemPipe)
                runtime.setIndexedBoundedMinWorkers(settings.indexedBoundedMinWorkers)
                runtime.waitgroup.ageUnregistered(force = true)
                insightPanelAddress = settings.insightPanelAddress
                insightFirstRoute = settings.insightFirstRoute
                insightSecondRoute = settings.insightSecondRoute
                writeInsightPanelAddress(context, settings.insightPanelAddress)
                writeInsightRoutes(context, settings.insightFirstRoute, settings.insightSecondRoute)
                std.save(context.applicationContext)
                Toast.makeText(
                    context,
                    "设置已保存，已触发老化记录清理",
                    Toast.LENGTH_SHORT
                ).show()
                backendDialogOpen = false
            }
        )
    }

    if (licenseDialogOpen) {
        LicenseDialog(onDismiss = { licenseDialogOpen = false })
    }

    if (exitConfirmOpen) {
        BackHandler(enabled = true) {}
        AppExitConfirmDialog(
            onConfirm = {
                exitConfirmOpen = false
                exitApp(context)
            }
        )
    }
}

@Composable
internal fun InsightRecoveryHost(
    visible: Boolean = true,
    onReopen: () -> Unit = {},
    content: @Composable (onRestart: () -> Unit) -> Unit
) {
    var recovery by remember { mutableStateOf(InsightRecoveryState()) }
    var pendingGeneration by remember { mutableStateOf<Long?>(null) }
    val currentReopen by rememberUpdatedState(onReopen)
    DisposableEffect(Unit) {
        HostBrowserDebug.event(null, "recovery.host.enter")
        onDispose { HostBrowserDebug.event(null, "recovery.host.dispose", stack = true) }
    }
    LaunchedEffect(visible, recovery.generation, recovery.paused, pendingGeneration) {
        HostBrowserDebug.event(null, "recovery.host.state", detail =
            "visible=$visible generation=${recovery.generation} paused=${recovery.paused} pending=$pendingGeneration")
    }
    // Owned by the screen, not the failing UIKit subtree: navigation cannot cancel recovery.
    LaunchedEffect(pendingGeneration) {
        val failedGeneration = pendingGeneration ?: return@LaunchedEffect
        HostBrowserDebug.event(null, "recovery.scheduled", detail = "generation=$failedGeneration delayMs=1000")
        Log.i(APP_TAG, "UIKit recovery scheduled generation=$failedGeneration")
        try {
            delay(1_000L)
        } catch (cancelled: CancellationException) {
            HostBrowserDebug.event(null, "recovery.cancelled", detail = "generation=$failedGeneration")
            throw cancelled
        }
        recovery = recovery.restartAutomatically(failedGeneration, SystemClock.elapsedRealtime())
        pendingGeneration = null
        currentReopen()
        HostBrowserDebug.event(null, "recovery.reopen", detail = "generation=${recovery.generation} paused=${recovery.paused}")
        Log.i(APP_TAG, "UIKit recovery reopen generation=${recovery.generation} paused=${recovery.paused}")
    }
    if (pendingGeneration != null) {
        // Unmount/close the failed generation before creating its replacement.
        Text("正在重新打开UIKit…", color = Color.White)
        return
    }
    if (!visible) return
    val currentGeneration = recovery.generation
    if (recovery.paused) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color(0xFF050806)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("UIKit连续恢复失败，已暂停自动重开", color = Color.White)
                Text("请释放内存或检查显示组件后重试", color = Color.White)
                Button(onClick = { recovery = recovery.restartManually(currentGeneration) }) {
                    Text("重新打开UIKit")
                }
            }
        }
        return
    }
    key(currentGeneration) {
        content {
            HostBrowserDebug.event(null, "recovery.request", detail =
                "requested=$currentGeneration current=${recovery.generation} paused=${recovery.paused} pending=$pendingGeneration")
            if (currentGeneration == recovery.generation && !recovery.paused && pendingGeneration == null) {
                pendingGeneration = currentGeneration
            }
        }
    }
}

/** Owns one generation. Stop callbacks immediately, then destroy UIKit before interrupting its listener. */
private class UIKitInsightSession(val insight: NewUIInsightPlay, val active: AtomicBoolean) {
    val mainHandler = Handler(Looper.getMainLooper())
    val cardNoExecutor = Executors.newSingleThreadExecutor()
    private var released = false

    // Only fixed stage names and instance identity: never card numbers, URLs or exception messages.
    fun traceCardFlow(stage: String) {
        Log.i("BreakfastCardFlow", "session=${System.identityHashCode(insight)} stage=$stage active=${active.get()}")
    }

    fun close() {
        check(Looper.myLooper() == Looper.getMainLooper())
        HostBrowserDebug.event(insight, "session.close.begin", detail = "released=$released active=${active.get()}", stack = true)
        active.set(false)
        if (released) return
        released = true
        traceCardFlow("session.close")
        releaseScannerResources(
            { mainHandler.removeCallbacksAndMessages(null) },
            { insight.Destroy() },
            { cardNoExecutor.shutdownNow() }
        )
        HostBrowserDebug.event(insight, "session.close.end")
    }
}

@Composable
private fun UIKitInsightLayer(
    modifier: Modifier = Modifier,
    onManualInputClick: () -> Unit,
    onBackendTargetClick: () -> Unit,
    onCameraInfraredSwitch: () -> Unit,
    onLicenseClick: () -> Unit,
    onOpenScanner: () -> Unit,
    onRestartInsight: () -> Unit,
    insightPanelAddress: String,
    insightFirstRoute: String,
    insightSecondRoute: String,
    onInsightCardNo: (String, () -> Boolean, (String) -> Unit) -> Unit
) {
    val insightContext = LocalContext.current
    val active = remember(insightPanelAddress, insightFirstRoute, insightSecondRoute) { AtomicBoolean(true) }
    val currentOnInsightCardNo by rememberUpdatedState(onInsightCardNo)
    val currentManualInput by rememberUpdatedState(onManualInputClick)
    val currentBackendTarget by rememberUpdatedState(onBackendTargetClick)
    val currentCameraSwitch by rememberUpdatedState(onCameraInfraredSwitch)
    val currentLicense by rememberUpdatedState(onLicenseClick)
    val currentOpenScanner by rememberUpdatedState(onOpenScanner)
    val eventHandler = remember(active) {
        object : UIEventStruct() {
            override fun onManualBarcodeInput() {
                HostBrowserDebug.event(active, "ui.manual.input", detail = "active=${active.get()}")
                if (active.get()) currentManualInput()
            }

            override fun onConfigureBackendAddress() {
                if (active.get()) currentBackendTarget()
            }

            override fun onCameraInfraredSwitch() {
                if (active.get()) currentCameraSwitch()
            }

            override fun onOpenSourceLicenses() {
                if (active.get()) currentLicense()
            }

            override fun onScanRegistration() {
                HostBrowserDebug.event(active, "ui.scan.registration", detail = "active=${active.get()}")
                if (active.get()) currentOpenScanner()
            }

            override fun onSettings() {
                if (active.get()) currentBackendTarget()
            }

            override fun onOpenScanner() {
                HostBrowserDebug.event(active, "ui.open.scanner", detail = "active=${active.get()}")
                if (active.get()) currentOpenScanner()
            }
        }
    }

    val creation = remember(eventHandler, insightPanelAddress, insightFirstRoute, insightSecondRoute) {
        try {
            val insight = createUIKitInsight(
                BreakfastInsightConfig(insightPanelAddress, insightFirstRoute, insightSecondRoute),
                UIInsightCss(
                    "uikit_insight/index.html",
                    18f,
                    156f,
                    88f,
                    "#f7faf7",
                    "#18813b"
                )
            )
            HostBrowserDebug.event(insight, "session.created", detail = "active=${HostBrowserDebug.id(active)}")
            Result.success(UIKitInsightSession(insight, active))
        } catch (error: Exception) {
            active.set(false)
            HostBrowserDebug.event(active, "session.creation.failed", detail = "type=${error.javaClass.simpleName}")
            Log.e(APP_TAG, "UIKit initialization failed", error)
            Result.failure<UIKitInsightSession>(error)
        }
    }
    val session = creation.getOrNull()
    if (session == null) {
        InsightFailureFallback(
            "显示模块初始化失败（${creation.exceptionOrNull()?.javaClass?.simpleName}）",
            onRestartInsight
        )
        return
    }

    val insight = session.insight
    var displayFailureReason by remember(session) { mutableStateOf<String?>(null) }
    fun fail(reason: String) {
        HostBrowserDebug.event(insight, "layer.failure", detail = "active=${active.get()} reason=$reason")
        if (active.getAndSet(false)) {
            displayFailureReason = reason
            session.close()
        }
    }
    DisposableEffect(session) {
        HostBrowserDebug.event(insight, "layer.effect.enter", detail = "active=${active.get()}")
        // AndroidView can fail before this effect starts. Never register work on a retired instance.
        if (active.get()) {
            try {
                session.traceCardFlow("listener.register.begin")
                insight.OnClickUIEvent(eventHandler)
                insight.OnCardNo.enroll(
                    object : OnCardNo() {
                        override fun event(str: String) {
                            session.traceCardFlow("callback.received")
                            if (!active.get()) {
                                session.traceCardFlow("callback.reject.inactive")
                                return
                            }
                            if (str.isBlank()) {
                                session.traceCardFlow("callback.reject.blank")
                                return
                            }
                            val posted = session.mainHandler.post {
                                if (!active.get()) {
                                    session.traceCardFlow("callback.reject.retired_before_main")
                                    return@post
                                }
                                session.traceCardFlow("callback.main")
                                try {
                                    currentOnInsightCardNo(str, { active.get() }, session::traceCardFlow)
                                } catch (error: Exception) {
                                    session.traceCardFlow("callback.failed")
                                    reportUiFailure(insightContext, "扫码回调失败，请重试", error)
                                }
                            }
                            if (!posted) session.traceCardFlow("callback.post.rejected")
                        }
                    }
                )
                session.traceCardFlow("listener.register.done")
                session.cardNoExecutor.execute {
                    try {
                        session.traceCardFlow("listener.run.enter")
                        if (active.get()) insight.OnCardNo.run()
                    } catch (error: Exception) {
                        session.traceCardFlow("listener.run.failed")
                        // A plain executor thread, not a coroutine cancellation boundary.
                        if (active.get()) {
                            Log.e(APP_TAG, "UIKit card listener stopped unexpectedly", error)
                            session.mainHandler.post { fail("显示模块监听失败") }
                        }
                    } finally {
                        session.traceCardFlow("listener.run.exit")
                    }
                }
            } catch (error: Exception) {
                session.traceCardFlow("listener.register.failed")
                Log.e(APP_TAG, "UIKit listener registration failed", error)
                fail("显示模块监听初始化失败（${error.javaClass.simpleName}）")
            }
        }
        onDispose {
            HostBrowserDebug.event(insight, "layer.effect.dispose", stack = true)
            session.close()
        }
    }

    val waitGroup = remember(insight) { std.run().waitgroup }
    var waitGroupLength by remember(insight) { mutableStateOf(waitGroup.len()) }
    LaunchedEffect(insight) {
        waitGroup.lenUpdates.collect { emittedLength ->
            waitGroupLength = emittedLength.coerceAtLeast(0)
        }
    }

    val failureReason = displayFailureReason
    if (failureReason != null) {
        InsightFailureFallback(failureReason, onRestartInsight)
        return
    }
    key(insight) {
        AndroidView(
            modifier = modifier,
            factory = { viewContext ->
                UIKitInsightHostView(viewContext).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    // Mount in update so creation and subsequent data changes share one path.
                }
            },
            update = { host ->
                if (active.get()) {
                    try {
                        renderUIKitInsight(insight, host, waitGroupLength) { crashed ->
                            fail(
                                if (crashed) {
                                    "显示模块渲染进程崩溃"
                                } else {
                                    "显示模块渲染进程被终止"
                                }
                            )
                        }
                    } catch (error: Exception) {
                        HostBrowserDebug.event(insight, "mount.failed", host, "type=${error.javaClass.simpleName}")
                        Log.e(APP_TAG, "UIKit mount failed", error)
                        // Keep arbitrary exception messages (URLs or business data) out of Toasts.
                        fail("显示模块加载失败（${error.javaClass.simpleName}）")
                    }
                }
            }
        )
    }
}

/** Restart once after composition, never inline from AndroidView.update or a renderer callback. */
@Composable
internal fun InsightFailureFallback(reason: String, onRestartInsight: () -> Unit) {
    val appContext = LocalContext.current.applicationContext
    val currentRestartInsight by rememberUpdatedState(onRestartInsight)
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF050806)),
        contentAlignment = Alignment.Center
    ) {
        Text("正在重新打开UIKit…", color = Color.White)
    }
    LaunchedEffect(Unit) {
        try {
            Toast.makeText(appContext, "非关键性bug:$reason", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            Log.w(APP_TAG, "Unable to show UIKit fallback toast", error)
        }
        // Hand recovery to the screen immediately, before this failing subtree can disappear.
        // The host owns throttling, disposal, generation replacement and returning to UIKit.
        currentRestartInsight()
    }
}

@Composable
private fun ScannerPreviewCard(
    modifier: Modifier = Modifier,
    scannerGunMode: Boolean,
    cameraRequested: Boolean,
    scanGate: ScannerGate<String>,
    onBackToInsight: () -> Unit,
    onPencilClick: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    // A new token also recreates resources when pause/resume happens before the next frame.
    var resumeGeneration by remember(lifecycleOwner) { mutableStateOf(0L) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            foreground = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_RESUME) resumeGeneration++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val cameraActive = cameraRequested && foreground && !scannerGunMode
    val scannerGunFocusRequester = remember { FocusRequester() }
    var scannerGunText by remember { mutableStateOf("") }
    // Read the animated state only inside Canvas: no per-frame recomposition of camera/SDK UI.
    val scanLineAlpha = if (cameraActive) {
        rememberInfiniteTransition(label = "scan-line").animateFloat(
            initialValue = 0.28f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 780),
                repeatMode = RepeatMode.Reverse
            ),
            label = "scan-line-alpha"
        )
    } else remember { mutableStateOf(1f) }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var flashlightOn by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }

    // A permission dialog itself pauses/resumes the Activity. Do not request again on resume
    // after a denial; only a fresh entry into the camera screen may ask again.
    LaunchedEffect(cameraRequested, scannerGunMode) {
        try {
            if (cameraRequested && !scannerGunMode && !hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportUiFailure(context, "相机权限申请失败", error)
        }
    }

    val previewView = remember(context, cameraActive, hasCameraPermission, resumeGeneration) {
        if (cameraActive && hasCameraPermission) {
            PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        } else null
    }

    DisposableEffect(previewView, lifecycleOwner, scannerGunMode, scanGate) {
        val disposed = AtomicBoolean(false)
        val failureReported = AtomicBoolean(false)
        val errorHandler = Handler(Looper.getMainLooper())
        var provider: ProcessCameraProvider? = null
        var preview: Preview? = null
        var analysis: ImageAnalysis? = null
        var analyzer: MLKitBarcodeAnalyzer? = null
        var executor: ExecutorService? = null

        fun isActive(): Boolean = !disposed.get() && cameraActive &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

        fun releaseCamera() {
            val ownedProvider = provider
            val ownedPreview = preview
            val ownedAnalysis = analysis
            val ownedAnalyzer = analyzer
            val ownedExecutor = executor
            provider = null
            preview = null
            analysis = null
            analyzer = null
            executor = null
            camera = null
            flashlightOn = false
            releaseScannerResources(
                { errorHandler.removeCallbacksAndMessages(null) },
                { ownedAnalysis?.clearAnalyzer() },
                {
                    val useCases = listOfNotNull(ownedPreview, ownedAnalysis).toTypedArray()
                    if (useCases.isNotEmpty()) ownedProvider?.unbind(*useCases)
                },
                { ownedPreview?.setSurfaceProvider(null) },
                { ownedAnalyzer?.close() },
                // Let already-queued analyze calls close their frames after the analyzer is closed.
                { ownedExecutor?.shutdown() }
            )
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_DESTROY) {
                disposed.set(true)
                releaseCamera()
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        try {
            if (previewView != null && !scannerGunMode && isActive()) {
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener(
                    {
                        // Main executor serializes this listener with disposal/ON_DESTROY.
                        // A late provider must not create resources or bind an old owner.
                        if (isActive()) {
                            try {
                                val cameraProvider = future.get()
                                provider = cameraProvider
                                val ownedPreview = Preview.Builder().build()
                                preview = ownedPreview
                                ownedPreview.setSurfaceProvider(previewView.surfaceProvider)
                                val ownedAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                analysis = ownedAnalysis
                                val ownedExecutor = Executors.newSingleThreadExecutor()
                                executor = ownedExecutor
                                val ownedAnalyzer = MLKitBarcodeAnalyzer(
                                    onFailure = { error ->
                                        if (isActive() && failureReported.compareAndSet(false, true)) {
                                            errorHandler.post {
                                                try {
                                                    if (isActive()) scanGate.releaseAfterFailure(error)
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (callbackError: Exception) {
                                                    reportUiFailure(context, "扫码错误提示失败", callbackError)
                                                }
                                            }
                                        }
                                    },
                                    onScanned = { result -> scanGate.submit(result, isActive = ::isActive) }
                                )
                                analyzer = ownedAnalyzer
                                ownedAnalysis.setAnalyzer(ownedExecutor, ownedAnalyzer)
                                if (isActive()) {
                                    camera = cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        ownedPreview,
                                        ownedAnalysis
                                    )
                                } else {
                                    releaseCamera()
                                }
                            } catch (cancelled: CancellationException) {
                                releaseCamera()
                                throw cancelled
                            } catch (error: Exception) {
                                releaseCamera()
                                reportUiFailure(context, "相机启动失败，请重试", error)
                            }
                        }
                    },
                    ContextCompat.getMainExecutor(context)
                )
            }
        } catch (cancelled: CancellationException) {
            releaseCamera()
            throw cancelled
        } catch (error: Exception) {
            releaseCamera()
            reportUiFailure(context, "相机初始化失败，请重试", error)
        }

        onDispose {
            disposed.set(true)
            releaseScannerResources(
                { lifecycleOwner.lifecycle.removeObserver(lifecycleObserver) },
                { releaseCamera() }
            )
        }
    }

    DisposableEffect(context, lifecycleOwner, scannerGunMode, scanGate) {
        val active = AtomicBoolean(scannerGunMode)
        var detachCallback: (() -> Unit)? = null

        fun detachScannerGun() {
            val detach = detachCallback
            detachCallback = null
            detach?.invoke()
        }

        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                active.set(false)
                releaseScannerResources({ detachScannerGun() })
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        try {
            if (scannerGunMode && lifecycleOwner.lifecycle.currentState != Lifecycle.State.DESTROYED) {
                val runtime = std.run()
                detachCallback = { runtime.setScannerGunApiCallback(null) }
                runtime.attachScannerGunApi(context) { code ->
                    scanGate.submit(code) {
                        active.get() && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            active.set(false)
            releaseScannerResources({ detachScannerGun() })
            throw cancelled
        } catch (error: Exception) {
            active.set(false)
            releaseScannerResources({ detachScannerGun() })
            reportUiFailure(context, "扫码枪初始化失败，请重试", error)
        }
        onDispose {
            active.set(false)
            releaseScannerResources(
                { lifecycleOwner.lifecycle.removeObserver(lifecycleObserver) },
                { detachScannerGun() }
            )
        }
    }

    LaunchedEffect(scannerGunMode) {
        if (scannerGunMode) {
            try {
                scannerGunFocusRequester.requestFocus()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                reportUiFailure(context, "扫码枪输入聚焦失败", error)
            }
        }
    }

    LaunchedEffect(scannerGunMode, scannerGunText) {
        if (!scannerGunMode) return@LaunchedEffect
        val code = scannerGunText.trim('\r', '\n', ' ', '\t')
        if (code.isBlank()) return@LaunchedEffect
        delay(260)
        if (scannerGunText.trim('\r', '\n', ' ', '\t') == code) {
            scannerGunText = ""
            scanGate.submit(code)
        }
    }

    LaunchedEffect(flashlightOn, camera) {
        try {
            val activeCamera = camera ?: return@LaunchedEffect
            if (!activeCamera.cameraInfo.hasFlashUnit()) return@LaunchedEffect
            activeCamera.cameraControl.enableTorch(flashlightOn)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            reportUiFailure(context, "闪光灯控制失败", error)
        }
    }

    GlassCard(
        modifier = modifier,
        containerColor = GlassBlack,
        borderColor = Color.Transparent,
        shape = RoundedCornerShape(0.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (previewView != null) {
                key(previewView) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { previewView }
                    )
                }
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                val boxWidth = size.width * 0.8f
                val boxHeight = size.height * 0.36f
                val left = (size.width - boxWidth) / 2f
                val top = (size.height - boxHeight) / 2f
                drawRect(if (scannerGunMode) Color(0xCC5F6368) else Color(0x99000000), size = size)
                drawRoundRect(
                    color = Color.Transparent,
                    topLeft = Offset(left, top),
                    size = Size(boxWidth, boxHeight),
                    cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx()),
                    blendMode = BlendMode.Clear
                )
                drawRoundRect(
                    color = ScannerLineColor,
                    topLeft = Offset(left, top),
                    size = Size(boxWidth, boxHeight),
                    cornerRadius = CornerRadius(28.dp.toPx(), 28.dp.toPx()),
                    style = Stroke(width = 3.dp.toPx())
                )
                if (!scannerGunMode) {
                    val scanLineY = top + boxHeight / 2f
                    val scanLineStart = left - 22.dp.toPx()
                    val scanLineEnd = left + boxWidth + 22.dp.toPx()
                    drawLine(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFFFF2B2B).copy(alpha = scanLineAlpha.value * 0.55f),
                                Color(0xFFFFF1F1).copy(alpha = scanLineAlpha.value),
                                Color(0xFFFF2B2B).copy(alpha = scanLineAlpha.value * 0.55f),
                                Color.Transparent
                            ),
                            startX = scanLineStart,
                            endX = scanLineEnd
                        ),
                        start = Offset(scanLineStart, scanLineY),
                        end = Offset(scanLineEnd, scanLineY),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }

            if (scannerGunMode) {
                BasicTextField(
                    value = scannerGunText,
                    onValueChange = { value ->
                        val code = value.trim('\r', '\n', ' ', '\t')
                        if (value.contains('\n') || value.contains('\r')) {
                            scannerGunText = ""
                            if (code.isNotBlank()) scanGate.submit(code)
                        } else {
                            scannerGunText = value
                        }
                    },
                    textStyle = TextStyle(color = Color.Transparent),
                    modifier = Modifier
                        .size(1.dp)
                        .focusRequester(scannerGunFocusRequester)
                        .focusable()
                )
                Text(
                    text = "点击侧键扫描",
                    color = ScannerLineColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            InsightReturnBadge(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 12.dp),
                onClick = onBackToInsight
            )

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 44.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    PencilPillButton(onClick = onPencilClick) {
                        PencilIcon(modifier = Modifier.size(42.dp), color = ScannerIconLineColor)
                    }
                    RoundIconButton(onClick = {
                        flashlightOn = !flashlightOn
                        std.run().uifurge.onFlashlightButtonClick()
                    }, selected = flashlightOn) {
                        FlashlightIcon(modifier = Modifier.size(38.dp), color = ScannerIconLineColor)
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightReturnBadge(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(ScannerLineColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(modifier = Modifier.size(18.dp)) {
            val strokeWidth = 2.4.dp.toPx()
            drawLine(
                color = ScannerIconLineColor,
                start = Offset(size.width * 0.78f, size.height * 0.18f),
                end = Offset(size.width * 0.32f, size.height * 0.5f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = ScannerIconLineColor,
                start = Offset(size.width * 0.32f, size.height * 0.5f),
                end = Offset(size.width * 0.78f, size.height * 0.82f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }
        Text(
            text = "Insight",
            color = ScannerIconLineColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun PencilPillButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(112.dp)
            .height(60.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(if (active) Color(0xFF063F25) else FlashlightGreen)
            .clickable {
                scope.launch {
                    active = true
                    delay(48)
                    active = false
                }
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun RoundIconButton(onClick: () -> Unit, selected: Boolean = false, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(CircleShape)
            .background(if (active || selected) Color(0xFF063F25) else FlashlightGreen)
            .clickable {
                scope.launch {
                    active = true
                    delay(80)
                    active = false
                }
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun MenuBadge(modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(ScannerLineColor)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(24.dp)) {
            val strokeWidth = 3.dp.toPx()
            val lineColor = ScannerIconLineColor
            drawLine(lineColor, Offset(2.dp.toPx(), 5.dp.toPx()), Offset(size.width - 2.dp.toPx(), 5.dp.toPx()), strokeWidth = strokeWidth, cap = StrokeCap.Round)
            drawLine(lineColor, Offset(2.dp.toPx(), size.height / 2f), Offset(size.width - 2.dp.toPx(), size.height / 2f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
            drawLine(lineColor, Offset(2.dp.toPx(), size.height - 5.dp.toPx()), Offset(size.width - 2.dp.toPx(), size.height - 5.dp.toPx()), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun RightThanksDrawer(
    open: Boolean,
    onClose: () -> Unit,
    onBackendTargetClick: () -> Unit,
    onManualInputClick: () -> Unit,
    onLicenseClick: () -> Unit,
    scannerGunMode: Boolean,
    onScannerGunModeClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (open) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f))
                .clickable(onClick = onClose)
        )
    }
    AnimatedVisibility(
        visible = open,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
        modifier = modifier
    ) {
        GlassCard(
            modifier = Modifier
                .fillMaxHeight()
                .width(304.dp),
            containerColor = Color(0xF20D1210),
            borderColor = ScannerLineColor.copy(alpha = 0.5f),
            shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                DrawerDecoration()
                Text("菜单", color = ScannerLineColor, fontWeight = FontWeight.Bold, fontSize = 26.sp)
                DrawerMenuButton("手动输入条码", onManualInputClick)
                DrawerMenuButton("配置后端地址", onBackendTargetClick)
                DrawerMenuButton("扫码枪模式(${if (scannerGunMode) "开" else "关"})", onScannerGunModeClick)
                DrawerMenuButton("开放源代码许可", onLicenseClick)
            }
        }
    }
}

@Composable
private fun DrawerDecoration() {
    Box(
        modifier = Modifier
            .height(132.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(ScannerLineColor.copy(alpha = 0.18f))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(ScannerLineColor.copy(alpha = 0.72f), radius = size.width * 0.24f, center = Offset(size.width * 0.28f, size.height * 0.36f))
            drawCircle(ScannerLineColor.copy(alpha = 0.36f), radius = size.width * 0.36f, center = Offset(size.width * 0.86f, size.height * 0.18f))
            drawRoundRect(
                color = ScannerIconLineColor.copy(alpha = 0.82f),
                topLeft = Offset(size.width * 0.12f, size.height * 0.66f),
                size = Size(size.width * 0.72f, 4.dp.toPx()),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )
        }
    }
}

@Composable
private fun DrawerMenuItem(text: String) {
    Text(
        text = text,
        color = ScannerIconLineColor,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ScannerLineColor.copy(alpha = 0.16f))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    )
}

@Composable
private fun DrawerMenuButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(ScannerLineColor.copy(alpha = 0.28f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(text = text, color = ScannerIconLineColor, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PencilIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.075f
        val body = Path().apply {
            moveTo(size.width * 0.18f, size.height * 0.72f)
            lineTo(size.width * 0.60f, size.height * 0.30f)
            lineTo(size.width * 0.72f, size.height * 0.42f)
            lineTo(size.width * 0.30f, size.height * 0.84f)
            close()
        }
        drawPath(body, color = color, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
        drawLine(color, Offset(size.width * 0.57f, size.height * 0.25f), Offset(size.width * 0.77f, size.height * 0.45f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.64f, size.height * 0.18f), Offset(size.width * 0.84f, size.height * 0.38f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.58f, size.height * 0.26f), Offset(size.width * 0.65f, size.height * 0.19f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.77f, size.height * 0.45f), Offset(size.width * 0.84f, size.height * 0.38f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.18f, size.height * 0.72f), Offset(size.width * 0.12f, size.height * 0.90f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.12f, size.height * 0.90f), Offset(size.width * 0.30f, size.height * 0.84f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.23f, size.height * 0.66f), Offset(size.width * 0.36f, size.height * 0.79f), strokeWidth = strokeWidth * 0.85f, cap = StrokeCap.Round)
    }
}

@Composable
private fun FlashlightIcon(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier = modifier) {
        val strokeWidth = size.minDimension * 0.08f
        val centerX = size.width / 2f
        drawLine(color, Offset(centerX, size.height * 0.02f), Offset(centerX, size.height * 0.18f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.22f, size.height * 0.16f), Offset(size.width * 0.32f, size.height * 0.25f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.78f, size.height * 0.16f), Offset(size.width * 0.68f, size.height * 0.25f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.05f, size.height * 0.34f), Offset(size.width * 0.18f, size.height * 0.34f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawLine(color, Offset(size.width * 0.82f, size.height * 0.34f), Offset(size.width * 0.95f, size.height * 0.34f), strokeWidth = strokeWidth, cap = StrokeCap.Round)
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.25f, size.height * 0.30f),
            size = Size(size.width * 0.5f, size.height * 0.14f),
            cornerRadius = CornerRadius(size.width * 0.04f, size.width * 0.04f),
            style = Stroke(width = strokeWidth)
        )
        val cone = Path().apply {
            moveTo(size.width * 0.28f, size.height * 0.44f)
            lineTo(size.width * 0.72f, size.height * 0.44f)
            lineTo(size.width * 0.61f, size.height * 0.66f)
            lineTo(size.width * 0.39f, size.height * 0.66f)
            close()
        }
        drawPath(cone, color = color, style = Stroke(width = strokeWidth))
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.39f, size.height * 0.66f),
            size = Size(size.width * 0.22f, size.height * 0.30f),
            cornerRadius = CornerRadius(size.width * 0.06f, size.width * 0.06f),
            style = Stroke(width = strokeWidth)
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(size.width * 0.46f, size.height * 0.76f),
            size = Size(size.width * 0.08f, size.height * 0.12f),
            cornerRadius = CornerRadius(size.width * 0.04f, size.width * 0.04f),
            style = Stroke(width = strokeWidth * 0.8f)
        )
    }
}

@Composable
private fun ManualInputDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("手动录入") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                label = { Text("订单号 / order_code") }
            )
        },
        confirmButton = {
            Button(onClick = onSubmit, colors = DialogButtonColors) { Text("确认") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = DialogTextButtonColors) { Text("取消") }
        }
    )
}

@Composable
private fun BackendTargetDialog(
    insightPanelAddress: String,
    insightFirstRoute: String,
    insightSecondRoute: String,
    onDismiss: () -> Unit,
    onConfirm: (UiJsonSettings) -> Unit
) {
    val target = std.run().api.target
    var jsonText by remember {
        mutableStateOf(
            UiJsonSettings(
                scheme = target.scheme,
                host = target.host,
                port = target.port,
                insightPanelAddress = insightPanelAddress,
                insightFirstRoute = insightFirstRoute,
                insightSecondRoute = insightSecondRoute,
                indexedBoundedMinWorkers = std.run().indexedBoundedMinWorkers
            ).toPrettyJson()
        )
    }
    val parsedSettings = remember(jsonText) { parseUiJsonSettings(jsonText) }
    val errorMessage = parsedSettings.exceptionOrNull()?.message

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置后端 API") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = jsonText,
                    onValueChange = { jsonText = it },
                    minLines = 8,
                    maxLines = 14,
                    label = { Text("JSON 设置") },
                    textStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
                    isError = errorMessage != null
                )
                errorMessage?.let { Text(it, color = Color(0xFFD32F2F), fontSize = 12.sp) }
                Text("POST 固定：/query_breakfast_right")
                Text("GET 固定：/change_breakfast_status")
                Text("insightPanelAddress：UIKit Insight 面板地址")
                Text("insightRoutes.firstRoute：第一幕 WebProvider 签名 URL")
                Text("insightRoutes.secondRoute：第二幕 iframe 签名 URL")
                Text("runtimeSharedConfig.indexedBoundedMinWorkers：可选回填 Worker 下限；删除后仅按设备内存评估")
            }
        },
        confirmButton = {
            Button(
                enabled = parsedSettings.isSuccess,
                onClick = { parsedSettings.getOrNull()?.let(onConfirm) },
                colors = DialogButtonColors
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = DialogTextButtonColors) { Text("取消") }
        }
    )
}

@Composable
private fun LicenseDialog(onDismiss: () -> Unit) {
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        delay(800L)
        while (true) {
            scrollState.animateScrollTo(scrollState.maxValue, animationSpec = tween(durationMillis = 18_000))
            delay(1_200L)
            scrollState.scrollTo(0)
            delay(900L)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开放源代码许可") },
        text = {
            Box(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(scrollState)
            ) {
                Text(
                    text = OpenSourceLicenseText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = DialogButtonColors) { Text("知道了") }
        }
    )
}

@Composable
private fun AppExitConfirmDialog(
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("提示") },
        text = { Text("应用即将退出，请重新打开！") },
        confirmButton = {
            Button(onClick = onConfirm, colors = DialogButtonColors) { Text("确定") }
        }
    )
}

@Composable
private fun CustomerDialog(
    customer: UiCommand.ShowCustomer,
    onDone: () -> Unit,
    onCancelBreakfast: () -> Unit,
    onDismiss: () -> Unit
) {
    var backgroundApprovalAvailable by remember(customer.orderCode, customer.querying) {
        mutableStateOf(false)
    }
    LaunchedEffect(customer.orderCode, customer.querying) {
        if (customer.querying) {
            delay(3_000L)
            backgroundApprovalAvailable = true
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("客户早餐信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                CustomerPhoto(
                    orderCode = customer.orderCode,
                    avatarUrl = customer.avatarUrl,
                    querying = customer.querying
                )
                if (customer.preCached) {
                    Text(
                        text = "预缓存记录",
                        color = FlashlightGreen,
                        fontSize = 12.sp
                    )
                }
                Text("订单号：${customer.orderCode}")
                Text("姓名：${customer.name}")
                Text("套餐：${customer.packageName ?: "-"}")
                OptionalExamField(
                    label = "体检类型",
                    value = customer.examType,
                    querying = customer.querying
                )
                OptionalExamField(
                    label = "单位名称",
                    value = customer.organizationName,
                    querying = customer.querying
                )
                OptionalExamField(
                    label = "体检总价",
                    value = customer.examTotalPrice?.let { "¥$it" },
                    querying = customer.querying
                )
                Text("早餐状态：${if (customer.querying) "N/A 查询中" else if (customer.hasBreakfast) "已登记" else "未登记"}")
                Text("权益状态：${if (customer.querying) "N/A 查询中" else if (customer.allowBreakfast) "允许" else "不允许"}")
                customer.blockReason?.let { reason ->
                    Text(
                        text = "原因：$reason",
                        color = Color(0xFFD32F2F),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (customer.querying && backgroundApprovalAvailable) {
                    Text(
                        "时间过长？请试试后台静默审批",
                        color = FlashlightGreen,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = if (customer.querying) {
                    backgroundApprovalAvailable
                } else {
                    customer.allowBreakfast
                },
                onClick = onDone,
                colors = DialogButtonColors
            ) {
                Text(if (customer.querying) "后台审批" else "完成早餐")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (customer.querying || customer.allowBreakfast) {
                    TextButton(
                        enabled = true,
                        onClick = onCancelBreakfast,
                        colors = DialogTextButtonColors
                    ) { Text("取消登记") }
                }
                TextButton(onClick = onDismiss, colors = DialogTextButtonColors) { Text("关闭") }
            }
        }
    )
}

@Composable
private fun OptionalExamField(label: String, value: String?, querying: Boolean) {
    Row {
        Text("$label：")
        Text(
            text = value ?: if (querying) "N/A 查询中" else "记录缺失，请人工核对",
            color = if (value == null && !querying) FlashlightGreen else Color.Unspecified
        )
    }
}

@Composable
private fun CustomerPhoto(
    orderCode: String,
    avatarUrl: String?,
    querying: Boolean
) {
    Box(
        modifier = Modifier
            .width(98.dp)
            .aspectRatio(IdPhotoWidthPx.toFloat() / IdPhotoHeightPx)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFE4ECE7)),
        contentAlignment = Alignment.Center
    ) {
        when {
            querying -> PhotoPlaceholder("照片加载中")
            avatarUrl == null -> PhotoPlaceholder("暂无照片")
            else -> SubcomposeAsyncImage(
                model = avatarUrl,
                contentDescription = "客户证件照",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                loading = { PhotoPlaceholder("照片加载中") },
                error = { PhotoPlaceholder("暂无照片") },
                success = { SubcomposeAsyncImageContent() }
            )
        }
    }
}

@Composable
private fun PhotoPlaceholder(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = Color(0xFF52645A), fontSize = 12.sp)
    }
}

@Composable
private fun GlassCard(
    modifier: Modifier = Modifier,
    containerColor: Color,
    borderColor: Color,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            borderColor.copy(alpha = 0.16f),
                            containerColor,
                            Color.Black.copy(alpha = 0.18f)
                        )
                    )
                )
        ) {
            content()
        }
    }
}
