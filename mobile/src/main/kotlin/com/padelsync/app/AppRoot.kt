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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.padelsync.kit.CourtController
import com.padelsync.kit.CourtMode

private enum class Screen { HOME, SETUP_SOLO, SETUP_HOST, JOIN, HISTORY }

/** Chooses the screen. A live match always wins, so the app reopens on the scoreboard. */
@Composable
fun AppRoot(controller: CourtController) {
    val ui by controller.ui.collectAsState()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    // Set when the host picks "New match" from the scoreboard.
    var replacingMatch by rememberSaveable { mutableStateOf(false) }

    // Lives here, above every screen, so a permission answer still arrives
    // after the screen that asked has been replaced by the scoreboard.
    val gate = rememberBluetoothGate()

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
                gate = gate,
                onNewMatch = { replacingMatch = true },
                onLeft = { screen = Screen.HOME },
            )

            replacingMatch || screen == Screen.SETUP_SOLO || screen == Screen.SETUP_HOST -> {
                val hosting = if (replacingMatch) ui.courtOpen else screen == Screen.SETUP_HOST
                val back = {
                    replacingMatch = false
                    screen = Screen.HOME
                }
                BackHandler(onBack = back)
                // Read once per visit: the form owns the values from then on.
                val initial = remember { controller.lastSetup }
                SetupScreen(
                    title = when {
                        replacingMatch -> "New match"
                        hosting -> "Host a match"
                        else -> "New match"
                    },
                    startLabel = if (hosting && !replacingMatch) "Start and open the court" else "Start match",
                    hosting = hosting,
                    initial = initial,
                    voiceOn = ui.speech.enabled,
                    onVoiceChange = { controller.setSpeech(ui.speech.copy(enabled = it)) },
                    onStart = { setup ->
                        if (replacingMatch) {
                            controller.startNewMatch(setup)
                        } else {
                            controller.startMatch(setup)
                            if (hosting) gate { controller.openCourt() }
                        }
                        replacingMatch = false
                        screen = Screen.HOME
                    },
                    onBack = back,
                )
            }

            screen == Screen.JOIN -> {
                BackHandler { screen = Screen.HOME }
                JoinScreen(controller = controller, error = ui.error, onBack = { screen = Screen.HOME })
            }

            screen == Screen.HISTORY -> {
                BackHandler { screen = Screen.HOME }
                val history by controller.history.collectAsState()
                HistoryScreen(records = history, onBack = { screen = Screen.HOME })
            }

            else -> HomeScreen(
                hasSavedMatch = ui.hasSavedMatch,
                onNewMatch = { screen = Screen.SETUP_SOLO },
                onHostMatch = { screen = Screen.SETUP_HOST },
                onJoin = { screen = Screen.JOIN },
                onResume = { controller.resumeSavedMatch() },
                onHistory = { screen = Screen.HISTORY },
            )
        }
    }
}
