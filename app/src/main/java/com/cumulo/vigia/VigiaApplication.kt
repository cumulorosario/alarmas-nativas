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
        Log.i("VigiaApp", "Application starting")
        AlarmNotificationManager.createChannels(this)

        // Programar WorkManager watchdog (sobrevive a matar la app)
        AlarmPollingService.scheduleWorkManagerFallback(this)

        // Arrancar el servicio de foreground
        startPollingService()
    }

    private fun startPollingService() {
        try {
            val intent = Intent(this, AlarmPollingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.w("VigiaApp", "Could not start service: ${e.message}")
        }
    }
}
