package com.cumulo.vigia.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.cumulo.vigia.data.VigiaRepository
import com.cumulo.vigia.data.local.AlarmFilterStore
import com.cumulo.vigia.data.local.SessionStore
import com.cumulo.vigia.model.Result as VigiaResult

/**
 * WorkManager worker — garantizado por el SO cada ~15 minutos.
 * 1. Reinicia el ForegroundService si está caído.
 * 2. Hace un poll directo y notifica alarmas activas.
 */
class AlarmWatchdogWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val TAG = "AlarmWatchdogWorker"

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        Log.i(TAG, "Watchdog running — service isRunning=${AlarmPollingService.isRunning}")

        val sessionStore = SessionStore(context)
        val session = sessionStore.getSession()

        if (!session.isLoggedIn) {
            Log.d(TAG, "Not logged in — skipping")
            return androidx.work.ListenableWorker.Result.success()
        }

        // 1. Reiniciar el servicio si está caído
        if (!AlarmPollingService.isRunning) {
            Log.i(TAG, "Service not running — restarting")
            try {
                val intent = Intent(context, AlarmPollingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not restart service: ${e.message}")
            }
        }

        // 2. Poll directo — notifica alarmas que puedan haber llegado mientras el servicio estuvo caído
        try {
            val repository = VigiaRepository(sessionStore)
            val filterStore = AlarmFilterStore(context)
            val filters = filterStore.getFilters()

            when (val result = repository.getAlarms()) {
                is VigiaResult.Success -> {
                    result.data
                        .filter { it.isActive && !it.isCleared }
                        .forEach { alarm ->
                            val isMuted = filters.any { f ->
                                f.muted && f.alarmType == alarm.type &&
                                alarm.originatorName.equals(f.deviceName, ignoreCase = true)
                            }
                            if (!isMuted) {
                                Log.i(TAG, "Watchdog notifying: ${alarm.id.id} ${alarm.severity}")
                                AlarmNotificationManager.showCriticalAlarm(context, alarm)
                            }
                        }
                }
                is VigiaResult.Error -> Log.w(TAG, "Poll error: ${result.message}")
                else -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Watchdog error: ${e.message}")
        }

        return androidx.work.ListenableWorker.Result.success()
    }
}
