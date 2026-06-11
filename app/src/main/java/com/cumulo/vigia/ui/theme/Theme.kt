package com.cumulo.vigia.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand colors - dark industrial palette
val RedPrimary = Color(0xFFDC2626)
val RedLight = Color(0xFFEF4444)
val RedDark = Color(0xFF991B1B)

val EmeraldGreen = Color(0xFF10B981)
val EmeraldDark = Color(0xFF059669)

val AmberWarning = Color(0xFFF59E0B)
val OrangeAlert = Color(0xFFF97316)

val ZincBg = Color(0xFF09090B)
val ZincSurface = Color(0xFF18181B)
val ZincCard = Color(0xFF27272A)
val ZincBorder = Color(0xFF3F3F46)
val ZincMuted = Color(0xFF71717A)
val ZincText = Color(0xFFFAFAFA)
val ZincTextMuted = Color(0xFFA1A1AA)

val CriticalColor = Color(0xFFDC2626)
val MajorColor = Color(0xFFF97316)
val MinorColor = Color(0xFFF59E0B)
val WarningColor = Color(0xFF3B82F6)
val IndeterminateColor = Color(0xFF71717A)

fun severityColor(severity: String): Color = when (severity) {
    "CRITICAL" -> CriticalColor
    "MAJOR" -> MajorColor
    "MINOR" -> MinorColor
    "WARNING" -> WarningColor
    else -> IndeterminateColor
}

private val DarkColorScheme = darkColorScheme(
    primary = RedPrimary,
    onPrimary = Color.White,
    primaryContainer = RedDark,
    secondary = EmeraldGreen,
    onSecondary = Color.White,
    background = ZincBg,
    surface = ZincSurface,
    onBackground = ZincText,
    onSurface = ZincText,
    surfaceVariant = ZincCard,
    outline = ZincBorder,
    error = RedLight
)

@Composable
fun VigiaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
