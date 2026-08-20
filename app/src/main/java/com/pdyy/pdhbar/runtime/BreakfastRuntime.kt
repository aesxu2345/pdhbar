package com.pdyy.pdhbar.runtime

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Binder
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.pdyy.pdhbar.MainActivity
import com.pdyy.pdhbar.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

private const val QUERY_BREAKFAST_RIGHT_PATH = "/query_breakfast_right"
private const val QUERY_ORDER_CODES_BY_TIME_RANGE_PATH = "/query_order_codes_by_time_range"
private const val CHANGE_BREAKFAST_STATUS_PATH = "/change_breakfast_status"
private const val UPLOAD_UNREGISTERED_SCAN_PATH = "/upload_unregistered_scan"
private const val API_TARGET_PREFS = "breakfast_api_target"
private const val API_TARGET_SCHEME = "scheme"
private const val API_TARGET_HOST = "host"
private const val API_TARGET_PORT = "port"
private const val RUNTIME_SHARED_PREFS = "breakfast_runtime_shared"
private const val SCANNER_GUN_MODE = "scanner_gun_mode"
private const val POST_RETRY_COUNT = 29
private const val POST_RETRY_DELAY_MS = 100L
private const val NETWORK_CONNECT_TIMEOUT_MS = 3_000L
private const val NETWORK_READ_TIMEOUT_MS = 8_000L
private const val SCAN_STATE_TIMEOUT_MS = 7_000L
private const val DEFAULT_API_SCHEME = "http"
private const val DEFAULT_API_HOST = "172.16.203.56"
private const val DEFAULT_API_PORT = 8088
private const val RUNTIME_TAG = "BreakfastRuntime"
private const val APPROVAL_NOTIFICATION_CHANNEL = "breakfast_approval_results"
private const val APPROVAL_NOTIFICATION_CHANNEL_NAME = "早餐审批结果"
const val SCAN_REPEAT_DELAY_MS = 1_200L
private const val SUNMI_SCANNER_SETTING_ACTION = "com.sunmi.scanner.ACTION_BAR_DEVICES_SETTING"
private const val SUNMI_SCANNER_SERIAL_SETTING_ACTION = "com.sunmi.scanner.ACTION_SCANNER_SERIAL_SETTING"
private const val SUNMI_SCANNER_SERIAL_OPEN_ACTION = "com.sunmi.scanner.serial_com4_open"
private const val SUNMI_SCANNER_RESULT_ACTION = "com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED"
private const val SUNMI_SCANNER_RESULT_DATA_KEY = "data"
private const val SUNMI_SCANNER_RESULT_BYTE_KEY = "source_byte"
private const val SUNMI_SCANNER_COMMAND_ACTION = "com.sunmi.scanner.Setting_cmd"
private const val SUNMI_SCANNER_SERVICE_ACTION = "com.sunmi.scanner.IScanInterface"
private const val SUNMI_SCANNER_SERVICE_PACKAGE = "com.sunmi.scanner"
private const val SUNMI_SCANNER_SERVICE_CLASS = "com.sunmi.scanner.ScannerService"

class std private constructor(
    val api: Postman,
    val hourlyOrderTicker: HourlyOrderTicker,
    val waitgroup: WaitGroupIO,
    val barscanner: BarScanner,
    val pipefall: PipeFallIO,
    systemIO: IBinder,
    val uifurge: UIfurge,
    private val scannerGunApi: ScannerGunApiBridge,
    scannerGunMode: Boolean
) {
    var systemIO: IBinder = systemIO
        private set
    var scannerGunMode: Boolean = scannerGunMode
        private set
    val scannerGunCrc8: Int
        get() = scannerGunApi.crc8

    private fun attachSystemIO(binder: IBinder) {
        systemIO = binder
    }

    fun setScannerGunMode(enabled: Boolean) {
        scannerGunMode = enabled
    }

    fun updateBackendTarget(pipefall: PipeFallIO) {
        api.onupdate(pipefall)
        hourlyOrderTicker.updateBackendTarget(pipefall)
    }

    fun attachScannerGunApi(context: Context, onScan: ((String) -> Unit)? = null) {
        scannerGunApi.setCallback(onScan)
        if (scannerGunMode) scannerGunApi.ensure(context, this)
    }

    fun setScannerGunApiCallback(onScan: ((String) -> Unit)?) {
        scannerGunApi.setCallback(onScan)
    }

    fun onScannerGunKeyEvent(context: Context, event: KeyEvent): Boolean {
        if (!scannerGunMode) {
            Log.d(RUNTIME_TAG, "scanner gun key ignored because mode off action=${event.action} code=${event.keyCode}")
            return false
        }
        return scannerGunApi.onKeyEvent(context, this, event)
    }

    fun triggerScannerGun(context: Context) {
        if (!scannerGunMode) return
        scannerGunApi.ensure(context, this)
        scannerGunApi.triggerScan()
    }

    companion object {
        private var buffer: std? = null

        fun onCreate(context: Context? = null): std {
            buffer?.let { return it }

            val target = context?.applicationContext?.readApiTarget() ?: NetTarget(
                scheme = DEFAULT_API_SCHEME,
                host = DEFAULT_API_HOST,
                port = DEFAULT_API_PORT,
                path = QUERY_BREAKFAST_RIGHT_PATH
            )
            val sharedConfig = context?.applicationContext?.readRuntimeSharedConfig() ?: RuntimeSharedConfig()

            val pipefall = PipeFall(requireNotNull(context).applicationContext)
            val systemIO = OnBindSystemIO(pipefall)
            val api = Postman(target = target)
            val waitgroup = WaitGroupBuffer(
                context = requireNotNull(context).applicationContext,
                executor = api::executeWaitGroupRequest,
                agedUnregisteredExecutor = { records ->
                    pipefall.writeCommand(UiCommand.Toast("正在提交${records.size}条已扫码未登记记录"))
                    api.uploadAgedUnregistered(records).also { result ->
                        pipefall.writeCommand(
                            UiCommand.Toast(
                                if (result.completed && result.success) {
                                    "已扫码未登记记录提交成功"
                                } else {
                                    "已扫码未登记记录提交失败，已保留待重试"
                                }
                            )
                        )
                    }
                }
            )
            val tickerNetTarget = target.copy()
            val hourlyOrderTicker = HourlyOrderTicker(
                context = context.applicationContext,
                initialTickerNetTarget = tickerNetTarget,
                tickerWaitGroup = waitgroup,
                notifyOperator = { message ->
                    pipefall.writeCommand(UiCommand.Toast(message))
                }
            )
            pipefall.bindWaitGroup(waitgroup)
            val barscanner = NewBarScanner(api = api, pipefall = pipefall)
            val uifurge = UIfurge(pipefall = pipefall)
            val scannerGunApi = ScannerGunApiBridge()

            return std(
                api = api,
                hourlyOrderTicker = hourlyOrderTicker,
                waitgroup = waitgroup,
                barscanner = barscanner,
                pipefall = pipefall,
                systemIO = systemIO,
                uifurge = uifurge,
                scannerGunApi = scannerGunApi,
                scannerGunMode = sharedConfig.scannerGunMode
            ).also {
                buffer = it
                waitgroup.enroll()
                hourlyOrderTicker.start()
            }
        }

        fun run(): std = buffer ?: onCreate()

        fun save(context: Context) {
            if (!isCreated()) return
            context.applicationContext.writeApiTarget(run().api.target)
            context.applicationContext.writeRuntimeSharedConfig(RuntimeSharedConfig(run().scannerGunMode))
            run().waitgroup.persist()
        }

        fun attachSystemIO(binder: IBinder) {
            run().attachSystemIO(binder)
        }

        fun isCreated(): Boolean = buffer != null

        fun hasSystemIO(): Boolean = buffer != null
    }
}

data class RuntimeSharedConfig(
    val scannerGunMode: Boolean = false
)

