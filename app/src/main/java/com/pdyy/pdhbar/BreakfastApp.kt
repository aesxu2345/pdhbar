package com.pdyy.pdhbar

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.pdyy.pdhbar.runtime.IPv4Address
import com.pdyy.pdhbar.runtime.NetRoute
import com.pdyy.pdhbar.runtime.PipeFallIO
import com.pdyy.pdhbar.runtime.UiCommand
import com.pdyy.pdhbar.runtime.std
import com.pdyy.pdhbar.scanner.MLKitBarcodeAnalyzer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

private val ScannerLineColor = Color(0xFF0A653A)
private val ScannerIconLineColor = Color(0xFFF4FFF9)
private val FlashlightGreen = ScannerLineColor
private val GlassBlack = Color(0xD90A0B0F)
private const val ScanRepeatDelayMs = 6_000L
private const val APP_TAG = "BreakfastApp"
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

private class ScannerThrottle<T>(
    private val delayMs: Long,
    private val action: (T) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var previous = 0L

    @Synchronized
    fun submit(value: T) {
        val now = System.currentTimeMillis()
        if (now - previous < delayMs) {
            return
        }
        previous = now
        handler.post { action(value) }
    }

    @Synchronized
    fun cancel() {
    }
}

private fun beepOnScan(scanPlayer: MediaPlayer) {
    runCatching {
        if (scanPlayer.isPlaying) {
            scanPlayer.seekTo(0)
        } else {
            scanPlayer.start()
        }
    }
}

