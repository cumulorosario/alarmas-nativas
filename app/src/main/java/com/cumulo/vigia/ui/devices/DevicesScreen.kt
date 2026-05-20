package com.cumulo.vigia.ui.devices

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cumulo.vigia.ui.DeviceCard
import com.cumulo.vigia.ui.VigiaViewModel
import com.cumulo.vigia.ui.theme.*

@Composable
fun DevicesScreen(viewModel: VigiaViewModel) {
    val state by viewModel.dashboardState.collectAsState()

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
            DeviceCard(device = device)
        }

        item { Spacer(Modifier.height(80.dp)) }
    }
}
