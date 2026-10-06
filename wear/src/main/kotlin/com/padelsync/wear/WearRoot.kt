package com.padelsync.wear

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.foundation.BasicSwipeToDismissBox
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

private enum class WearScreen { HOME, JOIN, CODE, MENU, TAKE_OVER }

@Composable
fun WearRoot(controller: CourtController) {
    val ui by controller.ui.collectAsState()
    var screen by remember { mutableStateOf(WearScreen.HOME) }
    var chosen by remember { mutableStateOf<NearbyCourt?>(null) }
    val gate = rememberBluetoothGate()
    val activity = LocalContext.current as? Activity

    // The host came back while the take-over question was open: withdraw it,
    // or it would pop up by itself the next time the host is lost.
    LaunchedEffect(ui.canTakeOver) {
        if (!ui.canTakeOver && screen == WearScreen.TAKE_OVER) screen = WearScreen.HOME
    }

    // Kept here rather than on the score screen, which is rebuilt on every
    // return from the menu: a result put away stays put away.
    val winner = ui.score?.winner
    var resultDismissed by remember(winner) { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val score = ui.score
        when {
            ui.mode == CourtMode.GUEST && ui.guestStatus == ClientStatus.REJECTED -> {
                val back = {
                    controller.leave()
                    screen = WearScreen.HOME
                }
                BackOnSwipe(onBack = back) {
                    MessageScreen(
                        title = "Could not join",
                        body = Labels.rejection(ui.rejection),
                        button = "Back",
                        onClick = back,
                    )
                }
            }

            // Asked before taking over as host: only one player should.
            ui.mode == CourtMode.GUEST && ui.canTakeOver && screen == WearScreen.TAKE_OVER ->
                BackOnSwipe(onBack = { screen = WearScreen.HOME }) {
                    MessageScreen(
                        title = Labels.TAKE_OVER_BUTTON_SHORT + "?",
                        body = Labels.TAKE_OVER_BODY,
                        button = "Cancel",
                        onClick = { screen = WearScreen.HOME },
                        primaryButton = Labels.TAKE_OVER_CONFIRM,
                        onPrimaryClick = {
                            screen = WearScreen.HOME
                            gate { controller.takeOverAsHost() }
                        },
                    )
                }

            ui.mode == CourtMode.GUEST && ui.guestStatus == ClientStatus.ENDED -> {
                val back = {
                    controller.leave()
                    screen = WearScreen.HOME
                }
                BackOnSwipe(onBack = back) {
                    MessageScreen(
                        title = Labels.COURT_CLOSED_TITLE,
                        body = Labels.COURT_CLOSED_BODY,
                        button = "Back",
                        onClick = back,
                        primaryButton = Labels.TAKE_OVER_BUTTON_SHORT.takeIf { ui.canTakeOver },
                        onPrimaryClick = { screen = WearScreen.TAKE_OVER },
                    )
                }
            }

            ui.mode != CourtMode.IDLE && score == null -> {
                val back = {
                    controller.leave()
                    screen = WearScreen.HOME
                }
                BackOnSwipe(onBack = back) {
                    MessageScreen(
                        title = "Connecting…",
                        body = "Stay near the host.",
                        button = "Cancel",
                        onClick = back,
                    )
                }
            }

            ui.mode != CourtMode.IDLE && screen == WearScreen.MENU ->
                BackOnSwipe(onBack = { screen = WearScreen.HOME }) {
                    MenuScreen(
                        ui = ui,
                        controller = controller,
                        onTakeOver = { screen = WearScreen.TAKE_OVER },
                        onClose = { screen = WearScreen.HOME },
                    )
                }

            ui.mode != CourtMode.IDLE && score != null -> {
                // During a match a swipe does nothing: a stray one while
                // tapping in a point must not take the scoreboard away.
                // Leaving goes through the menu. Once the match is over the
                // swipe puts the result away, like its "Scoreboard" button.
                val showingResult = score.winner != null && !resultDismissed
                BackOnSwipe(onBack = { if (showingResult) resultDismissed = true }, swipe = showingResult) {
                    ScoreScreen(
                        ui = ui,
                        score = score,
                        controller = controller,
                        resultDismissed = resultDismissed,
                        onDismissResult = { resultDismissed = true },
                        onMenu = { screen = WearScreen.MENU },
                    )
                }
            }

            screen == WearScreen.JOIN -> BackOnSwipe(onBack = { screen = WearScreen.HOME }) {
                JoinScreen(
                    controller = controller,
                    error = ui.error,
                    onChoose = {
                        chosen = it
                        screen = WearScreen.CODE
                    },
                    onBack = { screen = WearScreen.HOME },
                )
            }

            screen == WearScreen.CODE -> BackOnSwipe(onBack = { screen = WearScreen.JOIN }) {
                CodeScreen(
                    onBack = { screen = WearScreen.JOIN },
                    onDone = { code ->
                        val court = chosen
                        screen = WearScreen.HOME
                        if (court != null) controller.join(court, code)
                    },
                )
            }

            // The first screen: here, and only here, a swipe closes the app.
            else -> BackOnSwipe(onBack = { activity?.finish() }) {
                HomeScreen(
                    hasSavedMatch = ui.hasSavedMatch,
                    // Social padel is usually played to the last set whatever the score.
                    onPadel = { controller.startMatch(MatchConfig.padel().copy(playAllSets = true)) },
                    onTennis = { controller.startMatch(MatchConfig.tennis()) },
                    onJoin = { screen = WearScreen.JOIN },
                    onResume = {
                        // A court that was open when the app closed opens again.
                        if (controller.resumeSavedMatch()) gate { controller.openCourt() }
                    },
                )
            }
        }
    }
}

/**
 * Makes the watch's two ways of going back, the swipe to the right and the
 * back button, step back one screen with [onBack] instead of closing the app.
 *
 * The system's own swipe, which closes the whole app from any screen, is
 * switched off in the app's theme; this takes its place.
 *
 * @param swipe false to ignore the swipe on this screen. The back button
 * still calls [onBack].
 */
@Composable
private fun BackOnSwipe(onBack: () -> Unit, swipe: Boolean = true, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    BasicSwipeToDismissBox(onDismissed = onBack, userSwipeEnabled = swipe) { isBackground ->
        // What shows behind the screen as it slides away.
        if (isBackground) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            )
        } else {
            content()
        }
    }
}
