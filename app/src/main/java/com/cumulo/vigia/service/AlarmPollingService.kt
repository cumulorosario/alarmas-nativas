package com.cumulo.vigia.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.work.*
import com.cumulo.vigia.data.VigiaRepository
import com.cumulo.vigia.data.local.AlarmFilterStore
import com.cumulo.vigia.data.local.SessionStore
import com.cumulo.vigia.model.Result
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

class AlarmPollingService : Service() {

    private val TAG = "AlarmPollingService"
    // Scope con WakeLock propio para coroutines
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var repository: VigiaRepository
    private lateinit var sessionStore: SessionStore
    private lateinit var filterStore: AlarmFilterStore

    // WakeLock PARTIAL — mantiene CPU activo durante el poll
    private var wakeLock: PowerManager.WakeLock? = null

    private val notifiedAlarmIds = mutableSetOf<String>()
    private var consecutiveErrors = 0

    companion object {
        var isRunning = false
        const val POLL_INTERVAL_MS    = 15_000L   // 15s normal
        const val POLL_INTERVAL_BACKOFF = 60_000L  // 1min tras errores
        const val ACTION_POLL = "com.cumulo.vigia.ACTION_POLL"

        /**
         * Programa el próximo poll usando AlarmManager.setExactAndAllowWhileIdle()
         * Este es el ÚNICO mecanismo que funciona durante Doze Mode.
         */
        fun scheduleNextPoll(context: Context, delayMs: Long = POLL_INTERVAL_MS) {
            val intent = Intent(context, AlarmPollingService::class.java).apply {
                action = ACTION_POLL
            }
            val pi = PendingIntent.getService(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = SystemClock.elapsedRealtime() + delayMs

            when {
                // API 23+: setExactAndAllowWhileIdle garantiza ejecución en Doze
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ->
                    am.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                // API 19-22: setExact
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT ->
                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                // Fallback
                else ->
                    am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
            Log.d("AlarmPollingService", "Próximo poll en ${delayMs/1000}s")
        }

        fun cancelScheduledPolls(context: Context) {
            val intent = Intent(context, AlarmPollingService::class.java).apply {
                action = ACTION_POLL
            }
            val pi = PendingIntent.getService(
                context, 0, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pi != null) {
                val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                am.cancel(pi)
            }
        }

        fun scheduleWorkManagerFallback(context: Context) {
            // WorkManager como red de seguridad adicional — sin constraints
            val request = PeriodicWorkRequestBuilder<AlarmWatchdogWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(3, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "vigia_watchdog",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(applicationContext)
        repository   = VigiaRepository(sessionStore)
        filterStore  = AlarmFilterStore(applicationContext)

        // WakeLock PARTIAL — mantiene CPU activo, funciona con pantalla bloqueada
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VigiaIndustrial:PollingWakeLock")
            .apply { acquire() }

        AlarmNotificationManager.createChannels(this)
        val notification = AlarmNotificationManager.buildServiceNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(AlarmNotificationManager.NOTIF_SERVICE_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(AlarmNotificationManager.NOTIF_SERVICE_ID, notification)
        }

        isRunning = true
        scheduleWorkManagerFallback(applicationContext)
        Log.i(TAG, "Servicio iniciado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Reasegurar recursos si el servicio fue recreado
        if (!::repository.isInitialized) {
            sessionStore = SessionStore(applicationContext)
            repository   = VigiaRepository(sessionStore)
            filterStore  = AlarmFilterStore(applicationContext)
        }
        if (wakeLock?.isHeld == false) {
            try { wakeLock?.acquire() } catch (e: Exception) { }
        }

        val pollAction = intent?.action == ACTION_POLL || intent?.action == null

        if (pollAction) {
            // Ejecutar poll en coroutine y programar el siguiente al terminar
            scope.launch {
                val nextDelay = executePollCycle()
                // Programar el próximo DENTRO del WakeLock activo
                scheduleNextPoll(applicationContext, nextDelay)
            }
        }

        return START_STICKY
    }

    private suspend fun executePollCycle(): Long {
        val session = try { sessionStore.getSession() } catch (e: Exception) { null }

        if (session?.isLoggedIn != true) {
            Log.d(TAG, "Sin sesión activa — esperando")
            return POLL_INTERVAL_MS
        }

        return try {
            when (val result = repository.getAlarms()) {
                is Result.Success -> {
                    consecutiveErrors = 0
                    processAlarms(result.data)
                    POLL_INTERVAL_MS
                }
                is Result.Error -> {
                    consecutiveErrors++
                    Log.w(TAG, "Error poll #$consecutiveErrors: ${result.message}")

                    // Error de autenticación → limpiar IDs para re-notificar al renovar
                    if (result.message?.contains("sesión", ignoreCase = true) == true ||
                        result.message?.contains("token", ignoreCase = true) == true) {
                        Log.i(TAG, "Error de auth — limpiando IDs notificados")
                        notifiedAlarmIds.clear()
                    }

                    if (consecutiveErrors >= 3) POLL_INTERVAL_BACKOFF else POLL_INTERVAL_MS
                }
                else -> POLL_INTERVAL_MS
            }
        } catch (e: Exception) {
            consecutiveErrors++
            Log.e(TAG, "Excepción en poll: ${e.message}")
            POLL_INTERVAL_MS
        }
    }

    private suspend fun processAlarms(alarms: List<com.cumulo.vigia.model.Alarm>) {
        val activeAlarms = alarms.filter { it.isActive && !it.isCleared }
        val activeIds    = activeAlarms.map { it.id.id }.toSet()

        notifiedAlarmIds.removeAll { id -> id !in activeIds }

        val filters = filterStore.getFilters()

        activeAlarms.forEach { alarm ->
            if (alarm.id.id !in notifiedAlarmIds) {
                notifiedAlarmIds.add(alarm.id.id)
                val isMuted = filters.any { f ->
                    f.muted && f.alarmType == alarm.type &&
                    alarm.originatorName.equals(f.deviceName, ignoreCase = true)
                }
                if (isMuted) {
                    Log.d(TAG, "Muteada: ${alarm.type}@${alarm.originatorName}")
                } else {
                    Log.i(TAG, "ALERTA: ${alarm.id.id} ${alarm.severity}")
                    AlarmNotificationManager.showCriticalAlarm(applicationContext, alarm)
                }
            }
        }

        alarms.filter { it.isCleared }.forEach { alarm ->
            AlarmNotificationManager.cancelAlarmNotification(applicationContext, alarm)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "App cerrada desde recientes")
        // Asegurar que el próximo poll sigue programado
        scheduleNextPoll(applicationContext, 3_000L)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        Log.i(TAG, "Servicio destruido — AlarmManager mantiene el polling")
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { }
        scope.cancel()
        // NO cancelar los polls de AlarmManager — siguen funcionando sin el servicio
        // El próximo disparo del AlarmManager reiniciará el servicio automáticamente
        super.onDestroy()
    }
}
