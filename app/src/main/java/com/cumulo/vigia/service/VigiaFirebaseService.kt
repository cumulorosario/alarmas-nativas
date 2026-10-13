package com.cumulo.vigia.service

import android.util.Log
import com.cumulo.vigia.data.VigiaRepository
import com.cumulo.vigia.data.local.SessionStore
import com.cumulo.vigia.model.Alarm
import com.cumulo.vigia.model.AlarmId
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking

/**
 * Servicio FCM — recibe push notifications de Firebase.
 *
 * Android despierta este servicio AUNQUE el teléfono esté en Doze Mode,
 * con la pantalla bloqueada, o con la app cerrada.
 * Es el mecanismo más confiable disponible en Android.
 *
 * Flujo:
 * ThingsBoard Rule Chain → fcm_webhook → Firebase → onMessageReceived()
 *
 * Payload esperado:
 * {
 *   "alarmId":       "uuid",
 *   "alarmType":     "HIGH_TEMPERATURE",
 *   "severity":      "CRITICAL",
 *   "status":        "ACTIVE_UNACK",
 *   "deviceName":    "Sensor-01",
 *   "createdTime":   "1234567890000"
 * }
 *
 * IMPORTANTE: onMessageReceived() y onNewToken() ya corren en un hilo
 * secundario. Todo el trabajo se hace dentro de runBlocking para que
 * el método no termine hasta que la notificación se muestre. Si se
 * lanzara una corrutina "suelta" (CoroutineScope.launch), con la app
 * cerrada Android podría matar el proceso antes de mostrar la alarma.
 */
class VigiaFirebaseService : FirebaseMessagingService() {

    private val TAG = "VigiaFCM"

    /**
     * Se llama cuando llega un push de FCM.
     * Funciona en cualquier estado del teléfono.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        Log.i(TAG, "Push recibido — from: ${message.from}")

        val data = message.data
        if (data.isEmpty()) {
            Log.w(TAG, "Push sin data payload — ignorando")
            return
        }

        Log.d(TAG, "Data: $data")

        val alarmId   = data["alarmId"]   ?: data["id"] ?: return
        val alarmType = data["alarmType"] ?: data["type"] ?: "ALARMA"
        val severity  = data["severity"]  ?: "CRITICAL"
        val status    = data["status"]    ?: "ACTIVE_UNACK"
        val deviceName = data["deviceName"] ?: data["originatorName"] ?: "Dispositivo"
        val createdTime = data["createdTime"]?.toLongOrNull() ?: System.currentTimeMillis()

        val alarm = Alarm(
            id             = AlarmId(alarmId),
            createdTime    = createdTime,
            type           = alarmType,
            severity       = severity,
            status         = status,
            originatorName = deviceName
        )

        // Verificar si está activa (no renotificar alarmas resueltas)
        if (!alarm.isActive || alarm.isCleared) {
            Log.d(TAG, "Alarma resuelta en push — ignorando: $status")
            return
        }

        // Se espera a que termine todo antes de devolver el control a Android
        runBlocking {
            try {
                // Verificar filtros locales
                val filterStore = com.cumulo.vigia.data.local.AlarmFilterStore(applicationContext)
                val filters = filterStore.getFilters()
                val isMuted = filters.any { f ->
                    f.muted && f.alarmType == alarm.type &&
                    alarm.originatorName.equals(f.deviceName, ignoreCase = true)
                }

                if (isMuted) {
                    Log.d(TAG, "Alarma muteada por filtro — no notificando")
                    return@runBlocking
                }

                // Verificar si ya fue notificada recientemente
                val notifiedIds = AlarmPollingReceiver.getNotifiedIds(applicationContext)
                if (alarm.id.id in notifiedIds) {
                    Log.d(TAG, "Alarma ya notificada — ignorando duplicado")
                    return@runBlocking
                }

                Log.i(TAG, "FCM ALERTA: $alarmId $severity $deviceName")

                // Mostrar notificación inmediata
                AlarmNotificationManager.createChannels(applicationContext)
                AlarmNotificationManager.showCriticalAlarm(applicationContext, alarm)

                // Guardar en notifiedIds para evitar duplicados con el backup poll
                val updated = notifiedIds.toMutableSet().also { it.add(alarm.id.id) }
                AlarmPollingReceiver.saveNotifiedIds(applicationContext, updated)

            } catch (e: Exception) {
                Log.e(TAG, "Error procesando push: ${e.message}")
                // Notificar igual si hay error en el proceso — mejor una notificación
                // de más que perderse una alarma crítica
                AlarmNotificationManager.createChannels(applicationContext)
                AlarmNotificationManager.showCriticalAlarm(applicationContext, alarm)
            }
        }
    }

    /**
     * Se llama cuando Firebase rota el token FCM del dispositivo.
     * Debemos registrar el nuevo token en ThingsBoard inmediatamente.
     */
    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token renovado — registrando en ThingsBoard")
        // Se guarda primero como pendiente: si el proceso muere a mitad
        // del registro, la app lo reintenta en el próximo login/inicio.
        savePendingToken(token)
        runBlocking {
            try {
                registerTokenWithThingsBoard(token)
            } catch (e: Exception) {
                Log.e(TAG, "Error registrando nuevo token: ${e.message}")
                // Queda guardado como pendiente para reintentar en el próximo inicio
            }
        }
    }

    private suspend fun registerTokenWithThingsBoard(token: String) {
        val sessionStore = SessionStore(applicationContext)
        val session = sessionStore.getSession()
        if (!session.isLoggedIn) {
            Log.w(TAG, "Sin sesión — token queda pendiente")
            return
        }
        val repository = VigiaRepository(sessionStore)
        repository.registerFcmToken(token)
        clearPendingToken()
        Log.i(TAG, "Token FCM registrado en ThingsBoard correctamente")
    }

    private fun savePendingToken(token: String) {
        applicationContext.getSharedPreferences("vigia_fcm", MODE_PRIVATE)
            .edit().putString("pending_token", token).apply()
    }

    private fun clearPendingToken() {
        applicationContext.getSharedPreferences("vigia_fcm", MODE_PRIVATE)
            .edit().remove("pending_token").apply()
    }

    companion object {
        fun getPendingToken(context: android.content.Context): String? =
            context.getSharedPreferences("vigia_fcm", MODE_PRIVATE)
                .getString("pending_token", null)
    }
}
