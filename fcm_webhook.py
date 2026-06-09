package com.cumulo.vigia.service

import android.app.Service
import android.content.Context
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
import com.cumulo.vigia.model.Alarm
import com.cumulo.vigia.model.Result
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/**
 * Foreground Service con conexión WebSocket persistente a ThingsBoard.
 *
 * Arquitectura:
 * 1. WebSocket — recibe alarmas en tiempo real (push). Principal.
 * 2. Polling HTTP de respaldo — cada 5 minutos para cubrir alarmas perdidas
 *    durante reconexiones WebSocket o brechas de red.
 * 3. WorkManager watchdog — cada 15 minutos verifica que el servicio esté vivo.
 *
 * El WakeLock PARTIAL mantiene el CPU activo para el WebSocket.
 * La reconexión WebSocket es automática con backoff exponencial.
 */
class AlarmPollingService : Service() {

    private val TAG = "AlarmPollingService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var sessionStore: SessionStore
    private lateinit var repository: VigiaRepository
    private lateinit var filterStore: AlarmFilterStore

    private var wakeLock: PowerManager.WakeLock? = null
    private var webSocket: ThingsBoardWebSocket? = null
    private var reconnectJob: Job? = null
    private var backupPollJob: Job? = null

    // Estado persistido en SharedPreferences — sobrevive reinicios del servicio
    private val notifiedIds get() = AlarmPollingReceiver.getNotifiedIds(this)
    private fun saveNotifiedIds(ids: Set<String>) = AlarmPollingReceiver.saveNotifiedIds(this, ids)

    private var reconnectDelay = RECONNECT_INITIAL_MS
    private var wsConnected = false

    companion object {
        var isRunning = false
        const val RECONNECT_INITIAL_MS = 5_000L
        const val RECONNECT_MAX_MS     = 120_000L  // 2 minutos máximo
        const val BACKUP_POLL_MS       = 300_000L  // 5 minutos de backup poll

        fun scheduleWorkManagerFallback(context: Context) {
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

        // WakeLock PARTIAL — mantiene CPU activo para el WebSocket
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VigiaIndustrial:WsWakeLock")
            .apply { acquire(10 * 60 * 60 * 1000L) } // 10 horas máximo con timeout explícito

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

        scope.launch { startWebSocket() }
        startBackupPolling()

        Log.i(TAG, "Servicio iniciado")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!::sessionStore.isInitialized) {
            sessionStore = SessionStore(applicationContext)
            repository   = VigiaRepository(sessionStore)
            filterStore  = AlarmFilterStore(applicationContext)
        }
        // Renovar WakeLock si venció
        if (wakeLock?.isHeld == false) {
            try { wakeLock?.acquire(10 * 60 * 60 * 1000L) } catch (e: Exception) { }
        }
        // Reconectar si no hay WebSocket activo
        if (!wsConnected) {
            scope.launch { startWebSocket() }
        }
        return START_STICKY
    }

    // ─── WebSocket ────────────────────────────────────────────────────────────

