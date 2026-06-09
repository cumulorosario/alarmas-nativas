package com.cumulo.vigia.service

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.cumulo.vigia.data.VigiaRepository
import com.cumulo.vigia.data.local.AlarmFilterStore
import com.cumulo.vigia.data.local.SessionStore
import com.cumulo.vigia.model.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver disparado por AlarmManager cada POLL_INTERVAL_MS.
 *
 * Ventajas sobre el Foreground Service con coroutine loop:
 * 1. Android recrea el proceso automáticamente para dispatchar broadcasts
 * 2. No depende de que el servicio esté vivo ni de state en memoria
 * 3. Adquiere y libera su propio WakeLock por cada ciclo
 * 4. El estado de alarmas notificadas persiste en SharedPreferences (sobrevive reinicios)
 * 5. setExactAndAllowWhileIdle() se puede usar correctamente porque no es un loop
 */
class AlarmPollingReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlarmPollingReceiver"
        const val ACTION_POLL = "com.cumulo.vigia.ACTION_POLL"
        const val POLL_INTERVAL_MS = 30_000L      // 30s — más conservador que 15s
        const val POLL_BACKOFF_MS  = 120_000L     // 2 min tras errores
        private const val PREFS_NAME = "vigia_notified_alarms"
        private const val KEY_NOTIFIED = "notified_ids"
        private const val KEY_ERROR_COUNT = "consecutive_errors"

        fun scheduleNextPoll(context: Context, delayMs: Long = POLL_INTERVAL_MS) {
            val intent = Intent(context, AlarmPollingReceiver::class.java).apply {
                action = ACTION_POLL
            }
            val pi = PendingIntent.getBroadcast(
                context, 42, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = SystemClock.elapsedRealtime() + delayMs

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
            Log.d(TAG, "Próximo poll en ${delayMs / 1000}s")
        }

        fun cancelPolls(context: Context) {
            val intent = Intent(context, AlarmPollingReceiver::class.java).apply {
                action = ACTION_POLL
            }
            val pi = PendingIntent.getBroadcast(
                context, 42, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            pi?.let {
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(it)
            }
        }

        private fun getPrefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        fun getNotifiedIds(context: Context): MutableSet<String> =
            getPrefs(context).getStringSet(KEY_NOTIFIED, emptySet())
                ?.toMutableSet() ?: mutableSetOf()

        fun saveNotifiedIds(context: Context, ids: Set<String>) {
            getPrefs(context).edit().putStringSet(KEY_NOTIFIED, ids).apply()
        }

        fun getErrorCount(context: Context): Int =
            getPrefs(context).getInt(KEY_ERROR_COUNT, 0)

        fun saveErrorCount(context: Context, count: Int) {
            getPrefs(context).edit().putInt(KEY_ERROR_COUNT, count).apply()
        }

        fun clearNotifiedIds(context: Context) {
            getPrefs(context).edit().remove(KEY_NOTIFIED).apply()
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_POLL) return

        Log.d(TAG, "Poll broadcast recibido")

        // Adquirir WakeLock ANTES de lanzar el coroutine
        // goAsync() + WakeLock garantiza que el proceso no muere durante la red call
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VigiaIndustrial:PollReceiverWakeLock"
        ).apply { acquire(60_000L) } // 60s máximo — timeout explícito

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                executePoll(context)
            } finally {
                // Liberar siempre, incluso si hay excepción
                try { if (wl.isHeld) wl.release() } catch (e: Exception) { }
                pendingResult.finish()
            }
        }
    }

    private suspend fun executePoll(context: Context) {
        val sessionStore = SessionStore(context)
        val session = try { sessionStore.getSession() } catch (e: Exception) {
            Log.e(TAG, "Error leyendo sesión: ${e.message}")
            scheduleNextPoll(context)
            return
        }

        if (!session.isLoggedIn) {
            Log.d(TAG, "Sin sesión activa")
            scheduleNextPoll(context)
            return
        }

        val errorCount = getErrorCount(context)
        val repository = VigiaRepository(sessionStore)
        val filterStore = AlarmFilterStore(context)

        when (val result = repository.getAlarms()) {
            is Result.Success -> {
                saveErrorCount(context, 0)
                processAlarms(context, result.data, filterStore)
                scheduleNextPoll(context, POLL_INTERVAL_MS)
            }
            is Result.Error -> {
                val newCount = errorCount + 1
                saveErrorCount(context, newCount)
                Log.w(TAG, "Error poll #$newCount: ${result.message}")

                // Limpiar IDs si hay error de auth — re-notificará al recuperar sesión
                val msg = result.message?.lowercase() ?: ""
                if (msg.contains("sesión") || msg.contains("token") ||
                    msg.contains("401") || msg.contains("unauthorized")) {
                    Log.i(TAG, "Error de auth — limpiando IDs notificados")
                    clearNotifiedIds(context)
                }

                // Backoff tras 3 errores consecutivos
                val delay = if (newCount >= 3) POLL_BACKOFF_MS else POLL_INTERVAL_MS
                scheduleNextPoll(context, delay)
            }
            else -> scheduleNextPoll(context)
        }
    }

    private suspend fun processAlarms(
        context: Context,
        alarms: List<com.cumulo.vigia.model.Alarm>,
        filterStore: AlarmFilterStore
    ) {
        val activeAlarms = alarms.filter { it.isActive && !it.isCleared }
        val activeIds    = activeAlarms.map { it.id.id }.toSet()

        // Cargar IDs ya notificados desde SharedPreferences (persiste entre reinicios)
        val notifiedIds = getNotifiedIds(context)

        // Limpiar IDs de alarmas que ya no están activas
        notifiedIds.retainAll(activeIds)

        val filters = filterStore.getFilters()

        activeAlarms.forEach { alarm ->
            if (alarm.id.id !in notifiedIds) {
                val isMuted = filters.any { f ->
                    f.muted && f.alarmType == alarm.type &&
                    alarm.originatorName.equals(f.deviceName, ignoreCase = true)
                }
                if (isMuted) {
                    Log.d(TAG, "Muteada: ${alarm.type}@${alarm.originatorName}")
                    notifiedIds.add(alarm.id.id) // Marcar como procesada igual
                } else {
                    Log.i(TAG, "ALERTA: ${alarm.id.id} sev=${alarm.severity}")
                    AlarmNotificationManager.showCriticalAlarm(context, alarm)
                    notifiedIds.add(alarm.id.id)
                }
            }
        }

        // Persistir los IDs actualizados
        saveNotifiedIds(context, notifiedIds)

        // Cancelar notificaciones de alarmas resueltas
        alarms.filter { it.isCleared }.forEach { alarm ->
            AlarmNotificationManager.cancelAlarmNotification(context, alarm)
        }
    }
}
