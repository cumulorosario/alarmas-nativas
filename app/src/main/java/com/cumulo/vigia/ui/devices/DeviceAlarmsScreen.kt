package com.cumulo.vigia.ui.devices

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cumulo.vigia.data.local.AlarmFilterStore
import com.cumulo.vigia.model.Alarm
import com.cumulo.vigia.model.Device
import com.cumulo.vigia.ui.AlarmCard
import com.cumulo.vigia.ui.VigiaViewModel
import com.cumulo.vigia.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun DeviceAlarmsScreen(
    device: Device,
    viewModel: VigiaViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val filterStore = remember { AlarmFilterStore(context) }
    val scope = rememberCoroutineScope()

    val state by viewModel.dashboardState.collectAsState()
    val filters by filterStore.filtersFlow.collectAsState(initial = emptyList())

    // Alarmas de este dispositivo (por nombre de originador)
    val allDeviceAlarms = state.alarms.filter { alarm ->
        alarm.originatorName.equals(device.name, ignoreCase = true)
    }

    // Tipos de alarma únicos vistos para este dispositivo
    // (tanto activas como históricas en las últimas 20)
    val alarmTypes = allDeviceAlarms.map { it.type }.distinct().sorted()

    // Alarmas visibles: excluir las ocultas
    val visibleAlarms = allDeviceAlarms.filter { alarm ->
        val filter = filters.find {
            it.deviceId == device.id.id && it.alarmType == alarm.type
        }
        filter?.hidden != true
    }

    val activeCount = visibleAlarms.count { it.isActive && !it.isCleared }

    // Tab: 0 = Alarmas, 1 = Configurar tipos
    var selectedTab by remember { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize().background(ZincBg)) {

        // Top bar
        Surface(color = ZincSurface, border = BorderStroke(1.dp, ZincBorder)) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, null, tint = ZincText)
                    }
                    Spacer(Modifier.width(4.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(device.name, color = ZincText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (device.online) "Online" else "Offline",
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
                                color = CriticalColor, fontSize = 12.sp, fontWeight = FontWeight.Black
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.loadData(isRefresh = true) }) {
                        Icon(Icons.Default.Refresh, null, tint = ZincMuted)
                    }
                }

                // Tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = ZincSurface,
                    contentColor = RedPrimary,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = RedPrimary
                        )
                    }
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                "Alarmas (${visibleAlarms.size})",
                                color = if (selectedTab == 0) RedPrimary else ZincMuted,
                                fontWeight = FontWeight.Bold, fontSize = 13.sp
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Default.Tune, null,
                                    tint = if (selectedTab == 1) RedPrimary else ZincMuted,
                                    modifier = Modifier.size(14.dp))
                                Text(
                                    "Filtros (${alarmTypes.size})",
                                    color = if (selectedTab == 1) RedPrimary else ZincMuted,
                                    fontWeight = FontWeight.Bold, fontSize = 13.sp
                                )
                            }
                        }
                    )
                }
            }
        }

        when (selectedTab) {
            0 -> AlarmsTab(visibleAlarms, viewModel, activeCount)
            1 -> FiltersTab(device, alarmTypes, filters, filterStore, scope)
        }
    }
}

