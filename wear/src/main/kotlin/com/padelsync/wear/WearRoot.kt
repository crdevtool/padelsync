package com.padelsync.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.netsports.core.engine.MatchConfig
import com.netsports.core.sync.ClientStatus
import com.padelsync.kit.CourtController
import com.padelsync.kit.CourtMode
import com.padelsync.kit.Labels
import com.padelsync.kit.NearbyCourt

/** Colours matching the phone app; see its Palette for the reasoning. */
object WearPalette {
    val TeamA = Color(0xFF4DA3FF)
    val TeamB = Color(0xFFFF9B3D)
    val Accent = Color(0xFFD7F23C)
    val OnAccent = Color(0xFF0B1E3A)
    val Muted = Color(0xFFB4BCC8)
    val Danger = Color(0xFFFF6B6B)
    val Key = Color(0xFF232A36)
}

private enum class WearScreen { HOME, JOIN, CODE, MENU }

@Composable
fun WearRoot(controller: CourtController) {
    val ui by controller.ui.collectAsState()
    var screen by remember { mutableStateOf(WearScreen.HOME) }
    var chosen by remember { mutableStateOf<NearbyCourt?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val score = ui.score
        when {
            ui.mode == CourtMode.GUEST && ui.guestStatus == ClientStatus.REJECTED -> MessageScreen(
                title = "Could not join",
                body = Labels.rejection(ui.rejection),
                button = "Back",
                onClick = {
                    controller.leave()
                    screen = WearScreen.HOME
                },
            )

            ui.mode != CourtMode.IDLE && score == null -> MessageScreen(
                title = "Connecting…",
                body = "Stay near the host.",
                button = "Cancel",
                onClick = {
                    controller.leave()
                    screen = WearScreen.HOME
                },
            )

            ui.mode != CourtMode.IDLE && screen == WearScreen.MENU -> MenuScreen(
                ui = ui,
                controller = controller,
                onClose = { screen = WearScreen.HOME },
            )

            ui.mode != CourtMode.IDLE && score != null -> ScoreScreen(
                ui = ui,
                score = score,
                controller = controller,
                onMenu = { screen = WearScreen.MENU },
            )

            screen == WearScreen.JOIN -> JoinScreen(
                controller = controller,
                error = ui.error,
                onChoose = {
                    chosen = it
                    screen = WearScreen.CODE
                },
                onBack = { screen = WearScreen.HOME },
            )

            screen == WearScreen.CODE -> CodeScreen(
                onBack = { screen = WearScreen.JOIN },
                onDone = { code ->
                    val court = chosen
                    screen = WearScreen.HOME
                    if (court != null) controller.join(court, code)
                },
            )

            else -> HomeScreen(
                hasSavedMatch = ui.hasSavedMatch,
                onPadel = { controller.startMatch(MatchConfig.padel()) },
                onTennis = { controller.startMatch(MatchConfig.tennis()) },
                onJoin = { screen = WearScreen.JOIN },
                onResume = { controller.resumeSavedMatch() },
            )
        }
    }
}
