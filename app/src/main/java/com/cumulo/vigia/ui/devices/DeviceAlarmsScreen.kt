package com.cumulo.vigia.ui.devices

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cumulo.vigia.model.Alarm
import com.cumulo.vigia.model.Device
import com.cumulo.vigia.ui.AlarmCard
import com.cumulo.vigia.ui.VigiaViewModel
import com.cumulo.vigia.ui.theme.*

@Composable
fun DeviceAlarmsScreen(
    device: Device,
    viewModel: VigiaViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.dashboardState.collectAsState()

    // Filtrar las alarmas del estado global que corresponden a este dispositivo
    val deviceAlarms = state.alarms.filter { alarm ->
        alarm.originatorName.equals(device.name, ignoreCase = true)
    }

    val activeCount = deviceAlarms.count { it.isActive && !it.isCleared }

    Column(modifier = Modifier.fillMaxSize().background(ZincBg)) {

        // Top bar
        Surface(
            color = ZincSurface,
            border = BorderStroke(1.dp, ZincBorder)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, null, tint = ZincText)
                }
                Spacer(Modifier.width(4.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(device.name, color = ZincText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (device.online) "Online · ${deviceAlarms.size} alarmas"
                        else "Offline · ${deviceAlarms.size} alarmas",
                        color = if (device.online) EmeraldGreen else ZincMuted,
                        fontSize = 12.sp
                    )
                }
                if (activeCount > 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = CriticalColor.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, CriticalColor.copy(alpha = 0.4f))
                    ) {
                        Text(
                            "$activeCount activa${if (activeCount > 1) "s" else ""}",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            color = CriticalColor,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
                IconButton(onClick = { viewModel.loadData(isRefresh = true) }) {
                    Icon(Icons.Default.Refresh, null, tint = ZincMuted)
                }
            }
        }

        if (deviceAlarms.isEmpty()) {
            // Estado vacío
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        null,
                        tint = EmeraldGreen.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                    Text("Sin alarmas para este dispositivo", color = ZincMuted, fontSize = 15.sp)
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    // Resumen del dispositivo
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = ZincCard,
                        border = BorderStroke(1.dp, ZincBorder)
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            DeviceStat("Total", deviceAlarms.size.toString(), ZincTextMuted)
                            VerticalDivider(modifier = Modifier.height(40.dp), color = ZincBorder)
                            DeviceStat("Activas", activeCount.toString(), if (activeCount > 0) CriticalColor else EmeraldGreen)
                            VerticalDivider(modifier = Modifier.height(40.dp), color = ZincBorder)
                            DeviceStat(
                                "Críticas",
                                deviceAlarms.count { it.isCritical && it.isActive }.toString(),
                                CriticalColor
                            )
                        }
                    }
                }

                items(deviceAlarms, key = { it.id.id }) { alarm ->
                    AlarmCard(
                        alarm = alarm,
                        onAck = viewModel::acknowledgeAlarm,
                        onClear = viewModel::clearAlarm
                    )
                }

                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun DeviceStat(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = valueColor, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Text(label, color = ZincMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}
