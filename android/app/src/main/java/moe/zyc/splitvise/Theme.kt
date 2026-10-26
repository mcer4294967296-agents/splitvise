package moe.zyc.splitvise

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SplitviseColors = darkColorScheme(
    primary = Color(0xFFFF6F61),
    onPrimary = Color(0xFF6B2A24),
    primaryContainer = Color(0xFFF6D0CB),
    onPrimaryContainer = Color(0xFF6B2A2A),
    secondaryContainer = Color(0xFF2A2A2E),
    onSecondaryContainer = Color.White,
    background = Color(0xFF000000),
    onBackground = Color.White,
    surface = Color(0xFF000000),
    onSurface = Color.White,
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2A2A2E),
    surfaceContainerHighest = Color(0xFF141416),
    onSurfaceVariant = Color(0xFF9A9AA2),
    outline = Color(0xFF4A4A50),
)

@Composable
fun SplitviseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SplitviseColors,
        content = content,
    )
}
