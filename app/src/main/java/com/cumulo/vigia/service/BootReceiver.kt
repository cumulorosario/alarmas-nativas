package com.cumulo.vigia.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i("BootReceiver", "Received: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "com.cumulo.vigia.RESTART_SERVICE" -> {
                startService(context)
            }
        }
    }

    private fun startService(context: Context) {
        val serviceIntent = Intent(context, AlarmPollingService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.i("BootReceiver", "AlarmPollingService started")
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to start service: ${e.message}")
        }
    }
}
