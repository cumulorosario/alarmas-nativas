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
        Log.i("VigiaApp", "Aplicación iniciando")
        AlarmNotificationManager.createChannels(this)

        // WorkManager watchdog como red de seguridad
        AlarmPollingService.scheduleWorkManagerFallback(this)

        // Iniciar el servicio foreground
        startPollingService()

        // Programar el primer poll via AlarmManager (inmune a Doze)
        // El servicio programa el siguiente al terminar cada poll
        AlarmPollingService.scheduleNextPoll(this, 2_000L)
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
            Log.w("VigiaApp", "No se pudo iniciar el servicio: ${e.message}")
        }
    }
}
