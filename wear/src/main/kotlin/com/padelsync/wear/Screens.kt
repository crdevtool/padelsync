package com.padelsync.wear

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.Team
import com.netsports.core.match.Action
import com.netsports.core.ui.ScoreView
import com.padelsync.kit.BlePermissions
import com.padelsync.kit.Labels
import com.padelsync.kit.CourtController
import com.padelsync.kit.CourtMode
import com.padelsync.kit.CourtUiState
import com.padelsync.kit.NearbyCourt
import kotlinx.coroutines.delay

@Composable
private fun PrimaryChip(label: String, onClick: () -> Unit) {
    Chip(
        label = { Text(label, fontWeight = FontWeight.Bold) },
        onClick = onClick,
        colors = ChipDefaults.primaryChipColors(
            backgroundColor = WearPalette.Accent,
            contentColor = WearPalette.OnAccent,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SecondaryChip(label: String, onClick: () -> Unit) {
    Chip(
        label = { Text(label) },
        onClick = onClick,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun HomeScreen(
    hasSavedMatch: Boolean,
    onPadel: () -> Unit,
    onTennis: () -> Unit,
    onJoin: () -> Unit,
    onResume: () -> Unit,
) {
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { Text("PadelSync", color = WearPalette.Accent, fontWeight = FontWeight.Black, fontSize = 18.sp) }
        item { PrimaryChip("New padel match", onPadel) }
        item { SecondaryChip("New tennis match", onTennis) }
        item { SecondaryChip("Join a court", onJoin) }
        if (hasSavedMatch) {
            item { SecondaryChip("Resume last match", onResume) }
        }
    }
}

@Composable
fun MessageScreen(
    title: String,
    body: String,
    button: String,
    onClick: () -> Unit,
    primaryButton: String? = null,
    onPrimaryClick: () -> Unit = {},
) {
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = TextAlign.Center) }
        item { Text(body, color = WearPalette.Muted, fontSize = 13.sp, textAlign = TextAlign.Center) }
        if (primaryButton != null) {
            item { PrimaryChip(primaryButton, onPrimaryClick) }
        }
        item { SecondaryChip(button, onClick) }
    }
}

/**
 * The end of a match on the watch: who won, the score, and what to do next.
 *
 * @param winner the team that won, or `null` for a match that ended level.
 */
@Composable
fun WinnerScreen(
    score: ScoreView,
    winner: Team?,
    onRematch: (() -> Unit)?,
    onUndo: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { Text(if (winner == null) "🤝" else "🏆", fontSize = 34.sp) }
        item {
            Text(
                Labels.resultHeadline(score, winner),
                color = when (winner) {
                    Team.A -> WearPalette.TeamA
                    Team.B -> WearPalette.TeamB
                    null -> WearPalette.Accent
                },
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
            )
        }
        item { Text(score.setSummary, fontWeight = FontWeight.Black, fontSize = 20.sp, textAlign = TextAlign.Center) }
        if (onRematch != null) {
            item { PrimaryChip("Rematch", onRematch) }
        }
        item { SecondaryChip("Scoreboard", onDismiss) }
        if (onUndo != null) {
            item { SecondaryChip(Labels.undoResult(score), onUndo) }
        }
    }
}

/** Lists courts being hosted nearby. */
@Composable
fun JoinScreen(
    controller: CourtController,
    error: String?,
    onChoose: (NearbyCourt) -> Unit,
    onBack: () -> Unit,
) {
    val courts by controller.nearby.collectAsState()
    val gate = rememberBluetoothGate()
    val context = LocalContext.current
    val locationOff = rememberLocationOff()
    var noSettingsScreen by remember { mutableStateOf(false) }

    // Searching starts as soon as it can find anything: straight away, or
    // when the player comes back from switching Location on, with no tap.
    LaunchedEffect(locationOff) {
        if (locationOff) controller.stopScan() else gate { controller.startScan() }
    }
    DisposableEffect(Unit) { onDispose { controller.stopScan() } }

    ScalingLazyColumn(Modifier.fillMaxSize()) {
        item { Text("Join a court", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        if (locationOff) {
            // Wear OS 3 (Android 11): no court can be found while Location is off.
            item { Text(Labels.LOCATION_OFF_TITLE, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center) }
            item { Text(Labels.LOCATION_OFF_BODY, color = WearPalette.Muted, fontSize = 13.sp, textAlign = TextAlign.Center) }
            item {
                PrimaryChip(Labels.LOCATION_OFF_BUTTON) {
                    noSettingsScreen = !BlePermissions.openLocationSettings(context)
                }
            }
            if (noSettingsScreen) {
                item {
                    Text(
                        Labels.LOCATION_OFF_NO_SETTINGS,
                        color = WearPalette.Muted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else if (error != null) {
            item { Text(error, color = WearPalette.Danger, fontSize = 13.sp, textAlign = TextAlign.Center) }
            item { SecondaryChip("Try again") { gate { controller.startScan() } } }
        } else if (courts.isEmpty()) {
            item { Text("Looking nearby…", color = WearPalette.Muted, fontSize = 13.sp) }
        }
        items(courts, key = { it.id }) { court ->
            PrimaryChip(court.name) { onChoose(court) }
        }
        item { SecondaryChip("Back", onBack) }
    }
}

/** A numeric keypad for the 4-digit join code. */
@Composable
fun CodeScreen(onBack: () -> Unit, onDone: (Int?) -> Unit) {
    var code by remember { mutableStateOf("") }
    val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("<", "0", "OK"))

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 30.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            code.padEnd(4, '•'),
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            color = WearPalette.Accent,
        )
        Spacer(Modifier.height(4.dp))
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(vertical = 2.dp)) {
                for (key in row) {
                    val isOk = key == "OK"
                    val enabled = !isOk || code.length == 4
                    Box(
                        Modifier
                            .weight(1f)
                            .height(30.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isOk && enabled) WearPalette.Accent else WearPalette.Key)
                            .clickable(enabled = enabled) {
                                when (key) {
                                    "<" -> if (code.isEmpty()) onBack() else code = code.dropLast(1)
                                    "OK" -> onDone(code.toIntOrNull())
                                    else -> if (code.length < 4) code += key
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            key,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isOk && enabled) WearPalette.OnAccent else androidx.compose.ui.graphics.Color.White,
                        )
                    }
                }
            }
        }
    }
}

