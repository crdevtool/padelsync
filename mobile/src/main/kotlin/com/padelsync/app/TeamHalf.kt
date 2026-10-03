package com.padelsync.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netsports.core.engine.ServeSide
import com.netsports.core.engine.Team
import com.netsports.core.ui.ScoreView
import com.padelsync.kit.Labels

/** Dark outline behind the big numbers, so they read against turf in full sun. */
private val OnCourtShadow = Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 3f), blurRadius = 10f)

/**
 * One team's half of the court. The whole half is the button that scores a
 * point for that team.
 *
 * The two halves mirror each other about the net: each team's name sits by
 * its own back wall, where its players stand.
 *
 * @param pointsWon the team's points in the whole match, used only to notice
 * that it has just won one.
 * @param streak points this team has now won in a row, or 0.
 */
@Composable
fun TeamHalf(
    team: Team,
    atTop: Boolean,
    score: ScoreView,
    pointsWon: Int,
    streak: Int,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = if (team == Team.A) Palette.TeamA else Palette.TeamB
    val name = score.nameOf(team)
    val points = score.pointsOf(team)
    val games = score.gamesOf(team)
    val sets = score.setsOf(team)
    val serving = score.server == team

    // The half lights up in the team's colour for an instant when it wins a point.
    val flash = remember { Animatable(0f) }
    var seenPoints by remember { mutableIntStateOf(pointsWon) }
    LaunchedEffect(pointsWon) {
        val won = pointsWon > seenPoints
        seenPoints = pointsWon
        if (won) {
            flash.snapTo(0.42f)
            flash.animateTo(0f, tween(durationMillis = 520))
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, onClickLabel = "Point for $name", onClick = onClick)
            .semantics {
                contentDescription =
                    "$name. Points $points. Games $games. Sets $sets." + if (serving) " Serving." else ""
            },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = flash.value }
                .background(color),
        )
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (atTop) {
                NameRow(name, color, streak)
                ServeRow(team, score)
                PointsArea(points, games, sets)
            } else {
                PointsArea(points, games, sets)
                ServeRow(team, score)
                NameRow(name, color, streak)
            }
        }
    }
}

@Composable
private fun NameRow(name: String, color: Color, streak: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            name.uppercase(),
            color = Palette.OnBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(shadow = OnCourtShadow),
            modifier = Modifier.weight(1f, fill = false),
        )
        // Three points in a row is a run worth pointing out.
        AnimatedVisibility(visible = streak >= 3, enter = scaleIn() + fadeIn(), exit = fadeOut()) {
            Text(
                "🔥 $streak IN A ROW",
                color = Palette.OnAccent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Palette.Gold)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

/**
 * Where the server stands: a ball and the server's name, on the left or the
 * right of the half, sliding across after each point.
 */
@Composable
private fun ServeRow(team: Team, score: ScoreView) {
    val side = score.serveSide
    val serving = score.server == team && side != null
    val onLeft = CourtGeometry.serverOnScreenLeft(team, side ?: ServeSide.RIGHT)
    val bias by animateFloatAsState(
        targetValue = if (onLeft) -1f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "serve side",
    )

    Box(
        Modifier
            .fillMaxWidth()
            .height(38.dp),
    ) {
        AnimatedVisibility(
            visible = serving,
            enter = fadeIn() + scaleIn(initialScale = 0.7f),
            exit = fadeOut(),
            modifier = Modifier.align(BiasAlignment(bias, 0f)),
        ) {
            Row(
                Modifier
                    // Narrower than the half, so its position still shows the side.
                    .widthIn(max = 250.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(start = 8.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BouncingBall()
                Spacer(Modifier.width(8.dp))
                // A long name gives way; the side never does.
                Text(
                    serverLabel(team, score),
                    color = Palette.Ball,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (side != null) {
                    Text(
                        " · ${Labels.serveSide(side).uppercase()}",
                        color = Palette.OnBackground,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** `ANA SERVES`, `PLAYER 2 SERVES` when the name is not known, or just `SERVE` in singles. */
private fun serverLabel(team: Team, score: ScoreView): String {
    val player = score.playersOf(team).getOrNull(score.serverPlayerIndex)
    return when {
        player != null -> "${player.uppercase()} SERVES"
        score.doubles -> "PLAYER ${score.serverPlayerIndex + 1} SERVES"
        else -> "SERVE"
    }
}

/** A tennis ball that never quite sits still. */
@Composable
private fun BouncingBall() {
    val transition = rememberInfiniteTransition(label = "ball")
    val scale by transition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 620), RepeatMode.Reverse),
        label = "ball bounce",
    )
    Canvas(
        Modifier
            .size(20.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        drawCircle(Palette.Ball)
        // The seam: two arcs curving away from each other.
        val seam = Stroke(width = size.minDimension * 0.09f)
        val arc = Size(size.width, size.height)
        drawArc(Color.White.copy(alpha = 0.9f), 130f, 100f, false, Offset(-size.width * 0.62f, 0f), arc, style = seam)
        drawArc(Color.White.copy(alpha = 0.9f), -50f, 100f, false, Offset(size.width * 0.62f, 0f), arc, style = seam)
    }
}

/** The big number for the current game, with games and sets beside it. */
@Composable
private fun ColumnScope.PointsArea(points: String, games: Int, sets: Int) {
    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth(),
    ) {
        AnimatedContent(
            targetState = points,
            transitionSpec = {
                (slideInVertically { it / 2 } + fadeIn() + scaleIn(initialScale = 0.75f)) togetherWith
                    (slideOutVertically { -it / 2 } + fadeOut())
            },
            label = "points",
            modifier = Modifier.align(Alignment.Center),
        ) { value ->
            Text(
                value,
                color = Palette.OnBackground,
                fontSize = 112.sp,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                style = TextStyle(shadow = OnCourtShadow),
            )
        }
        Column(
            Modifier.align(Alignment.CenterEnd),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Counter("GAMES", games)
            Counter("SETS", sets)
        }
    }
}

/** A small count that swells for a moment when it goes up. */
@Composable
private fun Counter(label: String, value: Int) {
    val bump = remember { Animatable(1f) }
    var seen by remember { mutableIntStateOf(value) }
    LaunchedEffect(value) {
        val grew = value > seen
        seen = value
        if (grew) {
            bump.snapTo(1.7f)
            bump.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        }
    }
    Column(horizontalAlignment = Alignment.End) {
        Text(
            value.toString(),
            color = Palette.OnBackground,
            fontSize = 34.sp,
            fontWeight = FontWeight.Black,
            style = TextStyle(shadow = OnCourtShadow),
            modifier = Modifier.graphicsLayer {
                scaleX = bump.value
                scaleY = bump.value
            },
        )
        Text(label, color = Palette.OnBackground.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}
