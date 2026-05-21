package com.cumulo.vigia

import android.app.Application
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.cumulo.vigia.service.AlarmNotificationManager
import com.cumulo.vigia.service.AlarmPollingService
import com.cumulo.vigia.service.BootReceiver

class VigiaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i("VigiaApp", "Application starting")
        AlarmNotificationManager.createChannels(this)

        // Register service restart receiver
        val restartReceiver = BootReceiver()
        val filter = IntentFilter("com.cumulo.vigia.RESTART_SERVICE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(restartReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(restartReceiver, filter)
        }

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
            Log.w("VigiaApp", "Could not start service immediately: ${e.message}")
        }
    }
}
