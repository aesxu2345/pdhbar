package com.pdyy.pdhbar

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.pdyy.pdhbar.runtime.SystemIOBoundService
import com.pdyy.pdhbar.runtime.std
import com.pdyy.pdhbar.ui.theme.MyApplicationTheme

private const val MAIN_TAG = "BreakfastMain"

class MainActivity : ComponentActivity() {
    private var systemIOBound = false
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val onCreateStd: () -> std = {
        std.onCreate(applicationContext)
    }

    private val systemIOConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            service?.let(std::attachSystemIO)
            systemIOBound = service != null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            systemIOBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onCreateStd()
        bindService(
            Intent(this, SystemIOBoundService::class.java),
            systemIOConnection,
            Context.BIND_AUTO_CREATE
        )
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                BreakfastApp()
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (std.isCreated() && std.run().scannerGunMode) {
            Log.d(
                MAIN_TAG,
                "dispatch key action=${event.action} code=${event.keyCode} scanCode=${event.scanCode} repeat=${event.repeatCount} unicode=${event.unicodeChar} chars=${event.characters ?: ""}"
            )
        }
        if (std.isCreated() && std.run().onScannerGunKeyEvent(this, event)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        std.save(applicationContext)
        std.run().barscanner.start()
    }

    override fun onPause() {
        std.save(applicationContext)
        std.run().barscanner.stop()
        super.onPause()
    }

    override fun onDestroy() {
        std.save(applicationContext)
        // std/WaitGroup are process-scoped. Activity may be destroyed and recreated while the
        // process remains alive; closing the queue here leaves the retained singleton unusable.
        std.run().barscanner.stop()
        if (systemIOBound) {
            unbindService(systemIOConnection)
            systemIOBound = false
        }
        super.onDestroy()
    }
}