    private suspend fun startWebSocket() {
        val session = try { sessionStore.getSession() } catch (e: Exception) { return }
        if (!session.isLoggedIn) {
            Log.d(TAG, "Sin sesión — esperando login para WebSocket")
            return
        }

        Log.i(TAG, "Iniciando WebSocket")
        webSocket?.disconnect()

        webSocket = ThingsBoardWebSocket(
            baseUrl      = session.baseUrl,
            token        = session.token,
            tenantId     = session.tenantId,
            customerId   = session.customerId,
            onAlarm      = { alarm -> scope.launch { handleIncomingAlarm(alarm) } },
            onConnected  = {
                wsConnected = true
                reconnectDelay = RECONNECT_INITIAL_MS // Reset backoff al conectar
                Log.i(TAG, "WebSocket conectado — push de alarmas activo")
            },
            onDisconnected = { reason ->
                wsConnected = false
                Log.w(TAG, "WebSocket desconectado: $reason — reconectando en ${reconnectDelay/1000}s")
                scheduleReconnect()
            }
        )
        webSocket?.connect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(reconnectDelay)
            // Backoff exponencial: 5s → 10s → 20s → ... → 120s
            reconnectDelay = minOf(reconnectDelay * 2, RECONNECT_MAX_MS)

            // Si el token puede haber expirado, renovarlo antes de reconectar
            val session = try { sessionStore.getSession() } catch (e: Exception) { return@launch }
            if (session.isLoggedIn) {
                startWebSocket()
            }
        }
    }

    private suspend fun handleIncomingAlarm(alarm: Alarm) {
        val current = notifiedIds.toMutableSet()

        if (alarm.id.id in current) return // Ya notificada

        val filters = filterStore.getFilters()
        val isMuted = filters.any { f ->
            f.muted && f.alarmType == alarm.type &&
            alarm.originatorName.equals(f.deviceName, ignoreCase = true)
        }

        if (!isMuted) {
            Log.i(TAG, "NUEVA ALARMA via WS: ${alarm.id.id} ${alarm.severity}")
            AlarmNotificationManager.showCriticalAlarm(applicationContext, alarm)
        }
        current.add(alarm.id.id)
        saveNotifiedIds(current)
    }

    // ─── Backup polling (HTTP) ────────────────────────────────────────────────

    /**
     * Polling de respaldo cada 5 minutos.
     * Cubre alarmas que pudieran haberse perdido durante brechas de WebSocket.
     * También limpia notificaciones de alarmas resueltas.
     */
    private fun startBackupPolling() {
        backupPollJob?.cancel()
        backupPollJob = scope.launch {
            delay(30_000L) // Esperar 30s antes del primer backup poll
            while (isActive) {
                runBackupPoll()
                delay(BACKUP_POLL_MS)
            }
        }
    }

    private suspend fun runBackupPoll() {
        Log.d(TAG, "Backup poll HTTP")
        val session = try { sessionStore.getSession() } catch (e: Exception) { return }
        if (!session.isLoggedIn) return

        when (val result = repository.getAlarms()) {
            is Result.Success -> {
                val activeAlarms = result.data.filter { it.isActive && !it.isCleared }
                val activeIds    = activeAlarms.map { it.id.id }.toSet()
                val filters      = filterStore.getFilters()
                val current      = notifiedIds.toMutableSet()

                // Limpiar IDs de alarmas ya no activas
                current.retainAll(activeIds)

                activeAlarms.forEach { alarm ->
                    if (alarm.id.id !in current) {
                        val isMuted = filters.any { f ->
                            f.muted && f.alarmType == alarm.type &&
                            alarm.originatorName.equals(f.deviceName, ignoreCase = true)
                        }
                        if (!isMuted) {
                            Log.i(TAG, "Alarma perdida detectada en backup poll: ${alarm.id.id}")
                            AlarmNotificationManager.showCriticalAlarm(applicationContext, alarm)
                        }
                        current.add(alarm.id.id)
                    }
                }
                saveNotifiedIds(current)

                // Cancelar notificaciones de alarmas resueltas
                result.data.filter { it.isCleared }.forEach { alarm ->
                    AlarmNotificationManager.cancelAlarmNotification(applicationContext, alarm)
                }

                // Si el WebSocket no está conectado, intentar reconectar
                if (!wsConnected) {
                    Log.i(TAG, "Backup poll: WebSocket caído — intentando reconectar")
                    startWebSocket()
                }
            }
            is Result.Error -> {
                Log.w(TAG, "Backup poll error: ${result.message}")
                // Error de auth — limpiar IDs para re-notificar al renovar sesión
                val msg = result.message?.lowercase() ?: ""
                if (msg.contains("sesión") || msg.contains("token") || msg.contains("401")) {
                    AlarmPollingReceiver.clearNotifiedIds(applicationContext)
                    // El repositorio ya intentó renovar el token automáticamente
                    // Reconectar WebSocket con el nuevo token
                    scope.launch {
                        delay(2_000L)
                        startWebSocket()
                    }
                }
            }
            else -> {}
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "App cerrada desde recientes — servicio continúa")
        // El servicio tiene stopWithTask=false — no se detiene
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false
        Log.i(TAG, "Servicio destruido — START_STICKY lo reiniciará")
        try {
            webSocket?.disconnect()
            reconnectJob?.cancel()
            backupPollJob?.cancel()
            scope.cancel()
        } catch (e: Exception) { }
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (e: Exception) { }
        super.onDestroy()
    }
}
