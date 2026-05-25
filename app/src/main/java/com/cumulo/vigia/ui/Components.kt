package com.cumulo.vigia.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cumulo.vigia.model.Alarm
import com.cumulo.vigia.model.Device
import com.cumulo.vigia.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SeverityBadge(severity: String) {
    val color = severityColor(severity)
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f))
    ) {
        Text(
            severity,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun StatusBadge(status: String, displayStatus: String) {
    val color = when (status) {
        "ACTIVE_UNACK"  -> CriticalColor
        "ACTIVE_ACK"    -> OrangeAlert
        "CLEARED_UNACK" -> EmeraldGreen
        "CLEARED_ACK"   -> EmeraldGreen
        else            -> ZincMuted
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = if (status == "ACTIVE_UNACK") 0.9f else 0.15f)
    ) {
        Text(
            displayStatus,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            color = if (status == "ACTIVE_UNACK") Color.White else color,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
        )
    }
}

@Composable
fun AlarmCard(
    alarm: Alarm,
    onAck: (String) -> Unit,
    onClear: (String) -> Unit
) {
    val borderColor = when {
        alarm.isCleared                  -> EmeraldGreen.copy(alpha = 0.4f)
        alarm.severity == "CRITICAL"     -> CriticalColor.copy(alpha = 0.6f)
        alarm.severity == "MAJOR"        -> OrangeAlert.copy(alpha = 0.5f)
        else                             -> ZincBorder
    }
    val bgColor = when {
        alarm.isCleared                  -> ZincCard.copy(alpha = 0.4f)
        alarm.severity == "CRITICAL"     -> CriticalColor.copy(alpha = 0.05f)
        else                             -> ZincCard
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = bgColor,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row: severity + status + timestamp
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SeverityBadge(alarm.severity)
                StatusBadge(alarm.status, alarm.displayStatus())
                Spacer(Modifier.weight(1f))
                Text(
                    formatTimestamp(alarm.createdTime),
                    color = ZincMuted,
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                alarm.displayType(),
                color = if (alarm.isCleared) ZincTextMuted else ZincText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(Icons.Default.Memory, null, tint = ZincMuted, modifier = Modifier.size(14.dp))
                Text(alarm.originatorName, color = ZincTextMuted, fontSize = 13.sp)
            }

            Spacer(Modifier.height(12.dp))

            // Action buttons — logic:
            // ACTIVE + UNACK  → solo "RECONOCER" (también silencia la notificación)
            // ACTIVE + ACK    → solo "Resolver"
            // CLEARED         → badge "Cerrada"
            when {
                alarm.isActive && !alarm.isAcknowledged -> {
                    Button(
                        onClick = { onAck(alarm.id.id) },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = CriticalColor)
                    ) {
                        Icon(Icons.Default.NotificationsOff, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("RECONOCER Y SILENCIAR", fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    }
                }
                alarm.isActive && alarm.isAcknowledged -> {
                    Button(
                        onClick = { onClear(alarm.id.id) },
                        modifier = Modifier.fillMaxWidth().height(40.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = EmeraldGreen.copy(alpha = 0.15f))
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = EmeraldGreen, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Resolver", color = EmeraldGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                else -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = EmeraldGreen, modifier = Modifier.size(16.dp))
                        Text("Cerrada", color = EmeraldGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceCard(
    device: Device,
    onClick: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = ZincCard,
        border = BorderStroke(1.dp, ZincBorder),
        onClick = onClick ?: {}
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(ZincBorder.copy(alpha = 0.5f), RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Memory, null, tint = ZincMuted, modifier = Modifier.size(24.dp))
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, color = ZincText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(device.type.uppercase(), color = ZincMuted, fontSize = 10.sp, letterSpacing = 1.sp)
            }

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (device.online) EmeraldGreen else ZincBorder,
                            CircleShape
                        )
                )
                Text(
                    if (device.online) "ONLINE" else "OFFLINE",
                    color = if (device.online) EmeraldGreen else ZincMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                if (onClick != null) {
                    Icon(Icons.Default.ChevronRight, null, tint = ZincMuted, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
fun StatCard(
    icon: ImageVector,
    label: String,
    value: String,
    iconColor: Color,
    onClick: (() -> Unit)? = null
) {
    Surface(
        modifier = Modifier.fillMaxWidth().fillMaxHeight(),
        shape = RoundedCornerShape(20.dp),
        color = ZincCard,
        border = BorderStroke(1.dp, ZincBorder),
        onClick = onClick ?: {}
    ) {
        Column(modifier = Modifier.padding(20.dp).fillMaxHeight()) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(iconColor.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text(label, color = ZincMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            Text(value, color = ZincText, fontSize = 28.sp, fontWeight = FontWeight.Black)
        }
    }
}

fun formatTimestamp(ts: Long): String {
    return try {
        val sdf = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        sdf.format(Date(ts))
    } catch (e: Exception) {
        "—"
    }
}
