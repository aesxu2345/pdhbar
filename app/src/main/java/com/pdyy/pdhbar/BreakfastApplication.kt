package com.pdyy.pdhbar

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Local, bounded diagnostics. Never records order numbers, response bodies or signed URLs. */
class BreakfastApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Gecko services run this Application too. Only the main process owns shared diagnostics.
        val processName = if (Build.VERSION.SDK_INT >= 28) {
            getProcessName()
        } else {
            getSystemService(ActivityManager::class.java)?.runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        if (processName != packageName) return
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                writeRecord("last-java-failure.json", JSONObject().apply {
                    put("timestamp", System.currentTimeMillis())
                    put("pid", Process.myPid())
                    put("threadId", thread.id)
                    put("exceptionType", error.javaClass.name)
                    // Exception messages may contain patient data; store only frame locations.
                    put("frames", JSONArray(error.stackTrace.take(48).map { it.toString() }))
                })
            } catch (_: Throwable) {
                // Diagnostics must not interfere with Android's original fatal handler.
            } finally {
                if (previousHandler != null) previousHandler.uncaughtException(thread, error)
                else {
                    Process.killProcess(Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        }
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            try {
                val info = packageManager.getPackageInfo(packageName, 0)
                val report = JSONObject().apply {
                    put("capturedAt", System.currentTimeMillis())
                    put("pid", Process.myPid())
                    put("sdk", Build.VERSION.SDK_INT)
                    put("versionName", info.versionName)
                    put("lastUpdateTime", info.lastUpdateTime)
                    put("systemExitInfoSupported", Build.VERSION.SDK_INT >= 30)
                }
                if (Build.VERSION.SDK_INT >= 30) {
                    val manager = getSystemService(ActivityManager::class.java)
                    val records = JSONArray()
                    manager.getHistoricalProcessExitReasons(packageName, 0, 32).forEach { exit ->
                        records.put(JSONObject().apply {
                            put("timestamp", exit.timestamp)
                            put("process", exit.processName)
                            put("pid", exit.pid)
                            put("reason", exit.reason)
                            put("status", exit.status)
                            put("importance", exit.importance)
                            put("pssKb", exit.pss)
                            put("rssKb", exit.rss)
                        })
                    }
                    report.put("exits", records)
                }
                writeRecord("process-exits.json", report)
                Log.i("BreakfastExit", "Local exit diagnostics saved; sdk=${Build.VERSION.SDK_INT}, installedAt=${info.lastUpdateTime}")
            } catch (error: Exception) {
                Log.w("BreakfastExit", "Exit diagnostics unavailable: ${error.javaClass.simpleName}")
            } finally {
                executor.shutdown()
            }
        }
    }

    private fun writeRecord(name: String, record: JSONObject) {
        val directory = File(noBackupFilesDir, "diagnostics")
        check(directory.isDirectory || directory.mkdirs())
        val file = AtomicFile(File(directory, name))
        val output = file.startWrite()
        try {
            output.write(record.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (error: Throwable) {
            file.failWrite(output)
            throw error
        }
    }
}
