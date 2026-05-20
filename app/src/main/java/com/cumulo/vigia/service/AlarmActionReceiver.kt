package com.cumulo.vigia.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cumulo.vigia.data.VigiaRepository
import com.cumulo.vigia.data.local.SessionStore
import com.cumulo.vigia.model.Result
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

        scope.launch {
            try {
                when (action) {
                    AlarmNotificationManager.ACTION_ACK -> {
                        Log.i("AlarmActionReceiver", "ACK alarm $alarmId")
                        repository.acknowledgeAlarm(alarmId)
                    }
                    AlarmNotificationManager.ACTION_CLEAR -> {
                        Log.i("AlarmActionReceiver", "CLEAR alarm $alarmId")
                        repository.clearAlarm(alarmId)
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
