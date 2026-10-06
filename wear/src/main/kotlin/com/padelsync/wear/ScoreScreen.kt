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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.netsports.core.engine.Team
import com.netsports.core.match.Action
import com.netsports.core.sync.ClientStatus
import com.netsports.core.sync.TapFeedback
import com.netsports.core.ui.ScoreView
import com.netsports.core.ui.ServeLine
import com.padelsync.kit.CourtController
import com.padelsync.kit.CourtMode
import com.padelsync.kit.CourtUiState
import com.padelsync.kit.Labels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay

/**
 * The watch scoreboard: the top half scores for Team A, the bottom half for
 * Team B, with undo and the menu on the strip between them. The serving
 * team's half also says who serves and from which side.
 */
@Composable
fun ScoreScreen(
    ui: CourtUiState,
    score: ScoreView,
    controller: CourtController,
    resultDismissed: Boolean,
    onDismissResult: () -> Unit,
    onMenu: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    // Over with a winner, or over level: a points match can be drawn.
    val finished = score.isOver

    // Keep the score visible for the length of the match.
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var note by remember { mutableStateOf<String?>(null) }
    OnChange(ui.feedbackCount) {
        val text = when (ui.lastFeedback) {
            TapFeedback.SUPERSEDED -> "ALREADY IN"
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

    // Two quick ticks when someone else scores, so the wearer knows the point
    // is in without looking, and does not score it again.
    OnChange(ui.remoteScoreCount) {
        vibrate(context, longArrayOf(0, 20, 70, 20))
    }

    // The end of the match gets a screen of its own, which can be put away.
    val winner = score.winner
    OnChange(finished to winner) {
        if (finished) vibrate(context, longArrayOf(0, 120, 90, 120, 90, 260))
    }
    if (finished && !resultDismissed) {
        WinnerScreen(
            score = score,
            winner = winner,
            onRematch = if (ui.mode == CourtMode.HOST) ({ controller.rematch() }) else null,
            onUndo = if (ui.canScore) ({ controller.tap(Action.UNDO) }) else null,
            onDismiss = onDismissResult,
        )
        return
    }

    val tap = { action: Action ->
        vibrate(context, longArrayOf(0, 25))
        controller.tap(action)
    }

    val offline = ui.mode == CourtMode.GUEST && ui.guestStatus != ClientStatus.SYNCED
    val strip = when {
        note != null -> note
        // The strip holds about nine characters on a small round watch.
        offline -> "OFFLINE"
        else -> Labels.highlightShort(score, ui.config)
            ?: "SWAP ENDS".takeIf { score.changeEnds }
            ?: "VIEW ONLY".takeIf { !ui.canScore }
            // Decided, with sets still to play.
            ?: score.decidedWinner?.let { "${Labels.shortName(score, it)} WON" }
            // A points match counts its rallies where a match of sets shows its sets.
            ?: score.rallyLine
            ?: score.setSummary.ifEmpty { null }
    }

    // A small round watch has less room at its top and bottom edges, which
    // is where the serve line goes.
    val compact = LocalConfiguration.current.screenWidthDp < COMPACT_BELOW_DP

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
            serve = score.serveLine(Team.A, compact),
            compact = compact,
            countsShown = !score.pointsMatch,
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
            serve = score.serveLine(Team.B, compact),
            compact = compact,
            countsShown = !score.pointsMatch,
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
    serve: ServeLine?,
    compact: Boolean,
    countsShown: Boolean,
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
        // The score hugs the strip; the serve line takes the room left
        // towards the edge of the screen, above Team A's score and below
        // Team B's.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (alignBottom && serve != null) ServePill(serve, Modifier.offset(y = SERVE_OVERLAP))
            ScoreRow(label, color, points, games, sets, compact, countsShown)
            if (!alignBottom && serve != null) ServePill(serve, Modifier.offset(y = -SERVE_OVERLAP))
        }
    }
}

/**
 * A team's short name, its games, its sets and its points, each a number
 * that reads at a glance.
 *
 * @param countsShown false in a points match, which has no games or sets.
 */
@Composable
private fun ScoreRow(
    label: String,
    color: Color,
    points: String,
    games: Int,
    sets: Int,
    compact: Boolean,
    countsShown: Boolean,
) {
    // A three-letter name needs the room that a single letter leaves spare.
    val gap = when {
        compact -> 5.dp
        label.length > 1 -> 7.dp
        else -> 10.dp
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.padding(vertical = 2.dp),
    ) {
        Text(
            label,
            color = color,
            fontSize = if (label.length > 1) 16.sp else 23.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
        )
        Spacer(Modifier.width(gap))
        if (countsShown) {
            Count("GAMES", games, Color.White, compact)
            Spacer(Modifier.width(gap - 2.dp))
            Count("SETS", sets, WearPalette.Muted, compact)
            Spacer(Modifier.width(gap))
        }
        Text(
            points,
            color = Color.White,
            // Slightly smaller on a small watch, to leave room for the serve
            // line; larger in a points match, where it is the only number.
            fontSize = when {
                !countsShown -> if (compact) 48.sp else 56.sp
                compact -> 40.sp
                else -> 46.sp
            },
            fontWeight = FontWeight.Black,
            maxLines = 1,
        )
    }
}

/** One count of the score, with a small word over it saying which. */
@Composable
private fun Count(caption: String, value: Int, color: Color, compact: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(caption, color = WearPalette.Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(
            value.toString(),
            color = color,
            fontSize = if (compact) 22.sp else 27.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            // The digits carry empty space above them; close it up.
            modifier = Modifier.offset(y = (-4).dp),
        )
    }
}

/**
 * Who serves the next point and from which side, as on the phone: a ball,
 * the server in the ball's colour, then the side. `LEO · RIGHT`.
 */
@Composable
private fun ServePill(serve: ServeLine, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.6f))
            .padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(WearPalette.Accent),
        )
        Spacer(Modifier.width(4.dp))
        Text(serve.who, color = WearPalette.Accent, fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text(" · ${serve.side}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, maxLines = 1)
    }
}

/** Screens narrower than this, in dp, get the shorter serve line and smaller points. */
private const val COMPACT_BELOW_DP = 210

/**
 * How far the serve line is pushed towards the score. The big digits carry
 * empty space above and below them, which the line can sit in.
 */
private val SERVE_OVERLAP = 6.dp

/**
 * Runs [block] each time [key] changes, but not for the value it has when
 * this first appears: the score screen is rebuilt on every return from the
 * menu, and a buzz or a note must not replay because of that.
 */
@Composable
private fun OnChange(key: Any?, block: suspend CoroutineScope.() -> Unit) {
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(key) {
        if (first[0]) first[0] = false else block()
    }
}

private fun vibrate(context: Context, pattern: LongArray) {
    try {
        context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    } catch (e: RuntimeException) {
        // No vibrator, or not permitted: the tap still counts.
    }
}
