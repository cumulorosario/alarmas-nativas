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
 * WorkManager Worker — ejecutado cada 15 minutos por el SO.
 * Garantiza funcionamiento incluso si el proceso fue matado.
 *
 * Responsabilidades:
 * 1. Reiniciar ForegroundService si está caído
 * 2. Hacer un poll HTTP propio (independiente del servicio)
 * 3. Notificar alarmas activas no notificadas
 */
class AlarmWatchdogWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i("AlarmWatchdogWorker", "Watchdog ejecutando — servicio: ${AlarmPollingService.isRunning}")

        val sessionStore = SessionStore(context)
        val session = try { sessionStore.getSession() } catch (e: Exception) {
            return Result.success()
        }
        if (!session.isLoggedIn) return Result.success()

        // 1. Reiniciar servicio si está caído
        if (!AlarmPollingService.isRunning) {
            Log.i("AlarmWatchdogWorker", "Servicio caído — reiniciando")
            try {
                val intent = Intent(context, AlarmPollingService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(intent)
                else
                    context.startService(intent)
            } catch (e: Exception) {
                Log.e("AlarmWatchdogWorker", "Error reiniciando: ${e.message}")
            }
        }

        // 2. Poll HTTP independiente — no depende del estado del servicio
        try {
            val repository  = VigiaRepository(sessionStore)
            val filterStore = AlarmFilterStore(context)
            val filters     = filterStore.getFilters()
            val notifiedIds = AlarmPollingReceiver.getNotifiedIds(context).toMutableSet()

            when (val result = repository.getAlarms()) {
                is VigiaResult.Success -> {
                    val activeAlarms = result.data.filter { it.isActive && !it.isCleared }
                    val activeIds    = activeAlarms.map { it.id.id }.toSet()
                    notifiedIds.retainAll(activeIds)

                    var newNotifications = 0
                    activeAlarms.forEach { alarm ->
                        if (alarm.id.id !in notifiedIds) {
                            val isMuted = filters.any { f ->
                                f.muted && f.alarmType == alarm.type &&
                                alarm.originatorName.equals(f.deviceName, ignoreCase = true)
                            }
                            if (!isMuted) {
                                AlarmNotificationManager.showCriticalAlarm(context, alarm)
                                newNotifications++
                            }
                            notifiedIds.add(alarm.id.id)
                        }
                    }
                    AlarmPollingReceiver.saveNotifiedIds(context, notifiedIds)
                    Log.i("AlarmWatchdogWorker", "Poll OK — ${activeAlarms.size} activas, $newNotifications nuevas")
                }
                is VigiaResult.Error ->
                    Log.w("AlarmWatchdogWorker", "Poll error: ${result.message}")
                else -> {}
            }
        } catch (e: Exception) {
            Log.e("AlarmWatchdogWorker", "Excepción: ${e.message}")
        }

        return Result.success()
    }
}
