package com.cumulo.vigia.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON",
                "com.htc.intent.action.QUICKBOOT_POWERON")) return

        Log.i("BootReceiver", "Boot completado — iniciando servicio")
        try {
            val svcIntent = Intent(context, AlarmPollingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(svcIntent)
            else
                context.startService(svcIntent)
        } catch (e: Exception) {
            Log.e("BootReceiver", "Error: ${e.message}")
        }
        AlarmPollingService.scheduleWorkManagerFallback(context)
    }
}
