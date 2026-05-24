package com.cumulo.vigia.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    private lateinit var repository: VigiaRepository
    private lateinit var sessionStore: SessionStore
    private lateinit var filterStore: AlarmFilterStore

    private var wakeLock: PowerManager.WakeLock? = null

    // IDs ya notificados. Se limpia cuando hay un error de autenticación
    // para que al renovar el token se re-notifiquen las alarmas activas.
    private val notifiedAlarmIds = mutableSetOf<String>()

    // Contador de errores consecutivos — para detectar loop de fallos
    private var consecutiveErrors = 0
    private val MAX_CONSECUTIVE_ERRORS = 3

    companion object {
        var isRunning = false
        const val POLL_INTERVAL_MS = 15_000L
        const val POLL_INTERVAL_BACKOFF_MS = 60_000L // 1 min tras errores repetidos

        fun scheduleWorkManagerFallback(context: android.content.Context) {
            // Sin NetworkType constraint — el watchdog corre aunque no haya red,
            // así puede reiniciar el servicio incluso offline
            val request = PeriodicWorkRequestBuilder<AlarmWatchdogWorker>(15, TimeUnit.MINUTES)
                .setInitialDelay(2, TimeUnit.MINUTES)
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

        // PARTIAL_WAKE_LOCK: mantiene el CPU activo con pantalla bloqueada
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VigiaIndustrial:PollingWakeLock"
        ).apply { acquire() }

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
        startPolling()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                val session = try { sessionStore.getSession() } catch (e: Exception) { null }

                if (session?.isLoggedIn == true) {
                    val interval = pollAlarms()
                    delay(interval)
                } else {
                    Log.d(TAG, "Sin sesión activa — esperando")
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    /**
     * Ejecuta un ciclo de polling.
     * Retorna el intervalo de espera hasta el próximo ciclo:
     * - Normal: 15 segundos
     * - Backoff: 60 segundos si hay errores repetidos (no inundar el servidor)
     */
    private suspend fun pollAlarms(): Long {
        return try {
            when (val result = repository.getAlarms()) {
                is Result.Success -> {
                    consecutiveErrors = 0 // Reset al tener éxito
                    processAlarms(result.data)
                    POLL_INTERVAL_MS
                }
                is Result.Error -> {
                    consecutiveErrors++
                    Log.w(TAG, "Error en poll ($consecutiveErrors): ${result.message}")

                    // Si hubo error de autenticación, limpiar notifiedAlarmIds
                    // para que al renovar el token se re-notifiquen alarmas activas
                    if (result.message?.contains("401") == true ||
                        result.message?.contains("sesión", ignoreCase = true) == true ||
                        result.message?.contains("token", ignoreCase = true) == true) {
                        Log.i(TAG, "Error de autenticación — limpiando IDs notificados")
                        notifiedAlarmIds.clear()
                    }

                    // Backoff exponencial simple tras errores repetidos
                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) POLL_INTERVAL_BACKOFF_MS
                    else POLL_INTERVAL_MS
                }
                else -> POLL_INTERVAL_MS
            }
        } catch (e: Exception) {
            consecutiveErrors++
            Log.e(TAG, "Excepción inesperada en poll: ${e.message}")
            POLL_INTERVAL_MS
        }
    }

    private suspend fun processAlarms(alarms: List<com.cumulo.vigia.model.Alarm>) {
        val activeAlarms = alarms.filter { it.isActive && !it.isCleared }
        val activeIds    = activeAlarms.map { it.id.id }.toSet()

        // Remover IDs de alarmas que ya no están activas
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
                    Log.d(TAG, "Alarma muteada: ${alarm.type}@${alarm.originatorName}")
                } else {
                    Log.i(TAG, "Notificando alarma: ${alarm.id.id} ${alarm.severity}")
                    AlarmNotificationManager.showCriticalAlarm(applicationContext, alarm)
                }
            }
        }

        // Cancelar notificaciones de alarmas resueltas
        alarms.filter { it.isCleared }.forEach { alarm ->
            AlarmNotificationManager.cancelAlarmNotification(applicationContext, alarm)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::repository.isInitialized) {
            sessionStore = SessionStore(applicationContext)
            repository   = VigiaRepository(sessionStore)
            filterStore  = AlarmFilterStore(applicationContext)
        }
        if (wakeLock?.isHeld == false) {
            try { wakeLock?.acquire() } catch (e: Exception) { }
        }
        if (pollingJob?.isActive != true) {
            Log.i(TAG, "Polling reiniciado desde onStartCommand")
            notifiedAlarmIds.clear() // Reset para re-notificar alarmas activas
            startPolling()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "App cerrada — programando reinicio del servicio")
        val pi = android.app.PendingIntent.getService(
            applicationContext, 1,
            Intent(applicationContext, AlarmPollingService::class.java),
            android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        (getSystemService(ALARM_SERVICE) as android.app.AlarmManager).set(
            android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 3_000L,
            pi
        )
    }

    override fun onDestroy() {
        isRunning = false
        Log.i(TAG, "Servicio destruido")
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { }
        scope.cancel()
        super.onDestroy()
    }
}
