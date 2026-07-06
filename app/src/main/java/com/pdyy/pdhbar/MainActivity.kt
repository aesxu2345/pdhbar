package com.pdyy.pdhbar

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pdyy.pdhbar.runtime.SystemIOBoundService
import com.pdyy.pdhbar.runtime.std
import com.pdyy.pdhbar.ui.theme.MyApplicationTheme

private const val MAIN_TAG = "BreakfastMain"

class MainActivity : ComponentActivity() {
    private var systemIOBound = false

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
        std.run().barscanner.destroy()
        if (systemIOBound) {
            unbindService(systemIOConnection)
            systemIOBound = false
        }
        super.onDestroy()
    }
}