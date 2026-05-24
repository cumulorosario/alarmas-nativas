package com.cumulo.vigia.ui.dashboard

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cumulo.vigia.ui.*
import com.cumulo.vigia.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: VigiaViewModel,
    onNavigateToAlarms: () -> Unit,
    onNavigateToDevices: () -> Unit
) {
    val state by viewModel.dashboardState.collectAsState()
    val pullRefreshState = rememberPullToRefreshState()

    if (pullRefreshState.isRefreshing) {
        LaunchedEffect(true) {
            viewModel.loadData(isRefresh = true)
        }
    }

    LaunchedEffect(state.isRefreshing) {
        if (!state.isRefreshing) pullRefreshState.endRefresh()
    }

    Box(Modifier.nestedScroll(pullRefreshState.nestedScrollConnection)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().background(ZincBg),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Dashboard", color = ZincText, fontSize = 28.sp, fontWeight = FontWeight.Black)
                        Text("Vista de Supervisión", color = ZincMuted, fontSize = 14.sp)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(EmeraldGreen, androidx.compose.foundation.shape.CircleShape)
                        )
                        Text("Conectado", color = EmeraldGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Error banner
            if (state.error != null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = CriticalColor.copy(alpha = 0.1f),
                        border = BorderStroke(1.dp, CriticalColor.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, null, tint = CriticalColor, modifier = Modifier.size(18.dp))
                            Text(state.error ?: "", color = CriticalColor, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { viewModel.loadData() }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Default.Refresh, null, tint = ZincMuted)
                            }
                        }
                    }
                }
            }

            // Stat cards — ambas tarjetas con la misma altura
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.height(IntrinsicSize.Max)
                ) {
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        StatCard(
                            icon = Icons.Default.NotificationsActive,
                            label = "ALARMAS ACTIVAS",
                            value = state.activeAlarms.size.toString(),
                            iconColor = if (state.criticalCount > 0) CriticalColor else AmberWarning,
                            onClick = onNavigateToAlarms
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        StatCard(
                            icon = Icons.Default.Memory,
                            label = "DISPOSITIVOS ONLINE",
                            value = "${state.onlineDevices}/${state.devices.size}",
                            iconColor = EmeraldGreen,
                            onClick = onNavigateToDevices
                        )
                    }
                }
            }

            // Critical alarms count banner
            if (state.criticalCount > 0) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = CriticalColor.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, CriticalColor.copy(alpha = 0.5f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.Warning, null, tint = CriticalColor, modifier = Modifier.size(24.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "${state.criticalCount} ALARMA${if (state.criticalCount > 1) "S" else ""} CRÍTICA${if (state.criticalCount > 1) "S" else ""}",
                                    color = CriticalColor,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 14.sp,
                                    letterSpacing = 1.sp
                                )
                                Text("Requieren atención inmediata", color = CriticalColor.copy(alpha = 0.7f), fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Pending alarms section
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Shield, null, tint = CriticalColor, modifier = Modifier.size(22.dp))
                        Text("Alarmas Pendientes", color = ZincText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    if (state.isLoading) {
                        CircularProgressIndicator(color = RedPrimary, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }

            if (!state.isLoading && state.pendingAlarms.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = ZincCard,
                        border = BorderStroke(1.dp, ZincBorder)
                    ) {
                        Column(
                            modifier = Modifier.padding(40.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.CheckCircle, null, tint = EmeraldGreen.copy(alpha = 0.5f), modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(12.dp))
                            Text("Sin alarmas activas", color = ZincMuted, fontSize = 15.sp)
                        }
                    }
                }
            }

            items(state.pendingAlarms.take(10), key = { it.id.id }) { alarm ->
                AlarmCard(alarm = alarm, onAck = viewModel::acknowledgeAlarm, onClear = viewModel::clearAlarm)
            }

            if (state.pendingAlarms.size > 10) {
                item {
                    TextButton(onClick = onNavigateToAlarms, modifier = Modifier.fillMaxWidth()) {
                        Text("Ver todas (${state.pendingAlarms.size})", color = ZincMuted)
                        Icon(Icons.Default.ChevronRight, null, tint = ZincMuted)
                    }
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }

        PullToRefreshContainer(
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
            containerColor = ZincCard,
            contentColor = RedPrimary
        )
    }
}
