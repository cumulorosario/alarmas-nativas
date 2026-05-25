package com.cumulo.vigia.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.cumulo.vigia.R
import com.cumulo.vigia.mqtt.MqttAlarmClient

class MqttAlarmService : Service() {

    private var mqttClient: MqttAlarmClient? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()

        startForeground(
            2001,
            NotificationCompat.Builder(this, "vigia_mqtt")
                .setContentTitle("Vigia MQTT")
                .setContentText("Conectado al broker MQTT")
                .setSmallIcon(R.mipmap.ic_launcher)
                .build()
        )

        mqttClient = MqttAlarmClient(
            context = this,
            host = "tcp://10.0.2.2:1883",
            clientId = "vigia_android",
            username = "",
            password = ""
        ) {
            showAlarmNotification(it)
        }

        mqttClient?.connect()
    }

    private fun showAlarmNotification(message: String) {
        val manager = getSystemService(NotificationManager::class.java)

        val notification = NotificationCompat.Builder(this, "vigia_mqtt")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Nueva alarma")
            .setContentText(message.take(120))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "vigia_mqtt",
                "MQTT Alarmas",
                NotificationManager.IMPORTANCE_HIGH
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        mqttClient?.disconnect()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
