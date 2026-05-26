package com.cumulo.vigia.service

import android.util.Log
import com.cumulo.vigia.model.Alarm
import com.cumulo.vigia.model.AlarmId
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cliente WebSocket para ThingsBoard.
 *
 * ThingsBoard expone ws://host:port/api/ws/plugins/telemetry?token=JWT
 * Enviamos un comando de suscripción a alarmas y recibimos push en tiempo real.
 *
 * Protocolo ThingsBoard WebSocket:
 * - Suscripción: {"alarmSubCmds":[{"entityType":"TENANT","entityId":"<tenantId>","cmdId":1}]}
 * - Respuesta:   {"subscriptionId":1,"errorCode":0,"errorMsg":"","data":{"...alarm fields..."}}
 */
class ThingsBoardWebSocket(
    private val baseUrl: String,
    private val token: String,
    private val tenantId: String,
    private val customerId: String,
    private val onAlarm: (Alarm) -> Unit,
    private val onConnected: () -> Unit,
    private val onDisconnected: (reason: String) -> Unit
) {
    private val TAG = "TBWebSocket"
    private val cmdId = AtomicInteger(1)
    private var webSocket: WebSocket? = null
    private var isConnected = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)  // Sin timeout de lectura — conexión persistente
        .pingInterval(30, TimeUnit.SECONDS) // Keepalive ping cada 30s
        .build()

    fun connect() {
        if (isConnected) return

        // Construir URL WebSocket — reemplazar http/https por ws/wss
        val wsUrl = baseUrl
            .trimEnd('/')
            .replace("http://", "ws://")
            .replace("https://", "wss://") +
            "/api/ws/plugins/telemetry?token=$token"

        Log.i(TAG, "Conectando a $wsUrl")

        val request = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket conectado")
                isConnected = true
                onConnected()
                // Suscribirse a alarmas
                subscribeToAlarms(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket cerrando: $code $reason")
                isConnected = false
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket cerrado: $code $reason")
                isConnected = false
                onDisconnected("Conexión cerrada: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                isConnected = false
                onDisconnected("Error: ${t.message}")
            }
        })
    }

    private fun subscribeToAlarms(ws: WebSocket) {
        val subscriptions = mutableListOf<JSONObject>()

        // Suscripción 1: Alarmas del tenant
        if (tenantId.isNotEmpty()) {
            subscriptions.add(JSONObject().apply {
                put("entityType", "TENANT")
                put("entityId", tenantId)
                put("cmdId", cmdId.getAndIncrement())
                put("fetchExisting", true) // Recibir alarmas activas al conectar
            })
        }

        // Suscripción 2: Alarmas del customer (si es usuario customer)
        if (customerId.isNotEmpty()) {
            subscriptions.add(JSONObject().apply {
                put("entityType", "CUSTOMER")
                put("entityId", customerId)
                put("cmdId", cmdId.getAndIncrement())
                put("fetchExisting", true)
            })
        }

        if (subscriptions.isEmpty()) {
            Log.w(TAG, "Sin tenantId ni customerId — no se puede suscribir a alarmas")
            return
        }

        val msg = JSONObject()
        msg.put("alarmSubCmds", JSONArray(subscriptions))
        msg.put("tsSubCmds", JSONArray())
        msg.put("historyCmds", JSONArray())
        msg.put("attrSubCmds", JSONArray())

        val msgStr = msg.toString()
        Log.i(TAG, "Enviando suscripción: $msgStr")
        ws.send(msgStr)
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)

            // Mensaje de error de suscripción
            val errorCode = json.optInt("errorCode", 0)
            if (errorCode != 0) {
                Log.w(TAG, "Error en suscripción: ${json.optString("errorMsg")}")
                return
            }

            // Datos de alarmas — ThingsBoard envía el objeto alarma directamente
            val data = json.optJSONObject("data") ?: return

            // ThingsBoard puede enviar update de alarma individual
            parseAlarmFromMessage(data)?.let { alarm ->
                Log.i(TAG, "Alarma recibida via WS: ${alarm.id.id} ${alarm.severity} ${alarm.status}")
                if (alarm.isActive && !alarm.isCleared) {
                    onAlarm(alarm)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error procesando mensaje WS: ${e.message} — raw: $text")
        }
    }

    private fun parseAlarmFromMessage(data: JSONObject): Alarm? {
        return try {
            // ThingsBoard WebSocket alarm message format
            val idObj = data.optJSONObject("id")
            val alarmId = idObj?.optString("id") ?: return null

            Alarm(
                id             = AlarmId(alarmId),
                createdTime    = data.optLong("createdTime", System.currentTimeMillis()),
                type           = data.optString("type", "UNKNOWN"),
                severity       = data.optString("severity", "INDETERMINATE"),
                status         = data.optString("status", "ACTIVE_UNACK"),
                originatorName = data.optString("originatorName",
                    data.optJSONObject("originator")?.optString("id") ?: "Desconocido")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parseando alarma: ${e.message}")
            null
        }
    }

    fun disconnect() {
        isConnected = false
        webSocket?.close(1000, "Desconexión normal")
        webSocket = null
    }

    fun isConnected() = isConnected
}
