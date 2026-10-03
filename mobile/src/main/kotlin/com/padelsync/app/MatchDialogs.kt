package com.padelsync.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netsports.core.engine.Team
import com.netsports.core.match.Roster
import com.netsports.core.sync.DeviceKind
import com.netsports.core.sync.PeerInfo
import com.netsports.core.ui.ScoreView
import com.netsports.core.ui.SpeechSettings

/** Host only: which of the joined devices may change the score. */
@Composable
fun WhoCanScoreDialog(
    guests: List<PeerInfo>,
    guestsCanScore: Boolean,
    onEveryone: (Boolean) -> Unit,
    onDevice: (deviceId: Long, allowed: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Who can score") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SwitchRow(
                    title = "Everyone who joins",
                    caption = if (guestsCanScore) {
                        "New devices can add points as soon as they join"
                    } else {
                        "New devices only watch until you allow them"
                    },
                    checked = guestsCanScore,
                    onCheckedChange = onEveryone,
                )
                Spacer(Modifier.height(8.dp))
                if (guests.isEmpty()) {
                    Text("No other device has joined yet.", fontSize = 15.sp, color = Palette.Muted)
                } else {
                    Text("On this court", fontSize = 14.sp, color = Palette.Muted, fontWeight = FontWeight.Bold)
                    for (guest in guests) {
                        SwitchRow(
                            title = guest.deviceName.ifBlank { "Unnamed device" },
                            caption = if (guest.deviceKind == DeviceKind.WATCH) "Watch" else "Phone",
                            checked = guest.canScore,
                            onCheckedChange = { onDevice(guest.deviceId, it) },
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("This phone, as the host, can always score.", fontSize = 13.sp, color = Palette.Muted)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

/** What this device says out loud, and how often. */
@Composable
fun VoiceDialog(
    settings: SpeechSettings,
    available: Boolean,
    onChange: (SpeechSettings) -> Unit,
    onSayScore: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voice") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!available) {
                    Text(
                        "This phone has no text-to-speech voice installed. Add one in the phone's " +
                            "Settings under Accessibility or Languages, then try again.",
                        fontSize = 15.sp,
                        color = Palette.Danger,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                SwitchRow(
                    title = "Call the score out loud",
                    caption = "On this phone only. Uses the media volume.",
                    checked = settings.enabled,
                    onCheckedChange = { onChange(settings.copy(enabled = it)) },
                )
                val on = settings.enabled
                SwitchRow("Every point", settings.points, { onChange(settings.copy(points = it)) }, "\"30 15\", \"Deuce\"", on)
                SwitchRow("Games, sets and match", settings.games, { onChange(settings.copy(games = it)) }, "\"Game, Ana and Leo\"", on)
                SwitchRow("Big points", settings.stakes, { onChange(settings.copy(stakes = it)) }, "Break, set and match point", on)
                SwitchRow("Who serves", settings.server, { onChange(settings.copy(server = it)) }, "At the start of each game", on)
                SwitchRow("Change ends", settings.changeEnds, { onChange(settings.copy(changeEnds = it)) }, enabled = on)
                OptionGroup(
                    title = "Repeat the whole score every",
                    options = listOf(0, 2, 5, 10),
                    selected = settings.reminderMinutes,
                    label = { if (it == 0) "Never" else "$it min" },
                    onSelect = { onChange(settings.copy(reminderMinutes = it)) },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = onSayScore) { Text("Say the score now") } },
    )
}

/** Host only: correct or add the players' names during a match. */
@Composable
fun PlayersDialog(score: ScoreView, onSave: (Roster) -> Unit, onDismiss: () -> Unit) {
    var a1 by rememberSaveable { mutableStateOf(score.playersA.getOrNull(0).orEmpty()) }
    var a2 by rememberSaveable { mutableStateOf(score.playersA.getOrNull(1).orEmpty()) }
    var b1 by rememberSaveable { mutableStateOf(score.playersB.getOrNull(0).orEmpty()) }
    var b2 by rememberSaveable { mutableStateOf(score.playersB.getOrNull(1).orEmpty()) }
    val doubles = score.doubles

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Players") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Team A players", fontSize = 14.sp, color = Palette.TeamA, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NameField(a1, { a1 = it }, if (doubles) "Player 1" else "Player", Modifier.weight(1f))
                    if (doubles) NameField(a2, { a2 = it }, "Player 2", Modifier.weight(1f))
                }
                Text("Team B players", fontSize = 14.sp, color = Palette.TeamB, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NameField(b1, { b1 = it }, if (doubles) "Player 1" else "Player", Modifier.weight(1f), last = !doubles)
                    if (doubles) NameField(b2, { b2 = it }, "Player 2", Modifier.weight(1f), last = true)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(if (doubles) Roster.of(listOf(a1, a2), listOf(b1, b2)) else Roster.of(listOf(a1), listOf(b1)))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The player in [team] who would serve if its serving order were swapped now. */
fun otherServer(score: ScoreView, team: Team): String? =
    score.playersOf(team).getOrNull(1 - score.serverPlayerIndex)
