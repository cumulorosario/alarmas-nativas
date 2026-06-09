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
import com.cumulo.vigia.model.Device
import com.cumulo.vigia.ui.DeviceCard
import com.cumulo.vigia.ui.VigiaViewModel
import com.cumulo.vigia.ui.theme.*

@Composable
fun DevicesScreen(viewModel: VigiaViewModel) {
    val state by viewModel.dashboardState.collectAsState()
    var selectedDevice by remember { mutableStateOf<Device?>(null) }

    // Si hay un dispositivo seleccionado, mostrar su pantalla de alarmas
    selectedDevice?.let { device ->
        DeviceAlarmsScreen(
            device = device,
            viewModel = viewModel,
            onBack = { selectedDevice = null }
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(ZincBg),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Dispositivos", color = ZincText, fontSize = 28.sp, fontWeight = FontWeight.Black)
                    Text("Total: ${state.devices.size}", color = ZincMuted, fontSize = 14.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = EmeraldGreen.copy(alpha = 0.1f),
                        border = BorderStroke(1.dp, EmeraldGreen.copy(alpha = 0.3f))
                    ) {
                        Text(
                            "${state.onlineDevices} online",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            color = EmeraldGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = { viewModel.loadData(isRefresh = true) }) {
                        Icon(Icons.Default.Refresh, null, tint = ZincMuted)
                    }
                }
            }
        }

        item {
            // Instrucción
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = ZincCard,
                border = BorderStroke(1.dp, ZincBorder)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.TouchApp, null, tint = ZincMuted, modifier = Modifier.size(16.dp))
                    Text(
                        "Tocá un dispositivo para ver sus alarmas",
                        color = ZincMuted,
                        fontSize = 12.sp
                    )
                }
            }
        }

        if (state.devices.isEmpty() && !state.isLoading) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = ZincCard,
                    border = BorderStroke(1.dp, ZincBorder)
                ) {
                    Column(
                        modifier = Modifier.padding(48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.Memory, null, tint = ZincBorder, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No se encontraron dispositivos", color = ZincMuted)
                    }
                }
            }
        }

        items(state.devices, key = { it.id.id }) { device ->
            // Contar alarmas activas para este dispositivo
            val activeForDevice = state.alarms.count { alarm ->
                alarm.originatorName.equals(device.name, ignoreCase = true) &&
                alarm.isActive && !alarm.isCleared
            }

            Box {
                DeviceCard(
                    device = device,
                    onClick = { selectedDevice = device }
                )
                // Badge de alarmas activas
                if (activeForDevice > 0) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-12).dp, y = (-6).dp),
                        shape = RoundedCornerShape(50),
                        color = CriticalColor
                    ) {
                        Text(
                            activeForDevice.toString(),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            color = androidx.compose.ui.graphics.Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}
