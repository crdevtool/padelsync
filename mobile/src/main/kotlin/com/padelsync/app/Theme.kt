package com.padelsync.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Colours chosen for a court in full sun: pure black behind, pure white for
 * the numbers, and two team colours that stay distinct for colour-blind
 * players (blue and orange).
 */
object Palette {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF14181F)
    val SurfaceHigh = Color(0xFF232A36)
    val OnBackground = Color(0xFFFFFFFF)
    val Muted = Color(0xFFB4BCC8)
    val TeamA = Color(0xFF4DA3FF)
    val TeamB = Color(0xFFFF9B3D)
    val Accent = Color(0xFFD7F23C)
    val OnAccent = Color(0xFF0B1E3A)
    val Danger = Color(0xFFFF6B6B)
}

@Composable
fun PadelSyncTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Accent,
            onPrimary = Palette.OnAccent,
            secondary = Palette.TeamA,
            background = Palette.Background,
            onBackground = Palette.OnBackground,
            surface = Palette.Surface,
            onSurface = Palette.OnBackground,
            surfaceVariant = Palette.SurfaceHigh,
            onSurfaceVariant = Palette.Muted,
            error = Palette.Danger,
        ),
        content = content,
    )
}
