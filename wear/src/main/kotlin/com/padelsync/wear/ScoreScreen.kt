package com.padelsync.wear

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.netsports.core.engine.ServeSide
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

/**
 * The watch scoreboard: the top half scores for Team A, the bottom half for
 * Team B, with undo and the menu on the strip between them.
 */
@Composable
fun ScoreScreen(ui: CourtUiState, score: ScoreView, controller: CourtController, onMenu: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val finished = score.winner != null

    // Keep the score visible for the length of the match.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(ui.feedbackCount) {
        val text = when (ui.lastFeedback) {
            TapFeedback.SUPERSEDED -> "ALREADY SCORED"
            TapFeedback.NOT_ALLOWED -> "VIEW ONLY"
            else -> null
        }
        if (text != null) {
            vibrate(context, longArrayOf(0, 70, 90, 70))
            note = text
            delay(2500)
            note = null
        }
    }

    // The end of the match gets a screen of its own, which can be put away.
    val winner = score.winner
    var resultDismissed by remember(winner) { mutableStateOf(false) }
    LaunchedEffect(winner) {
        if (winner != null) vibrate(context, longArrayOf(0, 120, 90, 120, 90, 260))
    }
    if (winner != null && !resultDismissed) {
        WinnerScreen(
            score = score,
            winner = winner,
            onRematch = if (ui.mode == CourtMode.HOST) ({ controller.rematch() }) else null,
            onUndo = if (ui.canScore) ({ controller.tap(Action.UNDO) }) else null,
            onDismiss = { resultDismissed = true },
        )
        return
    }

    // Two quick ticks when someone else scores, so the wearer knows the point
    // is in without looking, and does not score it again.
    LaunchedEffect(ui.remoteScoreCount) {
        if (ui.remoteScoreCount > 0) vibrate(context, longArrayOf(0, 20, 70, 20))
    }

    val tap = { action: Action ->
        vibrate(context, longArrayOf(0, 25))
        controller.tap(action)
    }

    val offline = ui.mode == CourtMode.GUEST && ui.guestStatus != ClientStatus.SYNCED
    val strip = when {
        note != null -> note
        offline -> "RECONNECTING"
        else -> Labels.highlightShort(score, ui.config)
            ?: "CHANGE ENDS".takeIf { score.changeEnds }
            ?: serveLine(score)
            ?: score.setSummary.ifEmpty { null }
    }

    Column(Modifier.fillMaxSize()) {
        Half(
            team = Team.A,
            label = Labels.shortName(score, Team.A),
            name = score.nameA,
            color = WearPalette.TeamA,
            points = score.pointsA,
            games = score.gamesA,
            sets = score.setsA,
            serving = score.server == Team.A,
            enabled = !finished,
            alignBottom = true,
            onClick = { tap(Action.POINT_A) },
            modifier = Modifier.weight(1f),
        )

        Row(
            Modifier
                .fillMaxWidth()
                // Inset so the keys clear the curve of a round screen.
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PillKey("UNDO", enabled = score.canUndo && ui.canScore) { tap(Action.UNDO) }
            Text(
                strip.orEmpty(),
                color = if (note != null || offline) WearPalette.Danger else WearPalette.Accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            )
            PillKey("MENU", onClick = onMenu)
        }

        Half(
            team = Team.B,
            label = Labels.shortName(score, Team.B),
            name = score.nameB,
            color = WearPalette.TeamB,
            points = score.pointsB,
            games = score.gamesB,
            sets = score.setsB,
            serving = score.server == Team.B,
            enabled = !finished,
            alignBottom = false,
            onClick = { tap(Action.POINT_B) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Half(
    team: Team,
    label: String,
    name: String,
    color: Color,
    points: String,
    games: Int,
    sets: Int,
    serving: Boolean,
    enabled: Boolean,
    alignBottom: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.22f))
            .clickable(enabled = enabled, onClickLabel = "Point for $name", onClick = onClick)
            .semantics {
                contentDescription =
                    "$name. Points $points. Games $games. Sets $sets." + if (serving) " Serving." else ""
            },
        // On a round screen the usable width is greatest next to the middle
        // strip, so each half hugs it.
        contentAlignment = if (alignBottom) Alignment.BottomCenter else Alignment.TopCenter,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(vertical = 2.dp),
        ) {
            Text(
                label,
                color = color,
                fontSize = if (label.length > 1) 16.sp else 22.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
            )
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("G $games", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text("S $sets", color = WearPalette.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(10.dp))
            Text(points, color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (serving) WearPalette.Accent else Color.Transparent),
            )
        }
    }
}

/**
 * Who serves next and from which side, short enough for the strip:
 * `LEO · R`. Only shown when the player's name is known; the dot beside the
 * score already says which team serves.
 */
private fun serveLine(score: ScoreView): String? {
    val team = score.server ?: return null
    val side = score.serveSide ?: return null
    val player = score.playersOf(team).getOrNull(score.serverPlayerIndex) ?: return null
    return "${player.take(7).uppercase()} · ${if (side == ServeSide.RIGHT) "R" else "L"}"
}

private fun vibrate(context: Context, pattern: LongArray) {
    try {
        context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    } catch (e: RuntimeException) {
        // No vibrator, or not permitted: the tap still counts.
    }
}
