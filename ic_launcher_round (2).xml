package com.cumulo.vigia

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import com.cumulo.vigia.service.AlarmNotificationManager
import com.cumulo.vigia.service.AlarmPollingService

class VigiaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AlarmNotificationManager.createChannels(this)
        AlarmPollingService.scheduleWorkManagerFallback(this)
        try {
            val intent = Intent(this, AlarmPollingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(intent)
            else
                startService(intent)
        } catch (e: Exception) {
            Log.w("VigiaApp", "Error iniciando servicio: ${e.message}")
        }
    }
}
