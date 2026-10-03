package com.padelsync.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netsports.core.engine.Sport
import com.netsports.core.engine.Team
import com.netsports.core.match.Action
import com.netsports.core.sync.ClientStatus
import com.netsports.core.ui.MatchStats
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
    gate: (action: () -> Unit) -> Unit,
    onNewMatch: () -> Unit,
    onLeft: () -> Unit,
) {
    val score = ui.score
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmTakeOver by remember { mutableStateOf(false) }
    BackHandler { confirmLeave = true }

    // A court in the sun is no place for a screen that dims itself.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

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
                // The match need not end with the host: this device holds a full copy.
                secondButton = Labels.TAKE_OVER_BUTTON.takeIf { ui.canTakeOver },
                onSecondClick = { confirmTakeOver = true },
            )

        score == null ->
            Notice(
                title = "Connecting…",
                body = "Joining ${ui.courtName ?: "the court"}. Keep this device near the host.",
                button = "Cancel",
                onClick = leave,
            )

        else -> Scoreboard(
            ui = ui,
            score = score,
            controller = controller,
            gate = gate,
            onNewMatch = onNewMatch,
            onLeave = { confirmLeave = true },
            onTakeOver = { confirmTakeOver = true },
        )
    }

    if (confirmTakeOver) {
        AlertDialog(
            onDismissRequest = { confirmTakeOver = false },
            title = { Text(Labels.TAKE_OVER_TITLE) },
            text = { Text(Labels.TAKE_OVER_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    confirmTakeOver = false
                    gate { controller.takeOverAsHost() }
                }) { Text(Labels.TAKE_OVER_CONFIRM) }
            },
            dismissButton = { TextButton(onClick = { confirmTakeOver = false }) { Text("Cancel") } },
        )
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
private fun Notice(
    title: String,
    body: String,
    button: String,
    onClick: () -> Unit,
    secondButton: String? = null,
    onSecondClick: () -> Unit = {},
) {
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
        if (secondButton != null) {
            Spacer(Modifier.height(12.dp))
            Button(onClick = onSecondClick) { Text(secondButton, fontSize = 17.sp) }
        }
    }
}

/** Which of the match dialogs is open. */
private enum class Sheet { NONE, WHO_CAN_SCORE, VOICE, PLAYERS }

