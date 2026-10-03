package com.padelsync.app

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netsports.core.engine.Team
import com.netsports.core.match.Action
import com.netsports.core.sync.ClientStatus
import com.netsports.core.sync.TapFeedback
import com.netsports.core.ui.ScoreView
import com.padelsync.kit.CourtController
import com.padelsync.kit.CourtMode
import com.padelsync.kit.CourtUiState
import com.padelsync.kit.Labels
import kotlinx.coroutines.delay

/** The live scoreboard, for host and guest alike. */
@Composable
fun ScoreScreen(
    ui: CourtUiState,
    controller: CourtController,
    onNewMatch: () -> Unit,
    onLeft: () -> Unit,
) {
    val score = ui.score
    var confirmLeave by remember { mutableStateOf(false) }
    BackHandler { confirmLeave = true }

    val leave = {
        controller.leave()
        onLeft()
    }

    when {
        ui.mode == CourtMode.GUEST && ui.guestStatus == ClientStatus.REJECTED ->
            Notice(title = "Could not join", body = Labels.rejection(ui.rejection), button = "Back", onClick = leave)

        ui.mode == CourtMode.GUEST && ui.guestStatus == ClientStatus.ENDED ->
            Notice(
                title = "Court closed",
                body = "The host ended the match or stopped sharing it." +
                    (score?.setSummary?.takeIf { it.isNotEmpty() }?.let { " Final sets: $it." } ?: ""),
                button = "Back",
                onClick = leave,
            )

        score == null ->
            Notice(
                title = "Connecting…",
                body = "Joining ${ui.courtName ?: "the court"}. Keep this device near the host.",
                button = "Cancel",
                onClick = leave,
            )

        else -> Scoreboard(ui, score, controller, onNewMatch, onLeave = { confirmLeave = true })
    }

    if (confirmLeave) {
        val hosting = ui.mode == CourtMode.HOST
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(if (hosting) "End this match?" else "Leave this court?") },
            text = {
                Text(
                    if (hosting) {
                        "The match will end for every device on the court."
                    } else {
                        "The match carries on for the other players."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    leave()
                }) { Text(if (hosting) "End match" else "Leave") }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Notice(title: String, body: String, button: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.OnBackground)
        Spacer(Modifier.height(12.dp))
        Text(body, fontSize = 17.sp, color = Palette.Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        OutlinedButton(onClick = onClick) { Text(button, fontSize = 17.sp) }
    }
}

@Composable
private fun Scoreboard(
    ui: CourtUiState,
    score: ScoreView,
    controller: CourtController,
    onNewMatch: () -> Unit,
    onLeave: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val gate = rememberBluetoothGate()
    val hosting = ui.mode == CourtMode.HOST
    val finished = score.winner != null

    // A brief note when a tap did not count because another device got there first.
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.feedbackCount) {
        note = when (ui.lastFeedback) {
            TapFeedback.SUPERSEDED -> "Already scored on another device"
            TapFeedback.MATCH_COMPLETE -> "The match is over"
            else -> null
        }
        if (note != null) {
            delay(2500)
            note = null
        }
    }

    val tap = { action: Action ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        controller.tap(action)
    }

    Column(Modifier.fillMaxSize()) {
        StatusBar(ui, hosting, gate, controller, onNewMatch, onLeave)

        TeamPanel(
            team = Team.A,
            color = Palette.TeamA,
            points = score.pointsA,
            games = score.gamesA,
            sets = score.setsA,
            serving = score.server == Team.A,
            enabled = !finished,
            onClick = { tap(Action.POINT_A) },
            modifier = Modifier.weight(1f),
        )

        MiddleStrip(
            highlight = note ?: Labels.highlight(score, ui.config),
            isWarning = note != null,
            setSummary = score.setSummary,
            canUndo = score.canUndo,
            onUndo = { tap(Action.UNDO) },
        )

        TeamPanel(
            team = Team.B,
            color = Palette.TeamB,
            points = score.pointsB,
            games = score.gamesB,
            sets = score.setsB,
            serving = score.server == Team.B,
            enabled = !finished,
            onClick = { tap(Action.POINT_B) },
            modifier = Modifier.weight(1f),
        )

        if (finished && hosting) {
            Button(
                onClick = onNewMatch,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .height(56.dp),
            ) {
                Text("New match", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun StatusBar(
    ui: CourtUiState,
    hosting: Boolean,
    gate: (() -> Unit) -> Unit,
    controller: CourtController,
    onNewMatch: () -> Unit,
    onLeave: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    val (status, statusColor) = when {
        ui.error != null -> ui.error.orEmpty() to Palette.Danger
        hosting && ui.courtOpen ->
            "Court open · Code ${ui.joinCode} · ${devices(ui.deviceCount)}" to Palette.Accent
        hosting -> "This device only" to Palette.Muted
        ui.guestStatus == ClientStatus.SYNCED ->
            "${ui.courtName ?: "Court"} · ${devices(ui.deviceCount)}" to Palette.Accent
        else -> "Reconnecting…" to Palette.Danger
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            status,
            color = statusColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { menuOpen = true }) { Text("Menu", fontSize = 16.sp) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (hosting) {
                    if (ui.courtOpen) {
                        DropdownMenuItem(
                            text = { Text("Stop sharing this court") },
                            onClick = {
                                menuOpen = false
                                controller.closeCourt()
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Play with others") },
                            onClick = {
                                menuOpen = false
                                gate { controller.openCourt() }
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("New match") },
                        onClick = {
                            menuOpen = false
                            onNewMatch()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(if (hosting) "End match" else "Leave court") },
                    onClick = {
                        menuOpen = false
                        onLeave()
                    },
                )
            }
        }
    }
}

private fun devices(count: Int): String = if (count == 1) "1 device" else "$count devices"

/** Half of the screen for one team. The whole panel is the tap target. */
@Composable
private fun TeamPanel(
    team: Team,
    color: Color,
    points: String,
    games: Int,
    sets: Int,
    serving: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val name = Labels.team(team)
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(color.copy(alpha = 0.16f))
            .clickable(enabled = enabled, onClickLabel = "Point for $name", onClick = onClick)
            .semantics {
                contentDescription =
                    "$name. Points $points. Games $games. Sets $sets." + if (serving) " Serving." else ""
            }
            .padding(20.dp),
    ) {
        Row(Modifier.align(Alignment.TopStart), verticalAlignment = Alignment.CenterVertically) {
            Text(name.uppercase(), color = color, fontSize = 20.sp, fontWeight = FontWeight.Black)
            if (serving) {
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(Palette.Accent),
                )
                Spacer(Modifier.width(6.dp))
                Text("SERVE", color = Palette.Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }

        Text(
            points,
            color = Palette.OnBackground,
            fontSize = 132.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.Center),
        )

        Row(Modifier.align(Alignment.BottomStart), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Counter("GAMES", games)
            Counter("SETS", sets)
        }
    }
}

@Composable
private fun Counter(label: String, value: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value.toString(), color = Palette.OnBackground, fontSize = 40.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(8.dp))
        Text(label, color = Palette.Muted, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
    }
}

@Composable
private fun MiddleStrip(
    highlight: String?,
    isWarning: Boolean,
    setSummary: String,
    canUndo: Boolean,
    onUndo: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (highlight != null) {
                Text(
                    highlight,
                    color = if (isWarning) Palette.Danger else Palette.Accent,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            if (setSummary.isNotEmpty()) {
                Text(setSummary, color = Palette.Muted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        OutlinedButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.height(52.dp)) {
            Text("Undo", fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }
}