@Composable
private fun AlarmsTab(
    alarms: List<Alarm>,
    viewModel: VigiaViewModel,
    activeCount: Int
) {
    if (alarms.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.CheckCircle, null,
                    tint = EmeraldGreen.copy(alpha = 0.4f), modifier = Modifier.size(64.dp))
                Text("Sin alarmas visibles", color = ZincMuted, fontSize = 15.sp)
                Text("Revisá los filtros si esperabas ver algo", color = ZincMuted, fontSize = 12.sp)
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Resumen
        item {
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
                    DeviceStat("Total", alarms.size.toString(), ZincTextMuted)
                    VerticalDivider(modifier = Modifier.height(40.dp), color = ZincBorder)
                    DeviceStat("Activas", activeCount.toString(),
                        if (activeCount > 0) CriticalColor else EmeraldGreen)
                    VerticalDivider(modifier = Modifier.height(40.dp), color = ZincBorder)
                    DeviceStat(
                        "Críticas",
                        alarms.count { it.isCritical && it.isActive }.toString(),
                        CriticalColor
                    )
                }
            }
        }

        items(alarms, key = { it.id.id }) { alarm ->
            AlarmCard(alarm = alarm, onAck = viewModel::acknowledgeAlarm, onClear = viewModel::clearAlarm)
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun FiltersTab(
    device: Device,
    alarmTypes: List<String>,
    filters: List<AlarmFilterStore.AlarmFilter>,
    filterStore: AlarmFilterStore,
    scope: kotlinx.coroutines.CoroutineScope
) {
    if (alarmTypes.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Default.Tune, null,
                    tint = ZincBorder, modifier = Modifier.size(64.dp))
                Text("No hay tipos de alarma registrados", color = ZincMuted, fontSize = 14.sp)
                Text("Los tipos aparecen cuando llegan alarmas", color = ZincMuted, fontSize = 12.sp)
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
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
                    Icon(Icons.Default.Info, null, tint = ZincMuted, modifier = Modifier.size(16.dp))
                    Text(
                        "Los cambios se aplican localmente en este dispositivo. Las alarmas siguen existiendo en el servidor.",
                        color = ZincMuted, fontSize = 12.sp
                    )
                }
            }
        }

        items(alarmTypes) { alarmType ->
            val filter = filters.find { it.deviceId == device.id.id && it.alarmType == alarmType }
            val isMuted  = filter?.muted  ?: false
            val isHidden = filter?.hidden ?: false

            AlarmTypeFilterCard(
                alarmType = alarmType,
                isMuted   = isMuted,
                isHidden  = isHidden,
                onMutedChange = { newMuted ->
                    scope.launch {
                        filterStore.setFilter(
                            AlarmFilterStore.AlarmFilter(
                                deviceId   = device.id.id,
                                deviceName = device.name,
                                alarmType  = alarmType,
                                muted      = newMuted,
                                hidden     = isHidden
                            )
                        )
                    }
                },
                onHiddenChange = { newHidden ->
                    scope.launch {
                        filterStore.setFilter(
                            AlarmFilterStore.AlarmFilter(
                                deviceId   = device.id.id,
                                deviceName = device.name,
                                alarmType  = alarmType,
                                muted      = isMuted,
                                hidden     = newHidden
                            )
                        )
                    }
                }
            )
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun AlarmTypeFilterCard(
    alarmType: String,
    isMuted: Boolean,
    isHidden: Boolean,
    onMutedChange: (Boolean) -> Unit,
    onHiddenChange: (Boolean) -> Unit
) {
    val isFullyDisabled = isMuted && isHidden

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isFullyDisabled) ZincCard.copy(alpha = 0.5f) else ZincCard,
        border = BorderStroke(
            1.dp,
            if (isFullyDisabled) ZincBorder.copy(alpha = 0.4f) else ZincBorder
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Nombre del tipo
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (isFullyDisabled) Icons.Default.NotificationsOff
                                  else Icons.Default.Notifications,
                    contentDescription = null,
                    tint = if (isFullyDisabled) ZincMuted else RedPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    alarmType.replace("_", " "),
                    color = if (isFullyDisabled) ZincMuted else ZincText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                // Badge de estado
                if (isMuted || isHidden) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = ZincBorder.copy(alpha = 0.5f)
                    ) {
                        Text(
                            when {
                                isMuted && isHidden -> "Silenciada y oculta"
                                isMuted             -> "Silenciada"
                                else                -> "Oculta"
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            color = ZincMuted, fontSize = 10.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = ZincBorder)
            Spacer(Modifier.height(12.dp))

            // Toggle: Silenciar notificaciones
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Silenciar notificaciones", color = ZincText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("No alerta ni suena, pero aparece en la lista",
                        color = ZincMuted, fontSize = 11.sp)
                }
                Switch(
                    checked = isMuted,
                    onCheckedChange = onMutedChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = OrangeAlert,
                        uncheckedThumbColor = ZincMuted,
                        uncheckedTrackColor = ZincBorder
                    )
                )
            }

            Spacer(Modifier.height(8.dp))

            // Toggle: Ocultar de la lista
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Ocultar de la lista", color = ZincText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text("No aparece en esta pantalla (sigue en el servidor)",
                        color = ZincMuted, fontSize = 11.sp)
                }
                Switch(
                    checked = isHidden,
                    onCheckedChange = onHiddenChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = ZincMuted,
                        uncheckedThumbColor = ZincMuted,
                        uncheckedTrackColor = ZincBorder
                    )
                )
            }
        }
    }
}

@Composable
private fun DeviceStat(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = valueColor, fontSize = 24.sp, fontWeight = FontWeight.Black)
        Text(label, color = ZincMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}