@Composable
private fun Scoreboard(
    ui: CourtUiState,
    score: ScoreView,
    controller: CourtController,
    gate: (action: () -> Unit) -> Unit,
    onNewMatch: () -> Unit,
    onLeave: () -> Unit,
    onTakeOver: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val hosting = ui.mode == CourtMode.HOST
    val winner = score.winner
    // A view-only device keeps its halves tappable: the tap is answered with
    // a note saying why it did not count, which is kinder than a dead screen.
    val canTap = winner == null
    var sheet by remember { mutableStateOf(Sheet.NONE) }

    // A brief note when a tap did not count.
    var note by remember { mutableStateOf<String?>(null) }
    OnChange(ui.feedbackCount) {
        note = Labels.tapFeedback(ui.lastFeedback)
        if (note != null) {
            delay(2500)
            note = null
        }
    }

    // A gentle buzz when someone else scores, so players know the point is in
    // and do not score it again.
    OnChange(ui.remoteScoreCount) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    // A problem (Bluetooth off, permission refused) is shown for a while,
    // then makes way for the normal status line again.
    LaunchedEffect(ui.error) {
        if (ui.error != null) {
            delay(8000)
            controller.clearError()
        }
    }

    val tap = { action: Action ->
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        controller.tap(action)
    }

    val event = rememberMatchEvent(score)

    // The winners' screen comes up by itself and can be put away to look at
    // the scoreboard. A new winner (after an undo, or a new match) brings it back.
    var celebrationDismissed by remember(winner) { mutableStateOf(false) }

    // Once the match is decided the result can be opened from the menu, even
    // if the remaining sets are never played.
    val decided = winner ?: score.decidedWinner
    var resultRequested by remember(decided) { mutableStateOf(false) }
    val showResult = (winner != null && !celebrationDismissed) || (winner == null && decided != null && resultRequested)

    // What the result screen shows. Held on to while it fades out, so an
    // undo or a rematch does not rewrite it in mid-air.
    var result by remember { mutableStateOf<Result?>(null) }
    if (showResult && decided != null) result = Result(score, decided, ui.stats, ui.durationMillis)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            StatusBar(
                ui = ui,
                score = score,
                hosting = hosting,
                gate = gate,
                controller = controller,
                onOpen = { sheet = it },
                onSwapServer = { score.server?.let { tap(Action.swapServerFor(it)) } },
                onShowResult = if (winner == null && decided != null) ({ resultRequested = true }) else null,
                onNewMatch = onNewMatch,
                onLeave = onLeave,
            )

            if (ui.canTakeOver) {
                // The host has been out of reach for a while. The match need
                // not wait for it: this device holds a full copy.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        Labels.HOST_UNREACHABLE,
                        color = Palette.Muted,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = onTakeOver) {
                        Text(Labels.TAKE_OVER_BUTTON_SHORT, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                CourtBackground(
                    sport = ui.config?.sport ?: Sport.PADEL,
                    server = score.server,
                    serveSide = score.serveSide,
                    modifier = Modifier.matchParentSize(),
                )
                Column(Modifier.matchParentSize()) {
                    TeamHalf(
                        team = Team.A,
                        atTop = true,
                        score = score,
                        pointsWon = ui.stats?.teamA?.points ?: 0,
                        streak = if (ui.stats?.streakTeam == Team.A) ui.stats?.streak ?: 0 else 0,
                        enabled = canTap,
                        onClick = { tap(Action.POINT_A) },
                        modifier = Modifier.weight(1f),
                    )
                    NetStrip(
                        ui = ui,
                        score = score,
                        note = note,
                        onUndo = { tap(Action.UNDO) },
                    )
                    TeamHalf(
                        team = Team.B,
                        atTop = false,
                        score = score,
                        pointsWon = ui.stats?.teamB?.points ?: 0,
                        streak = if (ui.stats?.streakTeam == Team.B) ui.stats?.streak ?: 0 else 0,
                        enabled = canTap,
                        onClick = { tap(Action.POINT_B) },
                        modifier = Modifier.weight(1f),
                    )
                }

                // A set is worth a little confetti of its own.
                if (event?.kind == MatchEventKind.SET || event?.kind == MatchEventKind.DECIDED) {
                    Confetti(Modifier.matchParentSize(), pieces = 60, endless = false)
                }
                // In the half of whoever won it, clear of the net strip and its Undo button.
                EventBanner(
                    event,
                    Modifier.align(BiasAlignment(0f, if (event?.team == Team.B) 0.5f else -0.5f)),
                )
            }

            if (winner != null && celebrationDismissed) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(onClick = { celebrationDismissed = false }, modifier = Modifier.weight(1f).height(54.dp)) {
                        Text("Result", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    }
                    if (hosting) {
                        Button(onClick = onNewMatch, modifier = Modifier.weight(1f).height(54.dp)) {
                            Text("New match", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = showResult, enter = fadeIn(), exit = fadeOut()) {
            val shown = result
            if (shown != null) {
                // Rematch and undo only make sense once the last point has been played.
                val over = shown.score.winner != null
                Celebration(
                    score = shown.score,
                    winner = shown.winner,
                    stats = shown.stats,
                    durationMillis = shown.durationMillis,
                    onShare = { share(context, Labels.shareText(shown.score, ui.config, shown.durationMillis)) },
                    onRematch = if (hosting && over) ({ controller.rematch() }) else null,
                    onNewMatch = if (hosting) onNewMatch else null,
                    onUndo = if (ui.canScore && over) ({ tap(Action.UNDO) }) else null,
                    onDismiss = {
                        celebrationDismissed = true
                        resultRequested = false
                    },
                )
            }
        }
    }

    when (sheet) {
        Sheet.NONE -> Unit
        Sheet.WHO_CAN_SCORE -> WhoCanScoreDialog(
            guests = ui.guests,
            guestsCanScore = ui.guestsCanScore,
            onEveryone = { controller.setGuestsCanScore(it) },
            onDevice = { deviceId, allowed -> controller.setCanScore(deviceId, allowed) },
            onDismiss = { sheet = Sheet.NONE },
        )
        Sheet.VOICE -> VoiceDialog(
            settings = ui.speech,
            available = ui.voiceAvailable,
            onChange = { controller.setSpeech(it) },
            onSayScore = { controller.sayScore() },
            onDismiss = { sheet = Sheet.NONE },
        )
        Sheet.PLAYERS -> PlayersDialog(
            score = score,
            onSave = { controller.updateRoster(it) },
            onDismiss = { sheet = Sheet.NONE },
        )
    }
}

/** Everything the result screen shows, captured at the moment it opens. */
private data class Result(val score: ScoreView, val winner: Team, val stats: MatchStats?, val durationMillis: Long?)

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    try {
        context.startActivity(Intent.createChooser(send, "Share the result"))
    } catch (e: ActivityNotFoundException) {
        // No app on this phone can take a text share; nothing to do.
    }
}

// --- Status bar and menu ----------------------------------------------------

@Composable
private fun StatusBar(
    ui: CourtUiState,
    score: ScoreView,
    hosting: Boolean,
    gate: (action: () -> Unit) -> Unit,
    controller: CourtController,
    onOpen: (Sheet) -> Unit,
    onSwapServer: () -> Unit,
    onShowResult: (() -> Unit)?,
    onNewMatch: () -> Unit,
    onLeave: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    val (status, statusColor) = when {
        ui.error != null -> ui.error.orEmpty() to Palette.Danger
        hosting && ui.courtOpen ->
            "Court open · Code ${ui.joinCode} · ${devices(ui.deviceCount)}" to Palette.Accent
        hosting -> "This device only" to Palette.Muted
        ui.guestStatus != ClientStatus.SYNCED -> "Reconnecting…" to Palette.Danger
        !ui.canScore -> "${ui.courtName ?: "Court"} · View only" to Palette.Gold
        else -> "${ui.courtName ?: "Court"} · ${devices(ui.deviceCount)}" to Palette.Accent
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            status,
            color = statusColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { menuOpen = true }) { Text("Menu", fontSize = 16.sp) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                val item: @Composable (String, () -> Unit) -> Unit = { label, action ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            menuOpen = false
                            action()
                        },
                    )
                }
                if (hosting) {
                    if (ui.courtOpen) {
                        item("Stop sharing this court") { controller.closeCourt() }
                    } else {
                        item("Play with others") { gate { controller.openCourt() } }
                    }
                    item("Who can score") { onOpen(Sheet.WHO_CAN_SCORE) }
                    item("Players") { onOpen(Sheet.PLAYERS) }
                }
                // Players choose their serving order each set; this corrects the app's guess.
                val server = score.server
                if (score.doubles && server != null && ui.canScore) {
                    val other = otherServer(score, server)
                    item(if (other != null) "Swap server to $other" else "Swap server", onSwapServer)
                }
                item("Voice") { onOpen(Sheet.VOICE) }
                if (onShowResult != null) item("Result so far", onShowResult)
                if (hosting) item("New match", onNewMatch)
                item(if (hosting) "End match" else "Leave court", onLeave)
            }
        }
    }
}

