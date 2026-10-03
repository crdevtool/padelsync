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

    /** A padel court's blue turf, lit at the net and darker towards the back walls. */
    val PadelTurf = Color(0xFF1257C2)
    val PadelTurfDeep = Color(0xFF082E73)

    /** A hard tennis court: green surround, blue playing area. */
    val TennisSurround = Color(0xFF1F6B45)
    val TennisSurroundDeep = Color(0xFF0F3D27)
    val TennisCourt = Color(0xFF1F5FA8)

    val CourtLine = Color(0xFFF4F7FB)

    /** The band across the net that carries the call-outs and Undo. */
    val NetBand = Color(0xFF0C1524)
    val Ball = Color(0xFFD7F23C)
    val Gold = Color(0xFFFFD24A)
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
