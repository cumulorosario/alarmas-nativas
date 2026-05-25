package com.cumulo.vigia.mqtt

import android.content.Context
import android.util.Log
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage

class MqttAlarmClient(
    private val context: Context,
    private val host: String,
    private val clientId: String,
    private val username: String,
    private val password: String,
    private val onAlarm: (String) -> Unit
) {
    private val serverUri = if (host.startsWith("tcp://") || host.startsWith("ssl://")) {
        host
    } else {
        "tcp://$host:1883"
    }

    private val client = MqttAndroidClient(context, serverUri, clientId)

    fun connect() {
        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = false
            connectionTimeout = 15
            keepAliveInterval = 30
            userName = username
            password = this@MqttAlarmClient.password.toCharArray()
        }

        client.setCallback(object : MqttCallbackExtended {

            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                subscribe()
            }

            override fun connectionLost(cause: Throwable?) {
                Log.e("MQTT", "Conexión perdida", cause)
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val payload = message?.toString() ?: return
                onAlarm(payload)
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {
            }
        })

        client.connect(options, null, object : IMqttActionListener {

            override fun onSuccess(asyncActionToken: IMqttToken?) {
                subscribe()
            }

            override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                Log.e("MQTT", "Error conectando", exception)
            }
        })
    }

    private fun subscribe() {
        try {
            client.subscribe("alarms/#", 1)
        } catch (e: Exception) {
            Log.e("MQTT", "Error subscribe", e)
        }
    }

    fun disconnect() {
        try {
            client.unregisterResources()
            client.close()
            client.disconnect()
        } catch (_: Exception) {
        }
    }
}
