package com.cumulo.vigia.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.cumulo.vigia.data.VigiaRepository
import com.cumulo.vigia.data.local.AlarmFilterStore
import com.cumulo.vigia.data.local.SessionStore
import com.cumulo.vigia.model.Result
import kotlinx.coroutines.*

class AlarmPollingService : Service() {

    private val TAG = "AlarmPollingService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    private lateinit var repository: VigiaRepository
    private lateinit var sessionStore: SessionStore
    private lateinit var filterStore: AlarmFilterStore

    // IDs de alarmas ya notificadas — evita repetir la alerta en cada ciclo
    private val notifiedAlarmIds = mutableSetOf<String>()

    companion object {
        var isRunning = false
        const val POLL_INTERVAL_MS = 15_000L
    }

    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(applicationContext)
        repository   = VigiaRepository(sessionStore)
        filterStore  = AlarmFilterStore(applicationContext)

        AlarmNotificationManager.createChannels(this)
        val notification = AlarmNotificationManager.buildServiceNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(AlarmNotificationManager.NOTIF_SERVICE_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(AlarmNotificationManager.NOTIF_SERVICE_ID, notification)
        }

        isRunning = true
        Log.i(TAG, "Service started")
        startPolling()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive) {
                val session = sessionStore.getSession()
                if (session.isLoggedIn) pollAlarms()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollAlarms() {
        when (val result = repository.getAlarms()) {
            is Result.Success -> {
                val activeAlarms = result.data.filter { it.isActive && !it.isCleared }
                val activeIds    = activeAlarms.map { it.id.id }.toSet()

                // Limpiar IDs que ya no están activos
                notifiedAlarmIds.removeAll { id -> id !in activeIds }

                // Obtener filtros una sola vez por ciclo
                val filters = filterStore.getFilters()

                activeAlarms.forEach { alarm ->
                    if (alarm.id.id !in notifiedAlarmIds) {
                        notifiedAlarmIds.add(alarm.id.id)

                        // Verificar si está silenciada por nombre de dispositivo + tipo
                        val isMuted = filters.any { f ->
                            f.muted &&
                            f.alarmType == alarm.type &&
                            alarm.originatorName.equals(f.deviceName, ignoreCase = true)
                        }

                        if (isMuted) {
                            Log.d(TAG, "Alarm ${alarm.type} from ${alarm.originatorName} is muted — skipping")
                        } else {
                            Log.i(TAG, "New alarm: ${alarm.id.id} severity=${alarm.severity}")
                            AlarmNotificationManager.showCriticalAlarm(applicationContext, alarm)
                        }
                    }
                }

                // Cancelar notificaciones de alarmas ya resueltas
                result.data.filter { it.isCleared }.forEach { alarm ->
                    AlarmNotificationManager.cancelAlarmNotification(applicationContext, alarm)
                }
            }
            is Result.Error -> Log.w(TAG, "Poll error: ${result.message}")
            else -> {}
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::repository.isInitialized) {
            sessionStore = SessionStore(applicationContext)
            repository   = VigiaRepository(sessionStore)
            filterStore  = AlarmFilterStore(applicationContext)
        }
        if (pollingJob?.isActive != true) startPolling()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
        Log.i(TAG, "Service destroyed — scheduling restart")
        sendBroadcast(Intent("com.cumulo.vigia.RESTART_SERVICE"))
    }
}