class ScannerGunApiBridge {
    private var callback: ((String) -> Unit)? = null
    private var receiverRegistered = false
    private var serviceBound = false
    private var scanInterface: SunmiScanInterface? = null
    private var activeRuntime: std? = null
    private var configured = false
    private var lastTriggerAt = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val keyBuffer = StringBuilder()
    private var keyBufferFlush: Runnable? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            scanInterface = SunmiScanInterface(service)
            Log.d(RUNTIME_TAG, "scanner gun sunmi service connected name=$name")
            configureSunmiServiceCommand()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            scanInterface = null
            serviceBound = false
            Log.d(RUNTIME_TAG, "scanner gun sunmi service disconnected name=$name")
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val code = intent.scannerGunCode().trim('\r', '\n', ' ', '\t')
            Log.d(
                RUNTIME_TAG,
                "scanner gun broadcast received action=${intent.action} keys=${intent.extras?.keySet()?.joinToString() ?: "none"} raw=${code.take(256)} data=${intent.dataString ?: ""} blank=${code.isBlank()}"
            )
            if (code.isBlank()) return
            val runtime = activeRuntime ?: return
            crc8 = code.crc8()
            Log.d(RUNTIME_TAG, "scanner gun broadcast result action=${intent.action} crc8=$crc8")
            if (runtime.scannerGunMode) submitScannerGunCode(code)
        }
    }
    var crc8: Int = 0
        private set

    fun ensure(context: Context, runtime: std) {
        activeRuntime = runtime
        val appContext = context.applicationContext
        registerBroadcastFallback(appContext)
        configureSunmiBroadcastOutput(appContext)
        bindSunmiScanService(appContext)
    }

    fun setCallback(onScan: ((String) -> Unit)?) {
        callback = onScan
    }

    private fun configureSunmiBroadcastOutput(context: Context) {
        if (!Build.MANUFACTURER.equals("SUNMI", ignoreCase = true)) return
        if (configured) return
        configured = true
        sendSunmiBroadcastOutputSetting(context, 0)
        sendSunmiSerialCommand(context, "@SCNMOD2")
        sendSunmiSerialCommand(context, "@ORTSET800")
        sendSunmiSerialCommand(context, "@RRDDUR1000")
        mainHandler.postDelayed({ sendSunmiBroadcastOutputSetting(context, 1) }, 600L)
        mainHandler.postDelayed({ sendSunmiBroadcastOutputSetting(context, 2) }, 1_500L)
    }

    private fun configureSunmiServiceCommand() {
        val scanner = scanInterface ?: return
        val commands = listOf(
            "sunmi007001=1;scan00000101=1;sunmi007003=50;scan00000102=50;",
            "sunmi003002=1;",
            "sunmi003001=1;sunmi003006=$SUNMI_SCANNER_RESULT_ACTION;sunmi003007=$SUNMI_SCANNER_RESULT_DATA_KEY;sunmi003008=$SUNMI_SCANNER_RESULT_BYTE_KEY;"
        )
        commands.forEach { command ->
            runCatching {
                scanner.sendCommand(command)
                Log.d(RUNTIME_TAG, "scanner gun sunmi service command sent command=$command")
            }.onFailure {
                Log.w(RUNTIME_TAG, "scanner gun sunmi service command failed command=$command: ${it.message ?: it.javaClass.simpleName}")
            }
        }
    }

    private fun sendSunmiSerialCommand(context: Context, command: String) {
        runCatching {
            val bytes = command.toByteArray()
            val payload = ByteArray(bytes.size + 2)
            System.arraycopy(bytes, 0, payload, 0, bytes.size)
            payload.writeSunmiLrc()
            context.sendBroadcast(Intent(SUNMI_SCANNER_COMMAND_ACTION).apply {
                putExtra("cmd_data", payload)
            })
            Log.d(RUNTIME_TAG, "scanner gun sunmi command sent command=$command")
        }.onFailure {
            Log.w(RUNTIME_TAG, "scanner gun sunmi command failed command=$command: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun bindSunmiScanService(context: Context) {
        if (!Build.MANUFACTURER.equals("SUNMI", ignoreCase = true) || serviceBound) return
        runCatching {
            val intent = Intent(SUNMI_SCANNER_SERVICE_ACTION).apply {
                setPackage(SUNMI_SCANNER_SERVICE_PACKAGE)
                component = ComponentName(SUNMI_SCANNER_SERVICE_PACKAGE, SUNMI_SCANNER_SERVICE_CLASS)
            }
            serviceBound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            Log.d(RUNTIME_TAG, "scanner gun sunmi service bind requested bound=$serviceBound")
        }.onFailure {
            Log.w(RUNTIME_TAG, "scanner gun sunmi service bind failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    fun triggerScan() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTriggerAt < 3_800L) return
        lastTriggerAt = now
        runCatching {
            val scanner = scanInterface
            if (scanner == null) {
                Log.d(RUNTIME_TAG, "scanner gun sunmi service not connected, waiting for hardware scan")
                return
            }
            configureSunmiServiceCommand()
            mainHandler.postDelayed({
                runCatching {
                    scanner.scan()
                    Log.d(RUNTIME_TAG, "scanner gun sunmi service scan requested")
                    mainHandler.postDelayed({ runCatching { scanner.stop() } }, 50L)
                }.onFailure {
                    Log.w(RUNTIME_TAG, "scanner gun sunmi service scan failed: ${it.message ?: it.javaClass.simpleName}")
                }
            }, 100L)
        }.onFailure {
            Log.w(RUNTIME_TAG, "scanner gun sunmi trigger failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun sendSunmiBroadcastOutputSetting(context: Context, attempt: Int) {
        runCatching {
            val intents = listOf(
                Intent(SUNMI_SCANNER_SETTING_ACTION).apply {
                    putExtra("name", "Point of Sale Fixed Barcode Scanner.")
                    putExtra("pid", 9492)
                    putExtra("vid", 1529)
                    putExtra("type", 2)
                    putExtra("toast", false)
                    putExtra("out_broadcast", true)
                    putExtra("output_via_broadcast", true)
                    putExtra("isBroadcast", true)
                    putExtra("broadcast", true)
                    putExtra("SET_OUT_BROADCAST", true)
                    putExtra("OUT_BROADCAST", true)
                    putExtra("SET_OUT_CODE_ACTION", SUNMI_SCANNER_RESULT_ACTION)
                    putExtra("SET_OUT_CODE_ACTION_DATA_KEY", SUNMI_SCANNER_RESULT_DATA_KEY)
                    putExtra("SET_OUT_CODE_ACTION_BYTE_KEY", SUNMI_SCANNER_RESULT_BYTE_KEY)
                },
                Intent(SUNMI_SCANNER_SERIAL_SETTING_ACTION).apply {
                    putExtra("analog_key", false)
                    putExtra("broadcast", true)
                    putExtra("SERIAL_ANALOG_EVENT_OUT", false)
                    putExtra("SERIAL_BROADCAST_OUT", true)
                    putExtra("SET_OUT_BROADCAST", true)
                    putExtra("SET_OUT_CODE_ACTION", SUNMI_SCANNER_RESULT_ACTION)
                    putExtra("SET_OUT_CODE_ACTION_DATA_KEY", SUNMI_SCANNER_RESULT_DATA_KEY)
                    putExtra("SET_OUT_CODE_ACTION_BYTE_KEY", SUNMI_SCANNER_RESULT_BYTE_KEY)
                    putExtra("toast", false)
                },
                Intent(SUNMI_SCANNER_SETTING_ACTION).apply {
                    putExtra("SET_OUT_BROADCAST", true)
                    putExtra("SET_OUT_CODE_ACTION", SUNMI_SCANNER_RESULT_ACTION)
                    putExtra("SET_OUT_CODE_ACTION_DATA_KEY", SUNMI_SCANNER_RESULT_DATA_KEY)
                    putExtra("SET_OUT_CODE_ACTION_BYTE_KEY", SUNMI_SCANNER_RESULT_BYTE_KEY)
                    putExtra("OUT_CODE_ID", true)
                    putExtra("OUT_CODE_CHARACTER", true)
                    putExtra("toast", false)
                },
                Intent(SUNMI_SCANNER_SERIAL_OPEN_ACTION)
                    .putExtra("toast", false)
            )
            intents.forEachIndexed { index, intent ->
                context.sendBroadcast(intent)
                var explicit = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.sendBroadcast(Intent(intent).apply {
                        component = ComponentName(
                            "com.sunmi.scanner",
                            "com.sunmi.scannerdevice.receiver.BarDevicesSettingReceiver"
                        )
                    })
                    explicit = true
                }
                Log.d(
                    RUNTIME_TAG,
                    "scanner gun sunmi broadcast output requested attempt=$attempt variant=$index explicit=$explicit keys=${intent.extras?.keySet()?.joinToString() ?: "none"}"
                )
            }
        }.onFailure {
            Log.w(RUNTIME_TAG, "scanner gun sunmi broadcast output failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    private fun registerBroadcastFallback(context: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(SUNMI_SCANNER_RESULT_ACTION)
            addAction("ACTION_DATA_CODE_RECEIVED")
            addAction("com.sunmi.scanner.ACTION_DATA_RECEIVED")
            addAction("com.sunmi.scanner.ACTION_BARCODE_RECEIVED")
            addAction("com.sunmi.scanner.ACTION_SCAN_RESULT")
            addAction("com.sunmi.scanner.ACTION_SCAN_DATA")
            addAction("com.sunmi.scanner.ACTION_DECODE_DATA")
            addAction("android.intent.action.SCANRESULT")
            addAction("com.android.server.scannerservice.broadcast")
            addAction("scan.rcv.message")
            addAction("android.intent.ACTION_DECODE_DATA")
            addAction("com.scanner.broadcast")
            addAction("com.qs.scancode")
            addAction("nlscan.action.SCANNER_RESULT")
            addAction("urovo.rcv.message")
            addAction("com.ubx.barcode.ACTION_DECODE_DATA")
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            receiverRegistered = true
            Log.d(RUNTIME_TAG, "scanner gun broadcast fallback registered")
            Log.d(RUNTIME_TAG, "scanner gun sunmi result receiver ready action=$SUNMI_SCANNER_RESULT_ACTION")
        }.onFailure {
            Log.w(RUNTIME_TAG, "scanner gun broadcast fallback failed: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    fun onKeyEvent(context: Context, runtime: std, event: KeyEvent): Boolean {
        Log.d(
            RUNTIME_TAG,
            "scanner gun key raw action=${event.action} code=${event.keyCode} scanCode=${event.scanCode} repeat=${event.repeatCount} unicode=${event.unicodeChar} chars=${event.characters ?: ""}"
        )
        if (runtime.scannerGunMode && onTextKeyEvent(event)) return true
        if (!event.isScannerTriggerKey()) {
            Log.d(RUNTIME_TAG, "scanner gun key not trigger code=${event.keyCode} scanCode=${event.scanCode}")
            return false
        }
        ensure(context.applicationContext, runtime)
        if (event.action == KeyEvent.ACTION_DOWN) triggerScan()
        Log.d(RUNTIME_TAG, "scanner gun trigger key left to sunmi service action=${event.action} code=${event.keyCode}")
        return false
    }

    private fun onTextKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_MULTIPLE) {
            val chars = event.characters.orEmpty().filterNot { it.isISOControl() }
            if (chars.isBlank()) return false
            keyBuffer.append(chars)
            flushTextKeyBufferSoon()
            return true
        }

        if (event.action != KeyEvent.ACTION_UP) return false
        if (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || event.keyCode == KeyEvent.KEYCODE_TAB) {
            flushTextKeyBuffer()
            return true
        }

        val unicode = event.unicodeChar
        if (unicode <= 0) return false
        val char = unicode.toChar()
        if (char.isISOControl()) return false

        keyBuffer.append(char)
        flushTextKeyBufferSoon()
        return true
    }

    private fun flushTextKeyBufferSoon() {
        keyBufferFlush?.let(mainHandler::removeCallbacks)
        keyBufferFlush = Runnable { flushTextKeyBuffer() }
        mainHandler.postDelayed(keyBufferFlush!!, 180L)
    }

    private fun flushTextKeyBuffer() {
        keyBufferFlush?.let(mainHandler::removeCallbacks)
        keyBufferFlush = null
        val code = keyBuffer.toString().trim('\r', '\n', ' ', '\t')
        keyBuffer.clear()
        if (code.isBlank()) return
        crc8 = code.crc8()
        Log.d(RUNTIME_TAG, "scanner gun key text result crc8=$crc8")
        callback?.invoke(code)
    }

    private fun submitScannerGunCode(code: String) {
        mainHandler.post { callback?.invoke(code) }
    }
}

private fun Intent.scannerGunCode(): String {
    val keys = listOf(
        "data",
        "scannerdata",
        "barcode",
        "barocode",
        "barcode_string",
        "decode_data",
        "scan_result",
        "resultData",
        "mData",
        "jsonData",
        "extraData",
        "originData",
        "rawData",
        "barCode",
        "SCAN_BARCODE1",
        "value",
        "text",
        "msg"
    )
    for (key in keys) {
        val value = extras?.get(key)?.toString().orEmpty()
        if (value.isNotBlank()) return value
    }
    extras?.keySet()?.forEach { key ->
        val value = extras?.get(key)?.toString().orEmpty()
        if (value.isNotBlank()) return value
    }
    return dataString.orEmpty()
}

private fun KeyEvent.isScannerTriggerKey(): Boolean {
    return keyCode == KeyEvent.KEYCODE_F1 ||
        keyCode == KeyEvent.KEYCODE_F2 ||
        keyCode == KeyEvent.KEYCODE_F3 ||
        keyCode == KeyEvent.KEYCODE_F4 ||
        keyCode == KeyEvent.KEYCODE_F5 ||
        keyCode == KeyEvent.KEYCODE_F6 ||
        keyCode == KeyEvent.KEYCODE_F7 ||
        keyCode == KeyEvent.KEYCODE_F8 ||
        keyCode == KeyEvent.KEYCODE_F9 ||
        keyCode == KeyEvent.KEYCODE_F10 ||
        keyCode == KeyEvent.KEYCODE_F11 ||
        keyCode == KeyEvent.KEYCODE_F12 ||
        keyCode == KeyEvent.KEYCODE_BUTTON_L1 ||
        keyCode == KeyEvent.KEYCODE_BUTTON_R1 ||
        keyCode == KeyEvent.KEYCODE_BUTTON_L2 ||
        keyCode == KeyEvent.KEYCODE_BUTTON_R2 ||
        keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL ||
        keyCode == KeyEvent.KEYCODE_BUTTON_THUMBR ||
        keyCode in 280..293 ||
        keyCode in 520..523
}

private fun String.crc8(): Int {
    var crc = 0
    for (byte in toByteArray(Charsets.UTF_8)) {
        crc = crc xor (byte.toInt() and 0xFF)
        repeat(8) {
            crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x07) else (crc shl 1)
            crc = crc and 0xFF
        }
    }
    return crc and 0xFF
}

private fun ByteArray.writeSunmiLrc() {
    var crc = 0
    for (index in 0 until size - 2) {
        crc += this[index].toInt() and 0xFF
    }
    crc = crc.inv() + 1
    this[size - 2] = ((crc shr 8) and 0xFF).toByte()
    this[size - 1] = (crc and 0xFF).toByte()
}

private class SunmiScanInterface(private val remote: IBinder) {
    fun scan() = transactNoArgs(2)

    fun stop() = transactNoArgs(4)

    fun sendCommand(command: String) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SUNMI_SCANNER_SERVICE_ACTION)
            data.writeString(command)
            remote.transact(6, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactNoArgs(code: Int) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(SUNMI_SCANNER_SERVICE_ACTION)
            remote.transact(code, data, reply, 0)
            reply.readException()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}

private fun Context.readRuntimeSharedConfig(): RuntimeSharedConfig {
    val prefs = getSharedPreferences(RUNTIME_SHARED_PREFS, Context.MODE_PRIVATE)
    return RuntimeSharedConfig(
        scannerGunMode = prefs.getBoolean(SCANNER_GUN_MODE, false)
    )
}

private fun Context.writeRuntimeSharedConfig(config: RuntimeSharedConfig) {
    getSharedPreferences(RUNTIME_SHARED_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(SCANNER_GUN_MODE, config.scannerGunMode)
        .apply()
}

private fun Context.readApiTarget(): NetTarget {
    val prefs = getSharedPreferences(API_TARGET_PREFS, Context.MODE_PRIVATE)
    return NetTarget(
        scheme = prefs.getString(API_TARGET_SCHEME, DEFAULT_API_SCHEME) ?: DEFAULT_API_SCHEME,
        host = prefs.getString(API_TARGET_HOST, DEFAULT_API_HOST) ?: DEFAULT_API_HOST,
        port = prefs.getInt(API_TARGET_PORT, DEFAULT_API_PORT),
        path = QUERY_BREAKFAST_RIGHT_PATH
    )
}

private fun Context.writeApiTarget(target: NetTarget) {
    getSharedPreferences(API_TARGET_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(API_TARGET_SCHEME, target.scheme)
        .putString(API_TARGET_HOST, target.host)
        .putInt(API_TARGET_PORT, target.port)
        .apply()
}

data class NetTarget(
    val scheme: String = "http",
    val host: String,
    val port: Int = 80,
    val path: String = "",
    val headers: Map<String, String> = emptyMap(),
    val connectTimeoutMs: Long = NETWORK_CONNECT_TIMEOUT_MS,
    val readTimeoutMs: Long = NETWORK_READ_TIMEOUT_MS,
    val retry: Int = POST_RETRY_COUNT,
    val terminalId: String? = null,
    val operatorId: String? = null,
    val barcode: String? = null,
    val qrcode: String? = null
) {
    fun url(): String {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        return "$scheme://$host:$port$cleanPath"
    }
}

class Postman(
    target: NetTarget
) {
    private val targetLock = ReentrantReadWriteLock()
    private var targetState: NetTarget = target

    val target: NetTarget
        get() = targetLock.read { targetState }

    fun get(xwww: Map<String, String>): PostmanAction {
        return GetXwwwAction(
            targetLock.read { targetState.copy(path = CHANGE_BREAKFAST_STATUS_PATH) },
            xwww
        )
    }

    fun post(json: Map<String, Any?>): PostmanAction {
        val lockedTarget = targetLock.read { targetState.copy(path = QUERY_BREAKFAST_RIGHT_PATH) }
        val action = PostJsonAction(
            target = lockedTarget,
            json = json,
            onCallback = {},
            onComplete = {
                clearTemporaryFields()
            }
        )
        dumpTemporaryFields()
        return action
    }

    fun onupdate(pipefall: PipeFallIO): Postman {
        val scanCode = pipefall.latestScanCode
        val ipv4Address = pipefall.latestIPv4Address
        val route = pipefall.latestNetRoute
        targetLock.write {
            targetState = targetState.copy(
                scheme = route?.scheme ?: targetState.scheme,
                host = ipv4Address?.value ?: targetState.host,
                port = route?.port ?: targetState.port,
                path = targetState.path,
                barcode = scanCode?.value ?: targetState.barcode,
                qrcode = scanCode?.value ?: targetState.qrcode
            )
        }
        return this
    }

    fun dump(): Postman {
        targetLock.write {
            targetState = NetTarget(
                host = "",
                headers = emptyMap(),
                terminalId = null,
                operatorId = null,
                barcode = null,
                qrcode = null
            )
        }
        return this
    }

    private fun dumpTemporaryFields(): Postman {
        targetLock.write {
            targetState = targetState.copy(
                barcode = null,
                qrcode = null
            )
        }
        return this
    }

    private fun clearTemporaryFields() {
        dumpTemporaryFields()
    }

    suspend fun executeWaitGroupRequest(request: WaitGroupRequest): WaitGroupExecutionResult {
        return when (request.task) {
            WaitGroupTask.QueryBreakfastRight -> {
                val action = post(
                    mapOf("order_code" to request.orderCode, "card_no" to null)
                ) as PostJsonAction
                action.Do()
                WaitGroupExecutionResult(
                    completed = action.ok,
                    success = action.ok,
                    responseText = action.responseText,
                    message = action.msg
                )
            }
            WaitGroupTask.Enroll, WaitGroupTask.CancelEnrollment -> {
                val enroll = request.task == WaitGroupTask.Enroll
                val action = get(
                    mapOf(
                        "order_code" to request.orderCode,
                        "breakfast_status" to if (enroll) "1" else "0",
                        "breakfast_remark" to if (enroll) {
                            "PDA扫码核销早餐"
                        } else {
                            "PDA取消早餐核销"
                        }
                    )
                )
                action.Do()
                WaitGroupExecutionResult(
                    completed = action.ok,
                    success = action.ok,
                    message = action.msg
                )
            }
        }
    }

    suspend fun queryBreakfastRightForTicker(orderCode: String): WaitGroupExecutionResult =
        executeWaitGroupRequest(
            WaitGroupRequest(
                orderCode = orderCode,
                task = WaitGroupTask.QueryBreakfastRight,
                approvalState = WaitApprovalState.Ignored
            )
        )

    suspend fun queryOrderCodesByTimeRange(
        startUtcSeconds: Long,
        endUtcSeconds: Long
    ): List<String> {
        require(startUtcSeconds >= 0L && endUtcSeconds > startUtcSeconds)
        val rangeTarget = targetLock.read {
            targetState.copy(path = QUERY_ORDER_CODES_BY_TIME_RANGE_PATH)
        }
        val body = JSONObject()
            .put("start_utc", startUtcSeconds)
            .put("end_utc", endUtcSeconds)
            .toString()
        Log.i(
            RUNTIME_TAG,
            "range POST request url=${rangeTarget.url()} method=POST " +
                "contentType=application/json; charset=utf-8"
        )
        logCompletePayload(RUNTIME_TAG, "range POST request body", body)
        val response = withContext(Dispatchers.IO) {
            executeRawHttp11Post(rangeTarget, body)
        }
        Log.i(
            RUNTIME_TAG,
            "range POST response startUtc=$startUtcSeconds endUtc=$endUtcSeconds " +
                "http=${response.statusCode} bodyLength=${response.body.length}"
        )
        logCompletePayload(RUNTIME_TAG, "range POST response body", response.body)
        require(response.statusCode in 200..299) { "HTTP ${response.statusCode}" }
        val root = JSONObject(response.body)
        val responseMessage = root.optString("msg").takeIf(String::isNotBlank)
            ?: root.optString("message").takeIf(String::isNotBlank)
        Log.i(
            RUNTIME_TAG,
            "range POST decoded code=${root.opt("code")} status=${root.opt("status")} " +
                "msg=${root.optString("msg")} message=${root.optString("message")}"
        )
        require(root.optInt("code", Int.MIN_VALUE) == 200) {
            responseMessage ?: "range query failed"
        }
        require(root.optBoolean("status", false)) {
            responseMessage ?: "range query status is false"
        }
        val records = root.optJSONArray("data")
            ?: throw IllegalArgumentException("missing data")
        return buildList {
            repeat(records.length()) { index ->
                val record = records.optJSONObject(index)
                    ?: throw IllegalArgumentException("data[$index] is not an object")
                val orderCode = record.opt("order_code") as? String
                    ?: throw IllegalArgumentException("data[$index].order_code is not a string")
                orderCode.trim().takeIf(String::isNotEmpty)?.let(::add)
            }
        }.distinct()
    }

    suspend fun uploadAgedUnregistered(
        records: List<AgedUnregisteredRequest>
    ): WaitGroupExecutionResult {
        if (records.isEmpty()) {
            return WaitGroupExecutionResult(completed = true, success = true)
        }
        val body = JSONArray().apply {
            records.take(10).forEach { record ->
                put(
                    JSONObject()
                        .put("order_code", record.orderCode)
                        .put("created_at", record.createdAtEpochMs)
                )
            }
        }.toString()
        val uploadTarget = targetLock.read {
            targetState.copy(path = UPLOAD_UNREGISTERED_SCAN_PATH)
        }
        return withContext(Dispatchers.IO) {
            var lastResult = WaitGroupExecutionResult(completed = false, message = "upload failed")
            for (attempt in 0..uploadTarget.retry.coerceAtLeast(0)) {
                try {
                    val response = executeRawHttp11Post(uploadTarget, body)
                    val responseJson = runCatching { JSONObject(response.body) }.getOrNull()
                    val businessCode = responseJson
                        ?.takeIf { it.has("code") && !it.isNull("code") }
                        ?.optInt("code")
                    val success = response.statusCode == 200 &&
                        (businessCode == null || businessCode == 200)
                    lastResult = WaitGroupExecutionResult(
                        completed = true,
                        success = success,
                        responseText = response.body,
                        message = responseJson?.optString("msg")?.takeIf(String::isNotBlank)
                            ?: if (success) null else "HTTP ${response.statusCode}"
                    )
                    if (success) return@withContext lastResult
                    if (response.statusCode in 400..499 || (businessCode != null && businessCode != 200)) {
                        return@withContext lastResult
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    lastResult = WaitGroupExecutionResult(
                        completed = false,
                        message = error.message ?: error.javaClass.simpleName
                    )
                }
                if (attempt < uploadTarget.retry.coerceAtLeast(0)) {
                    delay(POST_RETRY_DELAY_MS)
                }
            }
            lastResult
        }
    }

}

interface PostmanAction {
    val ok: Boolean
    val done: Boolean
    val msg: String?

    suspend fun Do(): PostmanAction
}

abstract class OncePostmanAction : PostmanAction {
    final override var ok: Boolean = false
        protected set

    final override var done: Boolean = false
        protected set

    final override var msg: String? = null
        protected set

    final override suspend fun Do(): PostmanAction {
        if (done) {
            ok = false
            msg = "action already executed"
            return this
        }

        done = true
        ok = try {
            runOnce()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            msg = e.message
            false
        }

        return this
    }

    protected abstract suspend fun runOnce(): Boolean

    protected fun setMessage(message: String?) {
        msg = message
    }
}

class GetXwwwAction(
    private val target: NetTarget,
    private val xwww: Map<String, String>
) : OncePostmanAction() {
    override suspend fun runOnce(): Boolean = withContext(Dispatchers.IO) {
        val query = xwww.entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }
        val separator = if (target.url().contains("?")) "&" else "?"
        val requestUrl = if (query.isBlank()) target.url() else "${target.url()}$separator$query"
        var attempt = 0
        var ok = false
        var lastMessage: String? = null

        while (attempt < 5 && !ok) {
            ok = try {
                executeHttp("GET", requestUrl, target, null, ::setMessage)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastMessage = e.message ?: e.javaClass.simpleName
                setMessage(lastMessage)
                false
            }

            if (!ok) {
                lastMessage = msg ?: lastMessage ?: "GET failed"
                Log.w(RUNTIME_TAG, "get attempt failed ${attempt + 1}/5: $lastMessage")
                attempt += 1
                if (attempt < 5) {
                    delay((300L * attempt).coerceAtMost(1_500L))
                }
            }
        }

        ok
    }
}

class PostJsonAction(
    private val target: NetTarget,
    private val json: Map<String, Any?>,
    private val onCallback: suspend () -> Unit = {},
    private val onComplete: () -> Unit = {}
) : OncePostmanAction() {
    var responseText: String = ""
        private set

    override suspend fun runOnce(): Boolean = withContext(Dispatchers.IO) {
        try {
            val body = json.toJson()
            val maxRetry = target.retry.coerceAtLeast(0)
            var attempt = 0
            var lastMessage: String? = null
            var lastError: Exception? = null

            while (attempt <= maxRetry) {
                try {
                    Log.i(
                        RUNTIME_TAG,
                        "post request url=${target.url()} attempt=${attempt + 1}/${maxRetry + 1} " +
                            "method=POST contentType=application/json; charset=utf-8"
                    )
                    logCompletePayload(RUNTIME_TAG, "post request body", body)
                    val response = executeRawHttp11Post(target, body)
                    responseText = response.body
                    Log.i(
                        RUNTIME_TAG,
                        "post response url=${target.url()} attempt=${attempt + 1}/${maxRetry + 1} " +
                            "http=${response.statusCode} bodyLength=${response.body.length}"
                    )
                    logCompletePayload(RUNTIME_TAG, "post response body", response.body)
                    val responseJson = runCatching { JSONObject(response.body) }.getOrNull()
                    val businessCode = responseJson
                        ?.takeIf { it.has("code") && !it.isNull("code") }
                        ?.optInt("code")
                    val responseMessage = responseJson
                        ?.optString("msg")
                        ?.takeIf(String::isNotBlank)

                    val ok = response.statusCode in 200..299 && (businessCode == null || businessCode == 200)
                    if (ok) {
                        setMessage(null)
                        onCallback()
                        return@withContext true
                    }

                    lastMessage = responseMessage ?: "HTTP ${response.statusCode}"
                    setMessage(lastMessage)
                    Log.w(RUNTIME_TAG, "post attempt failed ${attempt + 1}/${maxRetry + 1}: $lastMessage")
                    if (response.statusCode in 400..499 || (businessCode != null && businessCode != 200)) {
                        return@withContext false
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    lastMessage = e.message ?: e.javaClass.simpleName
                    setMessage(lastMessage)
                    Log.w(RUNTIME_TAG, "post attempt error ${attempt + 1}/${maxRetry + 1}: $lastMessage")
                }

                attempt += 1
                if (attempt <= maxRetry) {
                    Log.d(
                        RUNTIME_TAG,
                        "post retry scheduled ${attempt + 1}/${maxRetry + 1} " +
                            "in ${POST_RETRY_DELAY_MS}ms"
                    )
                    delay(POST_RETRY_DELAY_MS)
                }
            }

            if (lastError != null && lastMessage.isNullOrBlank()) {
                setMessage(lastError.javaClass.simpleName)
            }
            false
        } finally {
            onComplete()
        }
    }
}

class BarScanner(
    private val api: Postman,
    private val pipefall: PipeFallIO
) {
    private var running = false

    fun start() {
        running = true
    }

    fun stop() {
        running = false
    }

    fun destroy() {
        running = false
    }

    fun isRunning(): Boolean = running

    suspend fun onScan(orderCode: String) {
        if (orderCode.isBlank()) return
        val code = orderCode.trim()
        Log.d(RUNTIME_TAG, "scan received: $code")
        pipefall.writeEvent(UiEvent.ScanCode(code))
    }
}

fun NewBarScanner(api: Postman, pipefall: PipeFallIO): BarScanner {
    return BarScanner(api = api, pipefall = pipefall)
}

class UIfurge(
    private val pipefall: PipeFallIO
) {
    fun onScanInput(code: String) {
        push(Action.TextBox(id = "order_code", value = code.trim()))
    }

    fun onApproveBreakfastClick(orderCode: String) {
        push(Action.Button(id = "approve_breakfast", value = orderCode.trim()))
    }

    fun onCancelEnrollmentClick(orderCode: String) {
        push(Action.Button(id = "cancel_enrollment", value = orderCode.trim()))
    }

    fun onDialogCancelClick() {
        push(Action.Button(id = "dialog_cancel"))
    }

    fun onPencilButtonClick() {
        push(Action.Button(id = "pencil"))
    }

    fun onFlashlightButtonClick() {
        push(Action.Button(id = "flashlight"))
    }

    fun onMenuButtonClick() {
        push(Action.Button(id = "menu"))
    }

    fun render(command: UiCommand) {
        pipefall.writeCommand(command)
    }

    private fun push(action: Action) {
        if (!std.hasSystemIO()) {
            pipefall.writeCommand(UiCommand.Toast("系统服务连接中，请稍后再试"))
            return
        }
        val eventBuffer = NewEventListener(action)
        val pusher = NewPusherService(eventBuffer, std.run().systemIO)
        pusher.Listen()
    }
}

sealed interface Action {
    val id: String
    val value: String

    data class Button(
        override val id: String,
        override val value: String = ""
    ) : Action

    data class TextBox(
        override val id: String,
        override val value: String
    ) : Action
}

data class EventListenerBuffer(
    val action: Action,
    val event: UiEvent?
)

data class PusherBuffer(
    val listenerBuffer: EventListenerBuffer,
    val binder: IBinder
)

fun NewEventListener(action: Action): EventListenerBuffer {
    val event = when (action) {
        is Action.TextBox -> when (action.id) {
            "order_code" -> UiEvent.ScanCode(action.value)
            else -> UiEvent.ActionNotice("文本框事件：${action.id}")
        }

        is Action.Button -> when (action.id) {
            "approve_breakfast" -> UiEvent.ApprovalAction(
                action.value,
                ApprovalQueueAction.ApproveBreakfast
            )
            "cancel_enrollment" -> UiEvent.ApprovalAction(
                action.value,
                ApprovalQueueAction.CancelEnrollment
            )
            "dialog_cancel" -> UiEvent.DialogCancel
            "pencil" -> null
            "flashlight" -> null
            "menu" -> null
            else -> UiEvent.ActionNotice("按钮事件：${action.id}")
        }
    }
    return EventListenerBuffer(action = action, event = event)
}

fun NewPusherService(eventBuffer: EventListenerBuffer, binder: IBinder): Pusher {
    return Pusher(PusherBuffer(listenerBuffer = eventBuffer, binder = binder))
}

class Pusher(
    val buffer: PusherBuffer
) {
    fun Listen() {
        val pipefall = buffer.binder as? PipeFallIO ?: return
        buffer.listenerBuffer.event?.let(pipefall::writeEvent)
    }
}

sealed interface UiEvent {
    data class ScanCode(val code: String) : UiEvent
    data class ApprovalAction(
        val orderCode: String,
        val action: ApprovalQueueAction
    ) : UiEvent
    data class ActionNotice(val message: String) : UiEvent
    data object DialogCancel : UiEvent
}

sealed interface UiCommand {
    data class Toast(val message: String) : UiCommand
    data class SubmissionChanged(val submitting: Boolean) : UiCommand
    data class BreakfastStatusQueued(val orderCode: String, val message: String) : UiCommand
    data class ShowCustomer(
        val orderCode: String,
        val cardNo: String?,
        val name: String,
        val sex: String?,
        val phone: String?,
        val avatarUrl: String?,
        val birthDate: String?,
        val packageName: String?,
        val examType: String?,
        val organizationName: String?,
        val examTotalPrice: String?,
        val hasBreakfast: Boolean,
        val allowBreakfast: Boolean,
        val blockReason: String?,
        val querying: Boolean = false,
        val preCached: Boolean = false
    ) : UiCommand

    data object CloseDialog : UiCommand
}

abstract class PipeFallIO : Binder() {
    abstract fun writeEvent(event: UiEvent)
    abstract fun writeCommand(command: UiCommand)
    abstract fun writeIPv4Address(address: IPv4Address)
    abstract fun writeNetRoute(route: NetRoute)
    abstract fun writeBackendTarget(address: IPv4Address, route: NetRoute)
    abstract val commands: SharedFlow<UiCommand>
    abstract val ipv4Addresses: SharedFlow<IPv4Address>
    abstract val latestScanCode: ScanCodePayload?
    abstract val latestIPv4Address: IPv4Address?
    abstract val latestNetRoute: NetRoute?
}

data class IPv4Address(
    val value: String
)

data class ScanCodePayload(
    val value: String
)

data class NetRoute(
    val scheme: String = "http",
    val port: Int = 80,
    val path: String = ""
)

class PipeFall(context: Context) : PipeFallIO() {
    private val appContext = context.applicationContext
    private sealed interface SubmissionState {
        data object Idle : SubmissionState
        data class Submitting(val orderCode: String, val status: String) : SubmissionState
    }

    private val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Queue submissions use an independent supervised buffer. A failed submission destroys
    // only its own child job and cannot cancel the scan/event collector.
    private val queueBufferScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val eventSocket = MutableSharedFlow<UiEvent>(extraBufferCapacity = 64)
    private val commandSocket = MutableSharedFlow<UiCommand>(extraBufferCapacity = 64)
    private val ipv4Socket = MutableSharedFlow<IPv4Address>(replay = 1, extraBufferCapacity = 8)

    override val commands: SharedFlow<UiCommand> = commandSocket
    override val ipv4Addresses: SharedFlow<IPv4Address> = ipv4Socket
    override var latestScanCode: ScanCodePayload? = null
        private set
    override var latestIPv4Address: IPv4Address? = null
        private set
    override var latestNetRoute: NetRoute? = null
        private set
    private var waitGroup: WaitGroupIO? = null
    private val submissionState = AtomicReference<SubmissionState>(SubmissionState.Idle)
    private val scanStateLockedAt = AtomicLong(0L)
    private val hiddenCustomerQueries = ConcurrentHashMap.newKeySet<String>()
    private val customerCacheNodes = ConcurrentHashMap<String, BreakfastRightCacheNode>()
    private val ignoredSourceNodeIds = ConcurrentHashMap<String, String>()

    init {
        workerScope.launch {
            eventSocket.collect { event ->
                when (event) {
                    is UiEvent.ScanCode -> startCustomerQuery(event.code)
                    UiEvent.DialogCancel -> cancelCustomerQuery()
                    else -> handleEvent(event)
                }
            }
        }
    }

    fun bindWaitGroup(waitGroup: WaitGroupIO) {
        this.waitGroup = waitGroup
        workerScope.launch {
            waitGroup.onwaitgroup.collect(::handleWaitGroupOutput)
        }
    }

    override fun writeEvent(event: UiEvent) {
        if (event is UiEvent.ScanCode && submissionState.get() !is SubmissionState.Idle) {
            Log.d(RUNTIME_TAG, "scan ignored while breakfast status is submitting")
            return
        }
        if (event is UiEvent.ScanCode && !tryAcquireScanState()) {
            Log.d(RUNTIME_TAG, "scan ignored because scan state machine is locked")
            return
        }
        if (event is UiEvent.ApprovalAction) {
            val orderCode = event.orderCode
            hiddenCustomerQueries += orderCode
            val status = if (event.action == ApprovalQueueAction.ApproveBreakfast) "1" else "0"
            if (!submissionState.compareAndSet(
                    SubmissionState.Idle,
                    SubmissionState.Submitting(orderCode, status)
                )
            ) {
                Log.d(RUNTIME_TAG, "duplicate breakfast submission ignored: orderCode=$orderCode")
                return
            }
            writeCommand(UiCommand.SubmissionChanged(true))
        }
        if (event is UiEvent.ScanCode) {
            latestScanCode?.value
                ?.takeIf { it != event.code }
                ?.let(hiddenCustomerQueries::add)
            hiddenCustomerQueries -= event.code
            latestScanCode = ScanCodePayload(event.code)
            if (event.code.isNotBlank()) {
                writeCommand(
                    UiCommand.ShowCustomer(
                        orderCode = event.code,
                        cardNo = null,
                        name = "N/A 查询中",
                        sex = null,
                        phone = null,
                        avatarUrl = null,
                        birthDate = null,
                        packageName = "N/A 查询中",
                        examType = null,
                        organizationName = null,
                        examTotalPrice = null,
                        hasBreakfast = false,
                        allowBreakfast = false,
                        blockReason = null,
                        querying = true
                    )
                )
            }
        }
        val emitted = eventSocket.tryEmit(event)
        Log.d(RUNTIME_TAG, "event emitted=$emitted event=$event")
        if (!emitted) {
            if (event is UiEvent.ApprovalAction) {
                submissionState.set(SubmissionState.Idle)
                writeCommand(UiCommand.SubmissionChanged(false))
            }
            if (event is UiEvent.ScanCode) {
                releaseScanState()
                writeCommand(UiCommand.CloseDialog)
            }
            writeCommand(UiCommand.Toast("扫码任务繁忙，请稍后再扫"))
        }
    }

    override fun writeCommand(command: UiCommand) {
        uiScope.launch {
            commandSocket.emit(command)
        }
    }

    override fun writeIPv4Address(address: IPv4Address) {
        latestIPv4Address = address
        uiScope.launch {
            ipv4Socket.emit(address)
        }
    }

    override fun writeNetRoute(route: NetRoute) {
        latestNetRoute = route
    }

    override fun writeBackendTarget(address: IPv4Address, route: NetRoute) {
        latestIPv4Address = address
        latestNetRoute = route
        uiScope.launch {
            ipv4Socket.emit(address)
        }
    }

    private suspend fun handleEvent(event: UiEvent) {
        when (event) {
            is UiEvent.ScanCode -> startCustomerQuery(event.code)
            is UiEvent.ApprovalAction -> enqueueStatusChange(event.orderCode, event.action)

            is UiEvent.ActionNotice -> writeCommand(UiCommand.Toast(event.message))
            UiEvent.DialogCancel -> cancelCustomerQuery()
        }
    }

    private fun startCustomerQuery(orderCode: String) {
        if (orderCode.isBlank()) {
            writeCommand(UiCommand.Toast("订单号不能为空"))
            return
        }

        queueBufferScope.launch {
            val ignoredSnapshot = try {
                waitGroup?.findIgnoredOrder(orderCode)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(RUNTIME_TAG, "ignored cache lookup failed; falling back to POST", error)
                null
            }
            if (ignoredSnapshot != null) {
                ignoredSourceNodeIds[orderCode] = ignoredSnapshot.nodeId
                val cached = ignoredSnapshot.cacheNode
                if (cached != null && cached.orderCode == orderCode) {
                    customerCacheNodes[orderCode] = cached
                    showCustomerView(
                        orderCode,
                        cached.toBreakfastRightView(),
                        preCached = true
                    )
                    return@launch
                }
            } else {
                ignoredSourceNodeIds.remove(orderCode)
            }
            val request = WaitGroupRequest(
                orderCode = orderCode,
                task = WaitGroupTask.QueryBreakfastRight
            )
            val result = try {
                std.run().api.executeWaitGroupRequest(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.e(RUNTIME_TAG, "customer query failed outside wait group: orderCode=$orderCode", error)
                WaitGroupExecutionResult(
                    completed = false,
                    message = error.message ?: "客户信息查询失败"
                )
            }
            showCustomerQueryResult(orderCode, result)
        }
    }

    private fun cancelCustomerQuery() {
        releaseScanState()
        latestScanCode?.value?.let { orderCode ->
            hiddenCustomerQueries += orderCode
        }
        writeCommand(UiCommand.CloseDialog)
    }

    private suspend fun enqueueStatusChange(orderCode: String, action: ApprovalQueueAction) {
        if (orderCode.isBlank()) {
            finishSubmission("订单号不能为空")
            return
        }
        val queue = waitGroup
        if (queue == null) {
            finishSubmission("任务队列尚未初始化")
            return
        }
        val cacheNode = customerCacheNodes.remove(orderCode)
        val ignoredSourceNodeId = ignoredSourceNodeIds.remove(orderCode)
        if (!pushToWaitGroup(orderCode, action, cacheNode, ignoredSourceNodeId, queue)) {
            if (cacheNode != null) customerCacheNodes[orderCode] = cacheNode
            if (ignoredSourceNodeId != null) ignoredSourceNodeIds[orderCode] = ignoredSourceNodeId
            finishSubmission("本次操作入队失败，请重试")
            return
        }
        releaseScanState()
        submissionState.set(SubmissionState.Idle)
        writeCommand(UiCommand.SubmissionChanged(false))
        writeCommand(
            UiCommand.BreakfastStatusQueued(
                orderCode,
                if (action == ApprovalQueueAction.ApproveBreakfast) "早餐核销已进入后台队列" else "取消登记已进入后台队列"
            )
        )
    }

    private suspend fun pushToWaitGroup(
        orderCode: String,
        action: ApprovalQueueAction,
        cacheNode: BreakfastRightCacheNode?,
        ignoredSourceNodeId: String?,
        queue: WaitGroupIO? = waitGroup
    ): Boolean {
        val target = queue ?: return false
        return try {
            target.push(
                orderCode = orderCode,
                action = action,
                approvalState = cacheNode?.allowBreakfast?.let {
                    if (it) WaitApprovalState.Approved else WaitApprovalState.Blocked
                } ?: WaitApprovalState.Pending,
                cacheNode = cacheNode,
                ignoredSourceNodeId = ignoredSourceNodeId
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(
                RUNTIME_TAG,
                "discarding failed queue-buffer task: orderCode=$orderCode action=$action",
                error
            )
            false
        }
    }

    private fun handleWaitGroupOutput(output: WaitGroupOutput) {
        if (
            output.request.task == WaitGroupTask.QueryBreakfastRight &&
            output.outcome == WaitGroupOutcome.AgedOut
        ) {
            Log.i(
                RUNTIME_TAG,
                "expired query dequeued locally: orderCode=${output.request.orderCode}"
            )
            return
        }
        when (output.request.task) {
            WaitGroupTask.QueryBreakfastRight -> showCustomerFromQueue(output)
            WaitGroupTask.Enroll, WaitGroupTask.CancelEnrollment -> {
                if (output.request.task == WaitGroupTask.Enroll) {
                    notifyEnrollmentResult(output)
                }
                if (output.outcome == WaitGroupOutcome.Completed) {
                    writeCommand(UiCommand.Toast(if (output.request.task == WaitGroupTask.Enroll) {
                        "早餐核销成功"
                    } else {
                        "已取消早餐核销"
                    }))
                } else {
                    writeCommand(UiCommand.Toast(output.result.message ?: "早餐状态提交失败"))
                }
            }
        }
    }

    private fun notifyEnrollmentResult(output: WaitGroupOutput) {
        val customer = output.cacheNode?.toBreakfastRightView()
            ?: output.result.responseText?.let(::parseBreakfastRight)
        val name = customer?.name?.takeIf { it.isNotBlank() && it != "未知客户" }
        val person = name?.let { "（$it）" }.orEmpty()
        val blocked = customer?.allowBreakfast == false ||
            output.result.message?.contains("无早餐权益") == true
        val title: String
        val text: String
        if (blocked) {
            title = "早餐审批：无早餐权益"
            val reason = customer?.reason?.takeIf(String::isNotBlank) ?: "后端未提供具体理由"
            text = "导检单号 ${output.request.orderCode}$person：$reason"
        } else if (output.outcome == WaitGroupOutcome.Completed && output.result.success) {
            title = "早餐登记成功"
            text = "导检单号 ${output.request.orderCode}$person 已成功登记早餐"
        } else {
            return
        }
        postApprovalNotification(appContext, output.id, title, text)
    }

    private fun showCustomerFromQueue(output: WaitGroupOutput) {
        showCustomerQueryResult(output.request.orderCode, output.result, output.outcome)
    }

    private fun showCustomerQueryResult(
        orderCode: String,
        result: WaitGroupExecutionResult,
        outcome: WaitGroupOutcome = if (result.completed) {
            WaitGroupOutcome.Completed
        } else {
            WaitGroupOutcome.Failed
        }
    ) {
        if (orderCode in hiddenCustomerQueries) {
            Log.d(
                RUNTIME_TAG,
                "late customer result ignored after dialog closed: orderCode=$orderCode"
            )
            return
        }
        if (outcome != WaitGroupOutcome.Completed) {
            writeCommand(UiCommand.Toast(result.message ?: "客户信息查询失败"))
            return
        }
        val cacheNode = result.responseText?.let(::parseBreakfastRightCacheNode)
        val breakfastRight = cacheNode?.toBreakfastRightView()
            ?: result.responseText?.let(::parseBreakfastRight)
        if (breakfastRight == null) {
            writeCommand(UiCommand.Toast("客户信息解析失败"))
            return
        }
        if (cacheNode != null && cacheNode.orderCode == orderCode) {
            customerCacheNodes[orderCode] = cacheNode
        }
        showCustomerView(orderCode, breakfastRight)
    }

    private fun showCustomerView(
        orderCode: String,
        breakfastRight: BreakfastRightView,
        preCached: Boolean = false
    ) {
        if (orderCode in hiddenCustomerQueries) return
        val blockReason = breakfastRight.blockReason()
        val resolvedAvatarUrl = std.run().api.target.resolveResourceUrl(breakfastRight.avatarUrl)
        writeCommand(
            UiCommand.ShowCustomer(
                orderCode = breakfastRight.orderCode,
                cardNo = breakfastRight.cardNo,
                name = breakfastRight.name,
                sex = breakfastRight.sex,
                phone = breakfastRight.phone,
                avatarUrl = resolvedAvatarUrl,
                birthDate = breakfastRight.birthDate,
                packageName = breakfastRight.packageName,
                examType = breakfastRight.examType,
                organizationName = breakfastRight.organizationName,
                examTotalPrice = breakfastRight.examTotalPrice,
                hasBreakfast = breakfastRight.hasBreakfast,
                allowBreakfast = breakfastRight.allowBreakfast,
                blockReason = blockReason,
                preCached = preCached
            )
        )
    }

    private fun finishSubmission(message: String) {
        releaseScanState()
        submissionState.set(SubmissionState.Idle)
        writeCommand(UiCommand.SubmissionChanged(false))
        writeCommand(UiCommand.Toast(message))
    }

    private fun tryAcquireScanState(): Boolean {
        while (true) {
            val previous = scanStateLockedAt.get()
            val now = SystemClock.elapsedRealtime().coerceAtLeast(1L)
            if (previous != 0L && now - previous < SCAN_STATE_TIMEOUT_MS) return false
            if (scanStateLockedAt.compareAndSet(previous, now)) {
                workerScope.launch {
                    delay(SCAN_STATE_TIMEOUT_MS)
                    scanStateLockedAt.compareAndSet(now, 0L)
                }
                return true
            }
        }
    }

    private fun releaseScanState() {
        scanStateLockedAt.set(0L)
    }
}

fun OnBindSystemIO(pipefall: PipeFallIO): IBinder {
    return pipefall
}

class SystemIOBoundService : Service() {
    override fun onBind(intent: Intent?): IBinder {
        if (!std.isCreated()) std.onCreate(applicationContext)
        return OnBindSystemIO(std.run().pipefall)
    }
}

private data class RawHttpResponse(
    val statusCode: Int,
    val body: String
)

private data class BreakfastRightView(
    val orderCode: String,
    val cardNo: String?,
    val name: String,
    val sex: String?,
    val phone: String?,
    val avatarUrl: String?,
    val birthDate: String?,
    val packageName: String?,
    val examType: String?,
    val organizationName: String?,
    val examTotalPrice: String?,
    val hasBreakfast: Boolean,
    val allowBreakfast: Boolean,
    val reason: String?
)

private fun BreakfastRightCacheNode.toBreakfastRightView(): BreakfastRightView = BreakfastRightView(
    orderCode = orderCode,
    cardNo = cardNo,
    name = nameB64.decodeBase64() ?: "未知客户",
    sex = sexB64.decodeBase64(),
    phone = phone,
    avatarUrl = avatarUrl,
    birthDate = birthDate,
    packageName = packageNameB64.decodeBase64(),
    examType = examTypeB64.decodeBase64(),
    organizationName = organizationNameB64.decodeBase64(),
    examTotalPrice = examTotalPriceB64.decodeBase64()?.takeIf {
        Regex("\\d+(?:\\.\\d{1,2})?").matches(it)
    },
    hasBreakfast = hasBreakfast ?: false,
    allowBreakfast = allowBreakfast ?: false,
    reason = reasonB64.decodeBase64()
)

private fun String?.decodeBase64(): String? {
    val encoded = this?.takeIf(String::isNotBlank) ?: return null
    return runCatching {
        String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
    }.getOrNull()
}

private fun postApprovalNotification(context: Context, key: String, title: String, text: String) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager.createNotificationChannel(
            NotificationChannel(
                APPROVAL_NOTIFICATION_CHANNEL,
                APPROVAL_NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }
    val openApp = PendingIntent.getActivity(
        context,
        key.hashCode(),
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = NotificationCompat.Builder(context, APPROVAL_NOTIFICATION_CHANNEL)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(openApp)
        .setAutoCancel(true)
        .setOnlyAlertOnce(true)
        .build()
    runCatching {
        NotificationManagerCompat.from(context).notify(key.hashCode(), notification)
    }.onFailure {
        Log.w(RUNTIME_TAG, "approval notification unavailable: ${it.message}")
    }
}

private fun BreakfastRightView.blockReason(): String? {
    if (allowBreakfast) return null
    return reason?.takeIf(String::isNotBlank) ?: "后台未提供具体理由"
}

private fun executeHttp(
    method: String,
    requestUrl: String,
    target: NetTarget,
    body: String?,
    setMessage: (String?) -> Unit
): Boolean {
    val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = target.connectTimeoutMs.toInt()
        readTimeout = target.readTimeoutMs.toInt()
        target.headers.forEach { (key, value) -> setRequestProperty(key, value) }
        if (body != null) {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
    }

    return try {
        if (body != null) {
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
            }
        }

        val httpOk = connection.responseCode in 200..299
        val responseText = readResponse(connection, httpOk)
        val businessCode = Regex("\\\"code\\\"\\s*:\\s*(\\d+)").find(responseText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        val responseMessage = Regex("\\\"msg\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").find(responseText)
            ?.groupValues
            ?.getOrNull(1)

        val ok = httpOk && (businessCode == null || businessCode == 200)
        if (!ok) setMessage(responseMessage ?: "HTTP ${connection.responseCode}")
        ok
    } finally {
        connection.disconnect()
    }
}

private fun readResponse(connection: HttpURLConnection, httpOk: Boolean): String {
    val stream = if (httpOk) connection.inputStream else connection.errorStream
    return stream?.bufferedReader(Charsets.UTF_8)?.use(BufferedReader::readText).orEmpty()
}

private const val LOGCAT_PAYLOAD_CHUNK_SIZE = 3_000

/** Logs every character without relying on Logcat's per-entry truncation limit. */
private fun logCompletePayload(tag: String, label: String, payload: String) {
    if (payload.isEmpty()) {
        Log.i(tag, "$label [empty]")
        return
    }
    val chunkCount = (payload.length + LOGCAT_PAYLOAD_CHUNK_SIZE - 1) /
        LOGCAT_PAYLOAD_CHUNK_SIZE
    payload.chunked(LOGCAT_PAYLOAD_CHUNK_SIZE).forEachIndexed { index, chunk ->
        Log.i(tag, "$label [${index + 1}/$chunkCount] $chunk")
    }
}

private fun executeRawHttp11Post(target: NetTarget, body: String): RawHttpResponse {
    require(target.scheme == "http") { "raw socket only supports http" }
    val cleanPath = if (target.path.startsWith("/")) target.path else "/${target.path}"
    val bodyBytes = body.toByteArray(Charsets.UTF_8)

    Socket().use { socket ->
        socket.connect(InetSocketAddress(target.host, target.port), target.connectTimeoutMs.toInt())
        socket.soTimeout = target.readTimeoutMs.toInt()
        val outputStream = socket.getOutputStream()
        val writer = outputStream.bufferedWriter(Charsets.ISO_8859_1)
        writer.append("POST $cleanPath HTTP/1.1\r\n")
        writer.append("Host: ${target.host}:${target.port}\r\n")
        writer.append("Content-Type: application/json; charset=utf-8\r\n")
        writer.append("Accept: application/json\r\n")
        writer.append("Connection: close\r\n")
        target.headers.forEach { (key, value) -> writer.append("$key: $value\r\n") }
        writer.append("Content-Length: ${bodyBytes.size}\r\n")
        writer.append("\r\n")
        writer.flush()
        outputStream.write(bodyBytes)
        outputStream.flush()

        val responseText = readRawHttpResponse(socket)
        val headerEnd = responseText.indexOf("\r\n\r\n")
        val headerText = if (headerEnd >= 0) responseText.substring(0, headerEnd) else responseText
        val rawBodyText = if (headerEnd >= 0) responseText.substring(headerEnd + 4) else ""
        val bodyText = if (Regex("(?i)transfer-encoding\\s*:\\s*chunked").containsMatchIn(headerText)) {
            decodeChunkedBody(rawBodyText)
        } else {
            rawBodyText
        }
        val statusCode = headerText.lineSequence()
            .firstOrNull()
            ?.split(" ")
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 0

        return RawHttpResponse(statusCode = statusCode, body = bodyText)
    }
}

private fun decodeChunkedBody(raw: String): String {
    val decoded = StringBuilder()
    var cursor = 0
    while (cursor < raw.length) {
        val lineEnd = raw.indexOf("\r\n", cursor)
        if (lineEnd < 0) throw IllegalArgumentException("invalid chunked response")
        val size = raw.substring(cursor, lineEnd)
            .substringBefore(';')
            .trim()
            .toIntOrNull(16)
            ?: throw IllegalArgumentException("invalid chunk size")
        cursor = lineEnd + 2
        if (size == 0) return decoded.toString()
        if (cursor + size > raw.length) throw IllegalArgumentException("truncated chunked response")
        decoded.append(raw, cursor, cursor + size)
        cursor += size
        if (!raw.startsWith("\r\n", cursor)) {
            throw IllegalArgumentException("invalid chunk terminator")
        }
        cursor += 2
    }
    throw IllegalArgumentException("missing final chunk")
}

private fun readRawHttpResponse(socket: Socket): String {
    val inputStream = socket.getInputStream()
    val buffer = ByteArrayOutputStream()
    val scratch = ByteArray(1024)
    var headerEnd = -1

    while (headerEnd < 0) {
        val count = inputStream.read(scratch)
        if (count < 0) break
        buffer.write(scratch, 0, count)
        headerEnd = buffer.toByteArray().indexOfHeaderEnd()
    }

    val bytesAfterHeader = if (headerEnd >= 0) buffer.size() - headerEnd - 4 else 0
    val headerText = if (headerEnd >= 0) {
        buffer.toByteArray().copyOfRange(0, headerEnd).toString(Charsets.ISO_8859_1)
    } else {
        buffer.toByteArray().toString(Charsets.ISO_8859_1)
    }
    val contentLength = Regex("(?i)content-length\\s*:\\s*(\\d+)")
        .find(headerText)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()

    if (contentLength != null) {
        val remaining = contentLength - bytesAfterHeader
        var unread = remaining.coerceAtLeast(0)
        while (unread > 0) {
            val count = inputStream.read(scratch, 0, minOf(scratch.size, unread))
            if (count < 0) break
            buffer.write(scratch, 0, count)
            unread -= count
        }
        return buffer.toByteArray().toString(Charsets.UTF_8)
    }

    while (true) {
        val count = inputStream.read(scratch)
        if (count < 0) break
        buffer.write(scratch, 0, count)
    }

    return buffer.toByteArray().toString(Charsets.UTF_8)
}

private fun ByteArray.indexOfHeaderEnd(): Int {
    for (index in 0..size - 4) {
        if (this[index] == '\r'.code.toByte() &&
            this[index + 1] == '\n'.code.toByte() &&
            this[index + 2] == '\r'.code.toByte() &&
            this[index + 3] == '\n'.code.toByte()
        ) {
            return index
        }
    }
    return -1
}

private fun parseBreakfastRight(json: String): BreakfastRightView? {
    val orderCode = json.stringField("order_code") ?: return null
    val examType = json.base64Field("exam_type_b64")
    val organizationName = json.base64Field("organization_name_b64")
    val examTotalPrice = json.base64PriceField("exam_total_price_b64")
    val avatarUrl = json.nullableStringField("avatar_url")
    return BreakfastRightView(
        orderCode = orderCode,
        cardNo = json.nullableStringField("card_no"),
        name = json.base64Field("name_b64") ?: "未知客户",
        sex = json.base64Field("sex_b64"),
        phone = json.nullableStringField("phone"),
        avatarUrl = avatarUrl,
        birthDate = json.nullableStringField("birth_date"),
        packageName = json.base64Field("package_name_b64"),
        examType = examType,
        organizationName = organizationName,
        examTotalPrice = examTotalPrice,
        hasBreakfast = json.booleanField("has_breakfast") ?: false,
        allowBreakfast = json.booleanField("allow_breakfast") ?: false,
        reason = json.base64Field("reason_b64")
    )
}

private fun NetTarget.resolveResourceUrl(value: String?): String? {
    val candidate = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching {
        val resolved = URI("$scheme://$host:$port/").resolve(candidate)
        resolved.toString().takeIf { resolved.scheme.equals("http", ignoreCase = true) ||
            resolved.scheme.equals("https", ignoreCase = true)
        }
    }.getOrNull()
}

private fun String.stringField(name: String): String? {
    return Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.jsonUnescape()
}

private fun String.nullableStringField(name: String): String? {
    return stringField(name)?.takeIf { it.isNotBlank() }
}

private fun String.base64Field(name: String): String? {
    val encoded = nullableStringField(name) ?: return null
    return runCatching {
        String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
    }.getOrNull()
}

private fun String.booleanField(name: String): Boolean? {
    return Regex("\\\"${Regex.escape(name)}\\\"\\s*:\\s*(true|false)")
        .find(this)
        ?.groupValues
        ?.getOrNull(1)
        ?.toBooleanStrictOrNull()
}

private fun String.base64PriceField(name: String): String? {
    return base64Field(name)?.takeIf {
        Regex("\\d+(?:\\.\\d{1,2})?").matches(it)
    }
}

private fun String.urlEncode(): String {
    return URLEncoder.encode(this, Charsets.UTF_8.name())
}

private fun Map<String, Any?>.toJson(): String {
    return entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
        val jsonValue = when (value) {
            null -> "null"
            is Number, is Boolean -> value.toString()
            else -> "\"${value.toString().jsonEscape()}\""
        }
        "\"${key.jsonEscape()}\":$jsonValue"
    }
}

private fun String.jsonEscape(): String {
    return replace("\\", "\\\\").replace("\"", "\\\"")
}

private fun String.jsonUnescape(): String {
    return replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\")
}