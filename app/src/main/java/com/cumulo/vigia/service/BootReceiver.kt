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
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.i("BootReceiver", "Boot completado — iniciando servicio y programando polls")
                startService(context)
                // Programar el primer poll inmediato via AlarmManager
                AlarmPollingService.scheduleNextPoll(context, 5_000L)
                // WorkManager watchdog como respaldo
                AlarmPollingService.scheduleWorkManagerFallback(context)
            }
        }
    }

    private fun startService(context: Context) {
        try {
            val intent = Intent(context, AlarmPollingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: Exception) {
            Log.e("BootReceiver", "Error iniciando servicio: ${e.message}")
        }
    }
}
