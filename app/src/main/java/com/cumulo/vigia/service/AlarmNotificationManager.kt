package com.cumulo.vigia.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.cumulo.vigia.R
import com.cumulo.vigia.model.Alarm
import com.cumulo.vigia.ui.AlarmFullScreenActivity
import com.cumulo.vigia.ui.MainActivity

object AlarmNotificationManager {

    const val CHANNEL_SERVICE = "vigia_service"
    const val CHANNEL_CRITICAL = "vigia_critical"
    const val CHANNEL_WARNINGS = "vigia_warnings"

    const val NOTIF_SERVICE_ID = 1
    const val NOTIF_ALARM_BASE_ID = 100

    const val ACTION_ACK = "com.cumulo.vigia.ACTION_ACK"
    const val ACTION_CLEAR = "com.cumulo.vigia.ACTION_CLEAR"
    const val EXTRA_ALARM_ID = "alarm_id"
    const val EXTRA_ALARM_NAME = "alarm_name"
    const val EXTRA_ALARM_TYPE = "alarm_type"
    const val EXTRA_ALARM_SEVERITY = "alarm_severity"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Silent background service channel
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Servicio Vigia", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Monitoreo en segundo plano"
                setShowBadge(false)
            }
        )

        // CRITICAL / MAJOR alarms - max importance, full-screen
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_CRITICAL, "Alarmas Críticas", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alertas críticas que requieren atención inmediata"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                enableLights(true)
                lightColor = android.graphics.Color.RED
                setSound(alarmSound, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )

        // MINOR / WARNING alarms
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WARNINGS, "Advertencias", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Alarmas de severidad menor"
                enableVibration(true)
            }
        )
    }

    fun buildServiceNotification(context: Context): Notification {
        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setContentTitle("Vigia Industrial")
            .setContentText("Monitoreo activo · Vigilando alarmas")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    fun showCriticalAlarm(context: Context, alarm: Alarm) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Wake screen
        wakeScreen(context)

        // Full-screen intent (shown over lock screen)
        val fullScreenIntent = Intent(context, AlarmFullScreenActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ALARM_ID, alarm.id.id)
            putExtra(EXTRA_ALARM_NAME, alarm.originatorName)
            putExtra(EXTRA_ALARM_TYPE, alarm.displayType())
            putExtra(EXTRA_ALARM_SEVERITY, alarm.severity)
        }
        val fullScreenPi = PendingIntent.getActivity(context,
            alarm.id.id.hashCode(),
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // Tap notification -> open app
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val mainPi = PendingIntent.getActivity(context,
            alarm.id.id.hashCode() + 1,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // ACK action
        val ackIntent = Intent(context, AlarmActionReceiver::class.java).apply {
            action = ACTION_ACK
            putExtra(EXTRA_ALARM_ID, alarm.id.id)
        }
        val ackPi = PendingIntent.getBroadcast(context,
            alarm.id.id.hashCode() + 2,
            ackIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        // CLEAR action
        val clearIntent = Intent(context, AlarmActionReceiver::class.java).apply {
            action = ACTION_CLEAR
            putExtra(EXTRA_ALARM_ID, alarm.id.id)
        }
        val clearPi = PendingIntent.getBroadcast(context,
            alarm.id.id.hashCode() + 3,
            clearIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val channel = if (alarm.isCritical) CHANNEL_CRITICAL else CHANNEL_WARNINGS
        val priority = if (alarm.isCritical) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH

        val notification = NotificationCompat.Builder(context, channel)
            .setContentTitle("⚠ ALERTA ${alarm.severity}: ${alarm.originatorName}")
            .setContentText(alarm.displayType())
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(priority)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(false)
            .setOngoing(alarm.isCritical)
            .setFullScreenIntent(fullScreenPi, true)
            .setContentIntent(mainPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Reconocer", ackPi)
            .addAction(android.R.drawable.ic_menu_save, "Resolver", clearPi)
            .setStyle(NotificationCompat.BigTextStyle()
                .setBigContentTitle("⚠ ${alarm.severity}: ${alarm.originatorName}")
                .bigText("${alarm.displayType()}\nEstado: ${alarm.displayStatus()}")
            )
            .setDefaults(Notification.DEFAULT_ALL)
            .build()

        nm.notify(NOTIF_ALARM_BASE_ID + alarm.id.id.hashCode().and(0x7FFFFFFF).rem(900), notification)
    }

    fun cancelAlarmNotification(context: Context, alarm: Alarm) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIF_ALARM_BASE_ID + alarm.id.id.hashCode().and(0x7FFFFFFF).rem(900))
    }

    private fun wakeScreen(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "VigiaIndustrial:AlarmWakeLock"
            )
            wl.acquire(10_000L)
            wl.release()
        } catch (e: Exception) {
            // Ignore if permission not granted
        }
    }
}
