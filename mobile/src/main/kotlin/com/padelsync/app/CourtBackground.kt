package com.padelsync.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.netsports.core.engine.ServeSide
import com.netsports.core.engine.Sport
import com.netsports.core.engine.Team

/**
 * The court seen from above, as the backdrop of the scoreboard: turf, lines,
 * net, and a glow on the box the next serve must land in.
 *
 * @param server the team serving next, or `null` when nobody is (the match
 * is over), which switches the glow off.
 */
@Composable
fun CourtBackground(sport: Sport, server: Team?, serveSide: ServeSide?, modifier: Modifier = Modifier) {
    val geometry = remember(sport) { CourtGeometry.of(sport) }

    // The glow fades out where it was and in where it now is, rather than jumping.
    val glowA by glow(server == Team.A && serveSide == ServeSide.RIGHT)
    val glowB by glow(server == Team.A && serveSide == ServeSide.LEFT)
    val glowC by glow(server == Team.B && serveSide == ServeSide.RIGHT)
    val glowD by glow(server == Team.B && serveSide == ServeSide.LEFT)

    Canvas(modifier) {
        drawSurface(sport, geometry)
        drawGlow(geometry.targetBox(Team.A, ServeSide.RIGHT), glowA)
        drawGlow(geometry.targetBox(Team.A, ServeSide.LEFT), glowB)
        drawGlow(geometry.targetBox(Team.B, ServeSide.RIGHT), glowC)
        drawGlow(geometry.targetBox(Team.B, ServeSide.LEFT), glowD)
        drawLines(sport, geometry)
    }
}

@Composable
private fun glow(on: Boolean) = animateFloatAsState(
    targetValue = if (on) 1f else 0f,
    animationSpec = tween(durationMillis = 350),
    label = "serve box glow",
)

private fun DrawScope.drawSurface(sport: Sport, geometry: CourtGeometry) {
    val corner = CornerRadius(22.dp.toPx())
    if (sport == Sport.PADEL) {
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to Palette.PadelTurfDeep,
                0.5f to Palette.PadelTurf,
                1f to Palette.PadelTurfDeep,
            ),
            cornerRadius = corner,
        )
    } else {
        drawRoundRect(
            brush = Brush.verticalGradient(
                0f to Palette.TennisSurroundDeep,
                0.5f to Palette.TennisSurround,
                1f to Palette.TennisSurroundDeep,
            ),
            cornerRadius = corner,
        )
        val court = geometry.bounds
        drawRect(
            color = Palette.TennisCourt,
            topLeft = Offset(court.left * size.width, court.top * size.height),
            size = Size((court.right - court.left) * size.width, (court.bottom - court.top) * size.height),
        )
    }
}

private fun DrawScope.drawGlow(box: CourtGeometry.Area, strength: Float) {
    if (strength <= 0f) return
    drawRect(
        color = Palette.Ball.copy(alpha = 0.20f * strength),
        topLeft = Offset(box.left * size.width, box.top * size.height),
        size = Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height),
    )
}

private fun DrawScope.drawLines(sport: Sport, geometry: CourtGeometry) {
    val line = Palette.CourtLine.copy(alpha = 0.78f)
    val width = 2.5.dp.toPx()
    val court = geometry.bounds
    val topLeft = Offset(court.left * size.width, court.top * size.height)
    val courtSize = Size((court.right - court.left) * size.width, (court.bottom - court.top) * size.height)

    // The outline: glass walls in padel, baselines and sidelines in tennis.
    if (sport == Sport.PADEL) {
        drawRoundRect(
            color = line,
            topLeft = topLeft,
            size = courtSize,
            cornerRadius = CornerRadius(12.dp.toPx()),
            style = Stroke(width = width * 1.4f),
        )
    } else {
        drawRect(color = line, topLeft = topLeft, size = courtSize, style = Stroke(width = width))
    }

    for (segment in geometry.lines) {
        drawLine(
            color = line,
            start = Offset(segment.x1 * size.width, segment.y1 * size.height),
            end = Offset(segment.x2 * size.width, segment.y2 * size.height),
            strokeWidth = width,
        )
    }

    // The net: a shadow, the tape, and a post at each end.
    val netY = geometry.netY * size.height
    val overhang = 6.dp.toPx()
    val netStart = Offset(topLeft.x - overhang, netY)
    val netEnd = Offset(topLeft.x + courtSize.width + overhang, netY)
    drawLine(Color.Black.copy(alpha = 0.35f), netStart.copy(y = netY + 4.dp.toPx()), netEnd.copy(y = netY + 4.dp.toPx()), 7.dp.toPx())
    drawLine(Palette.CourtLine, netStart, netEnd, 4.dp.toPx(), cap = StrokeCap.Round)
    drawCircle(Palette.CourtLine, radius = 5.dp.toPx(), center = netStart)
    drawCircle(Palette.CourtLine, radius = 5.dp.toPx(), center = netEnd)
}
