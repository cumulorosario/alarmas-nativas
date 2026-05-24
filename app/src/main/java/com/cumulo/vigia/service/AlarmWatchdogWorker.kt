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
 * Corre sin importar si hay red o no (sin NetworkType constraint).
 * 1. Reinicia el servicio si está caído.
 * 2. Si hay red, hace un poll directo para cubrir alarmas perdidas.
 */
class AlarmWatchdogWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val TAG = "AlarmWatchdogWorker"

    override suspend fun doWork(): androidx.work.ListenableWorker.Result {
        Log.i(TAG, "Watchdog ejecutando — servicio activo: ${AlarmPollingService.isRunning}")

        val sessionStore = SessionStore(context)
        val session      = sessionStore.getSession()

        if (!session.isLoggedIn) {
            Log.d(TAG, "Sin sesión — nada que hacer")
            return androidx.work.ListenableWorker.Result.success()
        }

        // 1. Reiniciar servicio si está caído
        if (!AlarmPollingService.isRunning) {
            Log.i(TAG, "Servicio caído — reiniciando")
            try {
                val intent = Intent(context, AlarmPollingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "No se pudo reiniciar el servicio: ${e.message}")
            }
        }

        // 2. Poll directo independiente — cubre el gap mientras el servicio estuvo caído
        // No usamos notifiedAlarmIds del servicio (distinto proceso/instancia)
        // por eso notificamos todas las alarmas activas que encontremos
        try {
            val repository  = VigiaRepository(sessionStore)
            val filterStore = AlarmFilterStore(context)
            val filters     = filterStore.getFilters()

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
                                Log.i(TAG, "Watchdog notificando: ${alarm.id.id} ${alarm.severity}")
                                AlarmNotificationManager.showCriticalAlarm(context, alarm)
                            }
                        }
                }
                is VigiaResult.Error -> Log.w(TAG, "Error en watchdog poll: ${result.message}")
                else -> {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Excepción en watchdog: ${e.message}")
        }

        return androidx.work.ListenableWorker.Result.success()
    }
}
