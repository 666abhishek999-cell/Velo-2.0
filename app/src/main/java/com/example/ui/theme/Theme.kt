package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val VeloStreamColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = Color(0xFF00363D),
    primaryContainer = Color(0xFF004F58),
    onPrimaryContainer = Color(0xFF97F0FF),

    secondary = NeonCrimson,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF8C0034),
    onSecondaryContainer = Color(0xFFFFD9E2),

    tertiary = EmeraldMatrix,
    onTertiary = Color(0xFF00382B),
    tertiaryContainer = Color(0xFF00513F),
    onTertiaryContainer = Color(0xFF73F8D3),

    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,

    outline = CardBorder,
    outlineVariant = Color(0xFF1E293B),

    error = DarkError,
    onError = Color.White
)

@Composable
fun VeloStreamTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = VeloStreamColorScheme,
        typography = Typography,
        content = content
    )
}