/** Everything that is not scoring: sharing the court, a new match, leaving. */
@Composable
fun MenuScreen(ui: CourtUiState, controller: CourtController, onTakeOver: () -> Unit, onClose: () -> Unit) {
    val gate = rememberBluetoothGate()
    val hosting = ui.mode == CourtMode.HOST

    // A problem is shown for a while, then cleared.
    LaunchedEffect(ui.error) {
        if (ui.error != null) {
            delay(8000)
            controller.clearError()
        }
    }

    ScalingLazyColumn(Modifier.fillMaxSize()) {
        if (hosting && ui.courtOpen) {
            item {
                Text(
                    "Code ${ui.joinCode}",
                    color = WearPalette.Accent,
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp,
                )
            }
            item {
                Text(
                    if (ui.deviceCount == 1) "Waiting for players" else "${ui.deviceCount} devices",
                    color = WearPalette.Muted,
                    fontSize = 13.sp,
                )
            }
        }
        if (ui.error != null) {
            item { Text(ui.error.orEmpty(), color = WearPalette.Danger, fontSize = 13.sp, textAlign = TextAlign.Center) }
        }
        item { PrimaryChip("Back to score", onClose) }
        if (ui.canTakeOver) {
            // The host has been out of reach for a while; this watch holds a full copy of the match.
            item { SecondaryChip(Labels.TAKE_OVER_BUTTON_SHORT, onTakeOver) }
        }
        val score = ui.score
        if (score != null && score.canBeFinished && ui.canScore) {
            // A timed match has no last point: someone has to say it is over.
            item {
                SecondaryChip(Labels.FINISH_MATCH) {
                    controller.tap(Action.FINISH)
                    onClose()
                }
            }
        }
        val server = score?.server
        if (score != null && score.doubles && server != null && ui.canScore) {
            // Players choose their serving order each set; this corrects the app's guess.
            item {
                SecondaryChip("Swap server") {
                    controller.tap(Action.swapServerFor(server))
                    onClose()
                }
            }
        }
        item {
            SecondaryChip(if (ui.speech.enabled) "Voice: on" else "Voice: off") {
                controller.setSpeech(ui.speech.copy(enabled = !ui.speech.enabled))
            }
        }
        if (hosting) {
            item {
                SecondaryChip(if (ui.guestsCanScore) "Others can score: yes" else "Others can score: no") {
                    controller.setGuestsCanScore(!ui.guestsCanScore)
                }
            }
            if (ui.courtOpen) {
                item { SecondaryChip("Stop sharing") { controller.closeCourt() } }
            } else {
                item { SecondaryChip("Play with others") { gate { controller.openCourt() } } }
            }
            item {
                SecondaryChip("New padel match") {
                    controller.startNewMatch(MatchConfig.padel().copy(playAllSets = true))
                    onClose()
                }
            }
            item {
                SecondaryChip("New tennis match") {
                    controller.startNewMatch(MatchConfig.tennis())
                    onClose()
                }
            }
        }
        item {
            SecondaryChip(if (hosting) "End match" else "Leave court") {
                controller.leave()
                onClose()
            }
        }
    }
}

/** A small pill-shaped button used on the score screen. */
@Composable
fun PillKey(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = 46.dp, height = 30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(WearPalette.Key)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            color = if (enabled) androidx.compose.ui.graphics.Color.White else WearPalette.Muted,
        )
    }
}
