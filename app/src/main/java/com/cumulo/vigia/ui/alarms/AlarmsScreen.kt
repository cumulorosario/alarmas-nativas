package com.cumulo.vigia.ui.alarms

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
import com.cumulo.vigia.ui.*
import com.cumulo.vigia.ui.theme.*

enum class AlarmFilter { ALL, ACTIVE, CLEARED }

@Composable
fun AlarmsScreen(viewModel: VigiaViewModel) {
    val state by viewModel.dashboardState.collectAsState()
    var filter by remember { mutableStateOf(AlarmFilter.ALL) }

    val filteredAlarms = when (filter) {
        AlarmFilter.ALL -> state.alarms
        AlarmFilter.ACTIVE -> state.alarms.filter { it.isActive && !it.isCleared }
        AlarmFilter.CLEARED -> state.alarms.filter { it.isCleared }
    }.sortedByDescending { it.createdTime }

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
                    Text("Alarmas", color = ZincText, fontSize = 28.sp, fontWeight = FontWeight.Black)
                    Text("Historial completo", color = ZincMuted, fontSize = 14.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.isRefreshing || state.isLoading) {
                        CircularProgressIndicator(color = RedPrimary, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    IconButton(onClick = { viewModel.loadData(isRefresh = true) }) {
                        Icon(Icons.Default.Refresh, null, tint = ZincMuted)
                    }
                }
            }
        }

        // Filter chips
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = filter == AlarmFilter.ALL,
                    onClick = { filter = AlarmFilter.ALL },
                    label = { Text("Todas (${state.alarms.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = RedPrimary,
                        selectedLabelColor = androidx.compose.ui.graphics.Color.White
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true, selected = filter == AlarmFilter.ALL,
                        borderColor = ZincBorder, selectedBorderColor = RedPrimary
                    )
                )
                FilterChip(
                    selected = filter == AlarmFilter.ACTIVE,
                    onClick = { filter = AlarmFilter.ACTIVE },
                    label = { Text("Activas (${state.activeAlarms.size})") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CriticalColor,
                        selectedLabelColor = androidx.compose.ui.graphics.Color.White
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true, selected = filter == AlarmFilter.ACTIVE,
                        borderColor = ZincBorder, selectedBorderColor = CriticalColor
                    )
                )
                FilterChip(
                    selected = filter == AlarmFilter.CLEARED,
                    onClick = { filter = AlarmFilter.CLEARED },
                    label = { Text("Resueltas") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = EmeraldGreen,
                        selectedLabelColor = androidx.compose.ui.graphics.Color.White
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true, selected = filter == AlarmFilter.CLEARED,
                        borderColor = ZincBorder, selectedBorderColor = EmeraldGreen
                    )
                )
            }
        }

        if (filteredAlarms.isEmpty()) {
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
                        Icon(Icons.Default.Notifications, null, tint = ZincBorder, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No hay alarmas en esta vista", color = ZincMuted, fontSize = 14.sp)
                    }
                }
            }
        }

        items(filteredAlarms, key = { it.id.id }) { alarm ->
            AlarmCard(alarm = alarm, onAck = viewModel::acknowledgeAlarm, onClear = viewModel::clearAlarm)
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}
