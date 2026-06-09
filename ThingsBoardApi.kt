package com.cumulo.vigia.ui

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cumulo.vigia.data.VigiaRepository
import com.cumulo.vigia.data.local.SessionStore
import com.cumulo.vigia.service.AlarmNotificationManager
import com.cumulo.vigia.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmFullScreenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }

        val alarmId       = intent.getStringExtra(AlarmNotificationManager.EXTRA_ALARM_ID) ?: ""
        val alarmName     = intent.getStringExtra(AlarmNotificationManager.EXTRA_ALARM_NAME) ?: "Dispositivo"
        val alarmType     = intent.getStringExtra(AlarmNotificationManager.EXTRA_ALARM_TYPE) ?: "Alarma"
        val alarmSeverity = intent.getStringExtra(AlarmNotificationManager.EXTRA_ALARM_SEVERITY) ?: "CRITICAL"

        val repository = VigiaRepository(SessionStore(applicationContext))

        fun cancelNotification() {
            val notifId = AlarmNotificationManager.NOTIF_ALARM_BASE_ID +
                alarmId.hashCode().and(0x7FFFFFFF).rem(900)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(notifId)
        }

        setContent {
            VigiaTheme {
                FullScreenAlarmContent(
                    alarmId = alarmId,
                    deviceName = alarmName,
                    alarmType = alarmType,
                    severity = alarmSeverity,
                    // Reconocer = silenciar notificación + cerrar pantalla
                    onAck = { id ->
                        CoroutineScope(Dispatchers.IO).launch { repository.acknowledgeAlarm(id) }
                        cancelNotification()
                        finish()
                    },
                    // Resolver = cerrar alarma + silenciar + cerrar pantalla
                    onClear = { id ->
                        CoroutineScope(Dispatchers.IO).launch { repository.clearAlarm(id) }
                        cancelNotification()
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
fun FullScreenAlarmContent(
    alarmId: String,
    deviceName: String,
    alarmType: String,
    severity: String,
    onAck: (String) -> Unit,
    onClear: (String) -> Unit
) {
    val pulseAnim = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulseAnim.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val isCritical = severity == "CRITICAL" || severity == "MAJOR"
    val primaryColor = severityColor(severity)

    Box(
        modifier = Modifier.fillMaxSize().background(ZincBg),
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.fillMaxSize().background(primaryColor.copy(alpha = 0.08f)))

        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(Modifier.height(32.dp))

            // Pulsing icon
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .scale(if (isCritical) pulseScale else 1f)
                    .background(primaryColor.copy(alpha = 0.15f), CircleShape)
                    .border(2.dp, primaryColor.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Warning, null, tint = primaryColor, modifier = Modifier.size(52.dp))
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("ALARMA ${severity.uppercase()}", color = primaryColor, fontSize = 13.sp,
                    fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                Spacer(Modifier.height(8.dp))
                Text("DETECTADA", color = ZincText, fontSize = 36.sp,
                    fontWeight = FontWeight.Black, letterSpacing = (-1).sp)
            }

            // Detail card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = ZincCard,
                border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DetailRow("Tipo", alarmType)
                    HorizontalDivider(color = ZincBorder)
                    DetailRow("Dispositivo", deviceName)
                    HorizontalDivider(color = ZincBorder)
                    DetailRow("Severidad", severity)
                }
            }

            Spacer(Modifier.weight(1f))

            // Buttons — sin "Silenciar": Reconocer ya silencia
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // RECONOCER Y SILENCIAR — acción principal
                Button(
                    onClick = { onAck(alarmId) },
                    modifier = Modifier.fillMaxWidth().height(60.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor)
                ) {
                    Icon(Icons.Default.NotificationsOff, null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("RECONOCER Y SILENCIAR", fontWeight = FontWeight.Black, fontSize = 15.sp, letterSpacing = 1.sp)
                }

                // RESOLVER — acción secundaria
                OutlinedButton(
                    onClick = { onClear(alarmId) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.6f)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = EmeraldGreen)
                ) {
                    Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Resolver alarma", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = ZincMuted, fontSize = 13.sp)
        Text(value, color = ZincText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End, modifier = Modifier.weight(1f, fill = false))
    }
}
