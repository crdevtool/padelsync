package com.padelsync.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netsports.core.engine.Team
import com.netsports.core.ui.MatchStats
import com.netsports.core.ui.ScoreView
import com.padelsync.kit.Labels
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** One piece of confetti. Positions are fractions of the width; speeds are in dp per second. */
private class Piece(
    val x: Float,
    val delaySeconds: Float,
    val fallSpeed: Float,
    val sway: Float,
    val swayPhase: Float,
    val size: Float,
    val spin: Float,
    val color: Color,
)

private val ConfettiColors = listOf(
    Palette.Accent,
    Palette.TeamA,
    Palette.TeamB,
    Palette.Gold,
    Color(0xFFFF5E8A),
    Color(0xFFFFFFFF),
)

/**
 * Confetti falling over whatever is behind it.
 *
 * @param endless rain for [CONFETTI_RAIN_SECONDS], then let the last pieces
 * fall out of sight. When false every piece falls once, which makes a short
 * burst. Either way it stops drawing once nothing is left to fall, so a
 * result left on screen does not keep the phone redrawing every frame.
 */
@Composable
fun Confetti(modifier: Modifier = Modifier, pieces: Int = 110, endless: Boolean = true) {
    val confetti = remember(pieces, endless) {
        val random = Random(System.nanoTime())
        List(pieces) {
            Piece(
                x = random.nextFloat(),
                delaySeconds = random.nextFloat() * (if (endless) 2.5f else 0.6f),
                // A burst has to be over in a couple of seconds, so it falls faster.
                fallSpeed = (170f + random.nextFloat() * 230f) * (if (endless) 1f else 1.9f),
                sway = 8f + random.nextFloat() * 22f,
                swayPhase = random.nextFloat() * 6.28f,
                size = 7f + random.nextFloat() * 8f,
                spin = (random.nextFloat() - 0.5f) * 720f,
                color = ConfettiColors[random.nextInt(ConfettiColors.size)],
            )
        }
    }

    // Seconds since the confetti started. Read only while drawing, so each
    // frame redraws the canvas without recomposing anything.
    var seconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        val lifetime = if (endless) CONFETTI_RAIN_SECONDS + 8f else 5f
        while (seconds < lifetime) {
            withFrameNanos { now -> seconds = (now - start) / 1_000_000_000f }
        }
    }

    Canvas(modifier) {
        val margin = 24.dp.toPx()
        val distance = size.height + 2 * margin
        for (piece in confetti) {
            val elapsed = seconds - piece.delaySeconds
            if (elapsed < 0f) continue
            val speed = piece.fallSpeed.dp.toPx()
            val fallen = elapsed * speed
            if (!endless && fallen > distance) continue
            // After the rain stops, a piece finishes the fall it is on and is not seen again.
            if (endless && piece.delaySeconds + floor(fallen / distance) * distance / speed > CONFETTI_RAIN_SECONDS) continue
            val y = fallen % distance - margin
            val x = piece.x * size.width + sin(elapsed * 2.2f + piece.swayPhase) * piece.sway.dp.toPx()
            val width = piece.size.dp.toPx()
            rotate(degrees = elapsed * piece.spin, pivot = Offset(x, y)) {
                drawRect(
                    color = piece.color,
                    topLeft = Offset(x - width / 2, y - width / 4),
                    size = Size(width, width / 2),
                )
            }
        }
    }
}

/**
 * The end of a match: confetti, the winners, the score and a few numbers to
 * argue about afterwards.
 *
 * @param winner the team that won, or `null` for a match that ended level.
 * @param durationMillis how long the match took, or `null` if unknown.
 * @param onRematch plays again with the same players, or `null` when this
 * device is not the host.
 */
@Composable
fun Celebration(
    score: ScoreView,
    winner: Team?,
    stats: MatchStats?,
    durationMillis: Long?,
    onShare: () -> Unit,
    onRematch: (() -> Unit)?,
    onNewMatch: (() -> Unit)?,
    onUndo: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    // The trophy drops in and settles with a bounce.
    val trophy = remember { Animatable(0.2f) }
    LaunchedEffect(Unit) {
        trophy.animateTo(1f, spring(dampingRatio = Spring.DampingRatioHighBouncy, stiffness = Spring.StiffnessLow))
    }
    val winnerColor = when (winner) {
        Team.A -> Palette.TeamA
        Team.B -> Palette.TeamB
        null -> Palette.Gold
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.94f))
            // Swallow taps so nothing underneath can be scored by accident.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Confetti(Modifier.fillMaxSize())

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (winner == null) "🤝" else "🏆",
                fontSize = 64.sp,
                modifier = Modifier.graphicsLayer {
                    scaleX = trophy.value
                    scaleY = trophy.value
                },
            )
            Text(
                if (winner == null) "ALL SQUARE" else "CONGRATULATIONS",
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                color = Palette.Gold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                Labels.resultHeadline(score, winner),
                fontSize = 36.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Black,
                color = winnerColor,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                score.setSummary,
                fontSize = 34.sp,
                fontWeight = FontWeight.Black,
                color = Palette.OnBackground,
                textAlign = TextAlign.Center,
            )
            if (durationMillis != null && durationMillis >= 60_000) {
                Text("Played in ${Labels.duration(durationMillis)}", fontSize = 16.sp, color = Palette.Muted)
            }

            if (stats != null && stats.totalPoints > 0) {
                Spacer(Modifier.height(18.dp))
                StatsTable(score, stats)
            }

            Spacer(Modifier.height(16.dp))
            Button(onClick = onShare, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Share the result", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            if (onRematch != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onRematch, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("Rematch, same players", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (onNewMatch != null) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onNewMatch, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text("New match", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = onDismiss) { Text("Scoreboard", fontSize = 16.sp) }
                if (onUndo != null) {
                    TextButton(onClick = onUndo) { Text(Labels.undoResult(score), fontSize = 16.sp) }
                }
            }
        }
    }
}

/** Both teams' numbers side by side. */
@Composable
private fun StatsTable(score: ScoreView, stats: MatchStats) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.SurfaceHigh.copy(alpha = 0.9f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            StatCell(score.nameA, Palette.TeamA, TextAlign.Start, Modifier.weight(1f), small = true)
            StatCell(score.nameB, Palette.TeamB, TextAlign.End, Modifier.weight(1f), small = true)
        }
        StatRow("Points won", stats.teamA.points, stats.teamB.points)
        // A points match has no games, and so no breaks of serve.
        if (!score.pointsMatch) {
            StatRow("Games won", stats.teamA.games, stats.teamB.games)
            StatRow("Breaks of serve", stats.teamA.breaks, stats.teamB.breaks)
        }
        StatRow("Best run of points", stats.teamA.longestStreak, stats.teamB.longestStreak)
    }
}

@Composable
private fun StatRow(label: String, a: Int, b: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        StatCell(a.toString(), if (a > b) Palette.OnBackground else Palette.Muted, TextAlign.Start, Modifier.weight(0.5f))
        Text(label, fontSize = 14.sp, color = Palette.Muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1.4f))
        StatCell(b.toString(), if (b > a) Palette.OnBackground else Palette.Muted, TextAlign.End, Modifier.weight(0.5f))
    }
}

@Composable
private fun StatCell(text: String, color: Color, align: TextAlign, modifier: Modifier, small: Boolean = false) {
    Text(
        text,
        fontSize = if (small) 15.sp else 22.sp,
        fontWeight = FontWeight.Black,
        color = color,
        textAlign = align,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** How long endless confetti keeps raining. */
private const val CONFETTI_RAIN_SECONDS = 12f
