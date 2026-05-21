package com.cumulo.vigia.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cumulo.vigia.data.local.SessionStore
import com.cumulo.vigia.ui.VigiaViewModel
import com.cumulo.vigia.ui.theme.*

@Composable
fun SettingsScreen(viewModel: VigiaViewModel) {
    val context = LocalContext.current
    val alarmSettings by viewModel.alarmSettings.collectAsState()
    val sessionInfo by viewModel.sessionInfo.collectAsState()

    var vibrate by remember(alarmSettings) { mutableStateOf(alarmSettings.vibrate) }
    var sound by remember(alarmSettings) { mutableStateOf(alarmSettings.sound) }
    var wake by remember(alarmSettings) { mutableStateOf(alarmSettings.wake) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ZincBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Configuración", color = ZincText, fontSize = 28.sp, fontWeight = FontWeight.Black)
        Text("Sistema y preferencias", color = ZincMuted, fontSize = 14.sp)

        Spacer(Modifier.height(4.dp))

        // Server info
        SettingsCard(
            icon = Icons.Default.Storage,
            iconColor = RedPrimary,
            title = "Información del Servidor"
        ) {
            InfoRow("URL Base", sessionInfo.baseUrl.ifEmpty { SessionStore.DEFAULT_BASE_URL })
            HorizontalDivider(color = ZincBorder, modifier = Modifier.padding(vertical = 8.dp))
            InfoRow("Autoridad", sessionInfo.authority.ifEmpty { "—" })
            HorizontalDivider(color = ZincBorder, modifier = Modifier.padding(vertical = 8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).background(EmeraldGreen, androidx.compose.foundation.shape.CircleShape))
                Text("Conectado", color = EmeraldGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Battery optimization
        SettingsCard(
            icon = Icons.Default.BatteryAlert,
            iconColor = OrangeAlert,
            title = "Persistencia del Servicio"
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = OrangeAlert.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, OrangeAlert.copy(alpha = 0.2f))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Para garantizar alarmas aunque el teléfono esté bloqueado:", color = OrangeAlert, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    StepItem("1", "Habilitar \"Inicio Automático\" en ajustes de la app")
                    StepItem("2", "Desactivar \"Optimización de Batería\" (poner en Sin Restricciones)")
                    StepItem("3", "En apps recientes, bloquear la app con el candado")
                }
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = OrangeAlert.copy(alpha = 0.15f))
            ) {
                Icon(Icons.Default.BatteryAlert, null, tint = OrangeAlert, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Abrir ajustes de batería", color = OrangeAlert, fontWeight = FontWeight.Bold)
            }
        }

        // Notification settings
        SettingsCard(
            icon = Icons.Default.Notifications,
            iconColor = WarningColor,
            title = "Ajustes de Alerta"
        ) {
            ToggleRow(label = "Vibración", subtitle = "Vibrar al recibir alarma", checked = vibrate) {
                vibrate = it
                viewModel.updateAlarmSettings(it, sound, wake)
            }
            HorizontalDivider(color = ZincBorder, modifier = Modifier.padding(vertical = 4.dp))
            ToggleRow(label = "Sonido", subtitle = "Reproducir sonido de alarma", checked = sound) {
                sound = it
                viewModel.updateAlarmSettings(vibrate, it, wake)
            }
            HorizontalDivider(color = ZincBorder, modifier = Modifier.padding(vertical = 4.dp))
            ToggleRow(label = "Despertar pantalla", subtitle = "Encender pantalla al recibir alarma crítica", checked = wake) {
                wake = it
                viewModel.updateAlarmSettings(vibrate, sound, it)
            }

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                        context.startActivity(intent)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, ZincBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = ZincText)
            ) {
                Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Ajustes de notificación del sistema", fontWeight = FontWeight.Medium, fontSize = 13.sp)
            }
        }

        // Logout
        Button(
            onClick = { viewModel.logout() },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CriticalColor.copy(alpha = 0.12f))
        ) {
            Icon(Icons.Default.Logout, null, tint = CriticalColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Cerrar Sesión", color = CriticalColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        Spacer(Modifier.height(16.dp))
        Text("Vigia Industrial v1.0 · Cumulo Ingeniería", color = ZincMuted, fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)

        Spacer(Modifier.height(80.dp))
    }
}

@Composable
fun SettingsCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconColor: Color,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = ZincCard,
        border = BorderStroke(1.dp, ZincBorder)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(20.dp))
                Text(title, color = ZincText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), color = ZincMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Text(value, color = ZincText, fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    }
}

@Composable
fun ToggleRow(label: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = ZincText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = ZincMuted, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = RedPrimary,
                uncheckedThumbColor = ZincMuted,
                uncheckedTrackColor = ZincCard
            )
        )
    }
}

@Composable
fun StepItem(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(number + ".", color = OrangeAlert, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(text, color = ZincTextMuted, fontSize = 12.sp)
    }
}