private fun vibrateOnScan(context: Context) {
    runCatching {
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

@Composable
fun BreakfastApp() {
    val context = LocalContext.current
    var customer by remember { mutableStateOf<UiCommand.ShowCustomer?>(null) }
    var drawerOpen by remember { mutableStateOf(false) }
    var manualInputOpen by remember { mutableStateOf(false) }
    var backendDialogOpen by remember { mutableStateOf(false) }
    var licenseDialogOpen by remember { mutableStateOf(false) }
    var exitConfirmOpen by remember { mutableStateOf(false) }
    var manualCode by remember { mutableStateOf("") }
    var scannerGunMode by remember { mutableStateOf(std.run().scannerGunMode) }

    LaunchedEffect(Unit) {
        std.run().pipefall.commands.collect { command ->
            when (command) {
                is UiCommand.Toast -> Toast.makeText(context, command.message, Toast.LENGTH_SHORT).show()
                is UiCommand.ShowCustomer -> customer = command
                UiCommand.CloseDialog -> customer = null
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF050806))
    ) {
        ScannerPreviewCard(
            modifier = Modifier.fillMaxSize(),
            scannerGunMode = scannerGunMode,
            onScanned = { code -> std.run().barscanner.onScan(code) },
            onPencilClick = {
                std.run().uifurge.onPencilButtonClick()
                manualInputOpen = true
            },
            onMenuClick = {
                std.run().uifurge.onMenuButtonClick()
                drawerOpen = true
            }
        )
        RightThanksDrawer(
            open = drawerOpen,
            onClose = { drawerOpen = false },
            onBackendTargetClick = { backendDialogOpen = true },
            onManualInputClick = { manualInputOpen = true },
            onLicenseClick = { licenseDialogOpen = true },
            scannerGunMode = scannerGunMode,
            onScannerGunModeClick = {
                val enabled = !scannerGunMode
                std.run().setScannerGunMode(enabled)
                std.save(context.applicationContext)
                Log.d(APP_TAG, "scanner gun mode saved=$enabled, waiting app exit before hardware switch")
                exitConfirmOpen = true
            },
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }

    customer?.let { data ->
        CustomerDialog(
            customer = data,
            onDone = { std.run().uifurge.onBreakfastDoneClick(data.orderCode) },
            onCancelBreakfast = { std.run().uifurge.onBreakfastCancelClick(data.orderCode) },
            onDismiss = { std.run().uifurge.onDialogCancelClick() }
        )
    }

    if (manualInputOpen) {
        ManualInputDialog(
            value = manualCode,
            onValueChange = { manualCode = it },
            onSubmit = {
                std.run().uifurge.onScanInput(manualCode.trim())
                manualInputOpen = false
            },
            onDismiss = { manualInputOpen = false }
        )
    }

    if (backendDialogOpen) {
        BackendTargetDialog(
            onDismiss = { backendDialogOpen = false },
            onConfirm = { scheme, host, port ->
                val runtime = std.run()
                val systemPipe = runtime.systemIO as? PipeFallIO
                if (systemPipe == null) {
                    Toast.makeText(context, "系统服务未连接，后端地址未保存", Toast.LENGTH_SHORT).show()
                    backendDialogOpen = false
                    return@BackendTargetDialog
                }
                systemPipe.writeBackendTarget(
                    address = IPv4Address(host),
                    route = NetRoute(scheme = scheme, port = port)
                )
                runtime.api.onupdate(systemPipe)
                std.save(context.applicationContext)
                Toast.makeText(context, "后端地址已保存", Toast.LENGTH_SHORT).show()
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
private fun ScannerPreviewCard(
    modifier: Modifier = Modifier,
    scannerGunMode: Boolean,
    onScanned: suspend (String) -> Unit,
    onPencilClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val scanPlayer = remember { MediaPlayer.create(context, R.raw.tingko) }
    val scannerGunFocusRequester = remember { FocusRequester() }
    var scannerGunText by remember { mutableStateOf("") }
    val scanThrottle = remember {
        ScannerThrottle<String>(ScanRepeatDelayMs) { result ->
            scope.launch {
                onScanned(result)
                scanFeedback(context, scanPlayer)
            }
        }
    }
    val scanLineTransition = rememberInfiniteTransition(label = "scan-line")
    val scanLineAlpha by scanLineTransition.animateFloat(
        initialValue = 0.28f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 780),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scan-line-alpha"
    )
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

    LaunchedEffect(scannerGunMode) {
        if (!scannerGunMode && !hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(scannerGunMode) {
        if (scannerGunMode) {
            flashlightOn = false
            if (camera == null) {
                std.run().attachScannerGunApi(context) { code -> scanThrottle.submit(code) }
                scannerGunFocusRequester.requestFocus()
            } else {
                camera = null
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener(
                    {
                        runCatching {
                            cameraProviderFuture.get().unbindAll()
                        }.onFailure {
                            Toast.makeText(context, "相机释放失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                        }
                        std.run().attachScannerGunApi(context) { code -> scanThrottle.submit(code) }
                        scannerGunFocusRequester.requestFocus()
                    },
                    ContextCompat.getMainExecutor(context)
                )
            }
        } else {
            std.run().setScannerGunApiCallback(null)
        }
    }

    LaunchedEffect(scannerGunMode, scannerGunText) {
        if (!scannerGunMode) return@LaunchedEffect
        val code = scannerGunText.trim('\r', '\n', ' ', '\t')
        if (code.isBlank()) return@LaunchedEffect
        delay(260)
        if (scannerGunText.trim('\r', '\n', ' ', '\t') == code) {
            scannerGunText = ""
            scanThrottle.submit(code)
        }
    }

    LaunchedEffect(flashlightOn, camera) {
        val activeCamera = camera ?: return@LaunchedEffect
        if (!activeCamera.cameraInfo.hasFlashUnit()) return@LaunchedEffect
        activeCamera.cameraControl.enableTorch(flashlightOn)
    }

    DisposableEffect(Unit) {
        onDispose {
            scanThrottle.cancel()
            cameraExecutor.shutdown()
            scanPlayer.release()
        }
    }

    GlassCard(
        modifier = modifier,
        containerColor = GlassBlack,
        borderColor = Color.Transparent,
        shape = RoundedCornerShape(0.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!scannerGunMode && hasCameraPermission) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        PreviewView(viewContext).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                    },
                    update = { previewView ->
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                        cameraProviderFuture.addListener(
                            {
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                    .also {
                                        it.setAnalyzer(
                                            cameraExecutor,
                                            MLKitBarcodeAnalyzer { result ->
                                                scanThrottle.submit(result)
                                            }
                                        )
                                    }

                                runCatching {
                                    cameraProvider.unbindAll()
                                    camera = cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        imageAnalysis
                                    )
                                }.onFailure {
                                    Toast.makeText(context, "相机启动失败：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                                }
                            },
                            ContextCompat.getMainExecutor(context)
                        )
                    }
                )
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
                                Color(0xFFFF2B2B).copy(alpha = scanLineAlpha * 0.55f),
                                Color(0xFFFFF1F1).copy(alpha = scanLineAlpha),
                                Color(0xFFFF2B2B).copy(alpha = scanLineAlpha * 0.55f),
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
                            if (code.isNotBlank()) scanThrottle.submit(code)
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

            MenuBadge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 34.dp, end = 22.dp),
                onClick = onMenuClick
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
                label = { Text("导检单条码 / order_code") }
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
    onDismiss: () -> Unit,
    onConfirm: (scheme: String, host: String, port: Int) -> Unit
) {
    val target = std.run().api.target
    var scheme by remember { mutableStateOf(target.scheme) }
    var host by remember { mutableStateOf(target.host) }
    var portText by remember { mutableStateOf(target.port.toString()) }
    val port = portText.toIntOrNull()
    val canSave = scheme.isNotBlank() && host.isNotBlank() && port != null && port in 1..65535

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("配置后端 API") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = scheme, onValueChange = { scheme = it.trim() }, singleLine = true, label = { Text("协议 / scheme") })
                OutlinedTextField(value = host, onValueChange = { host = it.trim() }, singleLine = true, label = { Text("IPv4 地址") })
                OutlinedTextField(value = portText, onValueChange = { portText = it.filter(Char::isDigit) }, singleLine = true, label = { Text("端口") })
                Text("POST 固定：/query_breakfast_right")
                Text("GET 固定：/change_breakfast_status")
            }
        },
        confirmButton = {
            Button(enabled = canSave, onClick = { onConfirm(scheme, host, port ?: target.port) }, colors = DialogButtonColors) { Text("保存") }
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("客户早餐信息") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("导检单：${customer.orderCode}")
                Text("姓名：${customer.name}")
                Text("套餐：${customer.packageName ?: "-"}")
                Text("早餐状态：${if (customer.querying) "N/A 查询中" else if (customer.hasBreakfast) "已登记" else "未登记"}")
                Text("权益状态：${if (customer.querying) "N/A 查询中" else if (customer.allowBreakfast) "允许" else "不允许"}")
                customer.blockReason?.let { reason ->
                    Text("原因：$reason", color = Color(0xFFD32F2F))
                }
            }
        },
        confirmButton = {
            Button(enabled = !customer.querying && customer.blockReason == null, onClick = onDone, colors = DialogButtonColors) { Text("完成早餐") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !customer.querying, onClick = onCancelBreakfast, colors = DialogTextButtonColors) { Text("取消登记") }
                TextButton(onClick = onDismiss, colors = DialogTextButtonColors) { Text("关闭") }
            }
        }
    )
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
