package com.cumulo.vigia.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.cumulo.vigia.data.VigiaRepository
import com.cumulo.vigia.data.local.SessionStore
import com.cumulo.vigia.model.Alarm
import com.cumulo.vigia.model.Result
import kotlinx.coroutines.*

class AlarmPollingService : Service() {

    private val TAG = "AlarmPollingService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null

    private lateinit var repository: VigiaRepository
    private lateinit var sessionStore: SessionStore

    // Track already-notified alarms to avoid repeated alerts
    private val notifiedAlarmIds = mutableSetOf<String>()

    companion object {
        var isRunning = false
        const val POLL_INTERVAL_MS = 15_000L // 15 seconds
    }

    override fun onCreate() {
        super.onCreate()
        sessionStore = SessionStore(applicationContext)
        repository = VigiaRepository(sessionStore)

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
                if (session.isLoggedIn) {
                    pollAlarms()
                } else {
                    Log.d(TAG, "Not logged in, skipping poll")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollAlarms() {
        when (val result = repository.getAlarms()) {
            is Result.Success -> {
                val activeAlarms = result.data.filter { it.isActive && !it.isCleared }

                // Remove notifications for alarms that are no longer active
                val activeIds = activeAlarms.map { it.id.id }.toSet()
                notifiedAlarmIds.removeAll { id -> id !in activeIds }

                // Notify new alarms
                activeAlarms.forEach { alarm ->
                    if (alarm.id.id !in notifiedAlarmIds) {
                        Log.i(TAG, "New alarm detected: ${alarm.id.id} severity=${alarm.severity}")
                        notifiedAlarmIds.add(alarm.id.id)
                        AlarmNotificationManager.showCriticalAlarm(applicationContext, alarm)
                    }
                }

                // Cancel notifications for resolved alarms
                result.data.filter { it.isCleared }.forEach { alarm ->
                    AlarmNotificationManager.cancelAlarmNotification(applicationContext, alarm)
                }
            }
            is Result.Error -> {
                Log.w(TAG, "Poll error: ${result.message}")
            }
            else -> {}
        }
    }

    fun clearNotifiedAlarm(alarmId: String) {
        notifiedAlarmIds.remove(alarmId)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand received")
        if (!::repository.isInitialized) {
            sessionStore = SessionStore(applicationContext)
            repository = VigiaRepository(sessionStore)
        }
        if (pollingJob?.isActive != true) {
            startPolling()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
        pollingJob?.cancel()
        Log.i(TAG, "Service destroyed - scheduling restart")
        // Self-restart via broadcast
        sendBroadcast(Intent("com.cumulo.vigia.RESTART_SERVICE"))
    }
}
