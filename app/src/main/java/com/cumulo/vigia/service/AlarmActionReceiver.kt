package com.cumulo.vigia.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cumulo.vigia.data.VigiaRepository
import com.cumulo.vigia.data.local.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getStringExtra(AlarmNotificationManager.EXTRA_ALARM_ID) ?: return
        val action = intent.action ?: return

        val pendingResult = goAsync()
        val scope = CoroutineScope(Dispatchers.IO)
        val repository = VigiaRepository(SessionStore(context))
        val nm = AlarmNotificationManager

        scope.launch {
            try {
                when (action) {
                    AlarmNotificationManager.ACTION_ACK -> {
                        Log.i("AlarmActionReceiver", "ACK alarm $alarmId — cancelling notification")
                        repository.acknowledgeAlarm(alarmId)
                        // Reconocer = silenciar: cancela la notificación persistente
                        val notifId = AlarmNotificationManager.NOTIF_ALARM_BASE_ID +
                            alarmId.hashCode().and(0x7FFFFFFF).rem(900)
                        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                            as android.app.NotificationManager
                        notifManager.cancel(notifId)
                    }
                    AlarmNotificationManager.ACTION_CLEAR -> {
                        Log.i("AlarmActionReceiver", "CLEAR alarm $alarmId")
                        repository.clearAlarm(alarmId)
                        val notifId = AlarmNotificationManager.NOTIF_ALARM_BASE_ID +
                            alarmId.hashCode().and(0x7FFFFFFF).rem(900)
                        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                            as android.app.NotificationManager
                        notifManager.cancel(notifId)
                    }
                }
            } catch (e: Exception) {
                Log.e("AlarmActionReceiver", "Error: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
