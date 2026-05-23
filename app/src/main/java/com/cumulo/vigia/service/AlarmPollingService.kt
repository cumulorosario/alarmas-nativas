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

    // WakeLock persistente — mantiene el CPU activo mientras pollea
    private var wakeLock: PowerManager.WakeLock? = null

    // IDs de alarmas ya notificadas en esta sesión del servicio
    private val notifiedAlarmIds = mutableSetOf<String>()

    companion object {
        var isRunning = false
        const val POLL_INTERVAL_MS = 15_000L

        fun scheduleWorkManagerFallback(context: android.content.Context) {
            // WorkManager como red de seguridad: si el servicio muere,
            // WorkManager lo resucita cada 15 minutos (mínimo permitido)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<AlarmWatchdogWorker>(
                15, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.MINUTES)
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

        // WakeLock PARTIAL — mantiene CPU despierto, NO necesita permiso especial
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VigiaIndustrial:PollingWakeLock"
        ).apply { acquire() } // Sin timeout — lo liberamos en onDestroy

        AlarmNotificationManager.createChannels(this)
        val notification = AlarmNotificationManager.buildServiceNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                AlarmNotificationManager.NOTIF_SERVICE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(AlarmNotificationManager.NOTIF_SERVICE_ID, notification)
        }

        isRunning = true
        Log.i(TAG, "Service started — scheduling WorkManager watchdog")
        scheduleWorkManagerFallback(applicationContext)
        startPolling()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    val session = sessionStore.getSession()
                    if (session.isLoggedIn) {
                        pollAlarms()
                    } else {
                        Log.d(TAG, "Not logged in — skipping poll")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected poll error: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollAlarms() {
        when (val result = repository.getAlarms()) {
            is Result.Success -> {
                val activeAlarms = result.data.filter { it.isActive && !it.isCleared }
                val activeIds    = activeAlarms.map { it.id.id }.toSet()

                // Limpiar IDs que ya no están activos para que si vuelven se re-notifiquen
                notifiedAlarmIds.removeAll { id -> id !in activeIds }

                val filters = filterStore.getFilters()

                activeAlarms.forEach { alarm ->
                    if (alarm.id.id !in notifiedAlarmIds) {
                        notifiedAlarmIds.add(alarm.id.id)

                        val isMuted = filters.any { f ->
                            f.muted &&
                            f.alarmType == alarm.type &&
                            alarm.originatorName.equals(f.deviceName, ignoreCase = true)
                        }

                        if (isMuted) {
                            Log.d(TAG, "Alarm ${alarm.type}@${alarm.originatorName} muted — skip")
                        } else {
                            Log.i(TAG, "ALERT: ${alarm.id.id} severity=${alarm.severity}")
                            AlarmNotificationManager.showCriticalAlarm(applicationContext, alarm)
                        }
                    }
                }

                // Cancelar notificaciones de alarmas resueltas
                result.data.filter { it.isCleared }.forEach { alarm ->
                    AlarmNotificationManager.cancelAlarmNotification(applicationContext, alarm)
                }
            }
            is Result.Error -> Log.w(TAG, "Poll error: ${result.message}")
            else -> {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        if (!::repository.isInitialized) {
            sessionStore = SessionStore(applicationContext)
            repository   = VigiaRepository(sessionStore)
            filterStore  = AlarmFilterStore(applicationContext)
        }
        // Reasegurar WakeLock si fue liberado
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire()
        }
        if (pollingJob?.isActive != true) {
            Log.i(TAG, "Polling was stopped — restarting")
            startPolling()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // El usuario cerró la app desde recientes — reiniciar servicio
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "Task removed — restarting service via alarm")
        val restartIntent = Intent(applicationContext, AlarmPollingService::class.java)
        val pi = android.app.PendingIntent.getService(
            applicationContext, 1, restartIntent,
            android.app.PendingIntent.FLAG_ONE_SHOT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        am.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 2000L, pi)
    }

    override fun onDestroy() {
        isRunning = false
        Log.i(TAG, "Service destroyed")
        // Liberar WakeLock antes de cancelar el scope
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { }
        scope.cancel()
        // El WorkManager watchdog se encargará de reiniciar si es necesario
        super.onDestroy()
    }
}