private fun devices(count: Int): String = if (count == 1) "1 device" else "$count devices"

// --- The net ----------------------------------------------------------------

/** The band across the middle: what the next point means, the sets so far, the clock, and undo. */
@Composable
private fun NetStrip(ui: CourtUiState, score: ScoreView, note: String?, onUndo: () -> Unit) {
    val decided = score.decidedWinner
    val callout = listOfNotNull(
        Labels.highlight(score, ui.config).takeIf { score.winner == null },
        "CHANGE ENDS".takeIf { score.changeEnds },
    ).joinToString(" · ")

    val (headline, headlineColor) = when {
        note != null -> note to Palette.Danger
        callout.isNotEmpty() -> callout to Palette.Accent
        score.winner != null -> "${score.nameOf(score.winner ?: Team.A).uppercase()} WON" to Palette.Gold
        decided != null -> "${score.nameOf(decided).uppercase()} WON · PLAYING SET ${score.setNumber}" to Palette.Gold
        else -> "SET ${score.setNumber}" to Palette.Muted
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(18.dp))
            // Solid, so the net drawn behind it does not strike through the text.
            .background(Palette.NetBand)
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = headline to headlineColor,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                label = "headline",
            ) { (text, color) ->
                Text(
                    text,
                    color = color,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val details = listOfNotNull(
                score.setSummary.takeIf { it.isNotEmpty() },
                if (score.winner == null) ui.startedAtMillis?.let { matchClock(it) } else null,
            ).joinToString("   ")
            if (details.isNotEmpty()) {
                Text(
                    details,
                    color = Palette.OnBackground,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = onUndo, enabled = score.canUndo && ui.canScore, modifier = Modifier.height(48.dp)) {
            Text("Undo", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Time since the match started on this device, ticking once a second. */
@Composable
private fun matchClock(startedAtMillis: Long): String {
    val now by produceState(System.currentTimeMillis(), startedAtMillis) {
        while (true) {
            delay(1000)
            value = System.currentTimeMillis()
        }
    }
    return Labels.clock(now - startedAtMillis)
}

// --- Games and sets as they are won -----------------------------------------

private enum class MatchEventKind { GAME, SET, DECIDED }

/**
 * Something worth a moment's fanfare, and the team that earned it. [id]
 * makes two identical events in a row distinct.
 */
private class MatchEvent(val kind: MatchEventKind, val team: Team, val text: String, val id: Int)

/**
 * Watches the score and reports a game or a set being won, on whichever
 * device it was scored, for a couple of seconds.
 */
@Composable
private fun rememberMatchEvent(score: ScoreView): MatchEvent? {
    var event by remember { mutableStateOf<MatchEvent?>(null) }
    // Plain holders, not state: changing them must not redraw anything.
    val previous = remember { arrayOfNulls<ScoreView>(1) }
    var counter by remember { mutableIntStateOf(0) }

    LaunchedEffect(score) {
        val before = previous[0]
        previous[0] = score
        val found = before?.let { describeEvent(it, score) }
        if (found != null) {
            counter++
            event = MatchEvent(found.kind, found.team, found.text, counter)
        }
    }
    LaunchedEffect(event?.id) {
        if (event != null) {
            delay(2600)
            event = null
        }
    }
    return event
}

/** What was just won between [before] and [after], if anything. An undo is never an event. */
private fun describeEvent(before: ScoreView, after: ScoreView): MatchEvent? {
    // The end of the match has a screen of its own.
    if (after.winner != null) return null
    if (after.completedSets.size == before.completedSets.size + 1) {
        val set = after.completedSets.last()
        val decided = after.decidedWinner
        return if (decided != null && before.decidedWinner == null) {
            MatchEvent(MatchEventKind.DECIDED, decided, "🏆 ${after.nameOf(decided).uppercase()} WON THE MATCH", 0)
        } else {
            MatchEvent(MatchEventKind.SET, set.winner, "SET · ${after.nameOf(set.winner).uppercase()}", 0)
        }
    }
    if (after.completedSets.size != before.completedSets.size) return null
    return when {
        after.gamesA == before.gamesA + 1 && after.gamesB == before.gamesB ->
            MatchEvent(MatchEventKind.GAME, Team.A, "GAME · ${after.nameA.uppercase()}", 0)
        after.gamesB == before.gamesB + 1 && after.gamesA == before.gamesA ->
            MatchEvent(MatchEventKind.GAME, Team.B, "GAME · ${after.nameB.uppercase()}", 0)
        else -> null
    }
}

@Composable
private fun EventBanner(event: MatchEvent?, modifier: Modifier = Modifier) {
    // Hold on to the last text so it can still be read while fading out.
    var shown by remember { mutableStateOf(event) }
    if (event != null) shown = event

    AnimatedVisibility(
        visible = event != null,
        enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy), initialScale = 0.6f) + fadeIn(),
        exit = scaleOut(targetScale = 1.15f) + fadeOut(),
        modifier = modifier,
    ) {
        val current = shown
        if (current != null) {
            Text(
                current.text,
                color = if (current.kind == MatchEventKind.GAME) Palette.Accent else Palette.Gold,
                fontSize = if (current.kind == MatchEventKind.GAME) 26.sp else 30.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.Black.copy(alpha = 0.82f))
                    .padding(horizontal = 22.dp, vertical = 14.dp),
            )
        }
    }
}
