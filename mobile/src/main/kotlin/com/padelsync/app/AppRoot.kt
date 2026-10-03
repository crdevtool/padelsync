package com.padelsync.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.padelsync.kit.CourtController
import com.padelsync.kit.CourtMode

private enum class Screen { HOME, SETUP, JOIN }

/** Chooses the screen. A live match always wins, so the app reopens on the scoreboard. */
@Composable
fun AppRoot(controller: CourtController) {
    val ui by controller.ui.collectAsState()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    // Set when the host picks "New match" from the scoreboard.
    var replacingMatch by rememberSaveable { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .safeDrawingPadding(),
    ) {
        when {
            ui.mode != CourtMode.IDLE && !replacingMatch -> ScoreScreen(
                ui = ui,
                controller = controller,
                onNewMatch = { replacingMatch = true },
                onLeft = { screen = Screen.HOME },
            )

            replacingMatch || screen == Screen.SETUP -> {
                BackHandler {
                    replacingMatch = false
                    screen = Screen.HOME
                }
                SetupScreen(
                    onStart = { config ->
                        if (replacingMatch) controller.startNewMatch(config) else controller.startMatch(config)
                        replacingMatch = false
                        screen = Screen.HOME
                    },
                    onBack = {
                        replacingMatch = false
                        screen = Screen.HOME
                    },
                )
            }

            screen == Screen.JOIN -> {
                BackHandler { screen = Screen.HOME }
                JoinScreen(controller = controller, error = ui.error, onBack = { screen = Screen.HOME })
            }

            else -> HomeScreen(
                hasSavedMatch = ui.hasSavedMatch,
                onNewMatch = { screen = Screen.SETUP },
                onJoin = { screen = Screen.JOIN },
                onResume = { controller.resumeSavedMatch() },
            )
        }
    }
}
