package com.padelsync.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netsports.core.engine.ServeSide
import com.netsports.core.engine.Sport
import com.netsports.core.engine.Team

@Composable
fun HomeScreen(
    hasSavedMatch: Boolean,
    onNewMatch: () -> Unit,
    onHostMatch: () -> Unit,
    onJoin: () -> Unit,
    onResume: () -> Unit,
    onHistory: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A small court as the app's signature.
        CourtBackground(
            sport = Sport.PADEL,
            server = Team.A,
            serveSide = ServeSide.RIGHT,
            modifier = Modifier
                .width(92.dp)
                .aspectRatio(0.5f),
        )
        Spacer(Modifier.height(16.dp))
        Text("PadelSync", fontSize = 44.sp, fontWeight = FontWeight.Black, color = Palette.Accent)
        Spacer(Modifier.height(8.dp))
        Text(
            "One score on every phone and watch on the court.",
            fontSize = 18.sp,
            color = Palette.Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        // Only shown when a watch without PadelSync is connected to this phone.
        WatchAppCard(Modifier.padding(bottom = 20.dp))

        HomeAction(
            title = "New match",
            caption = "Keep score on this phone",
            background = Palette.Accent,
            titleColor = Palette.OnAccent,
            captionColor = Palette.OnAccent.copy(alpha = 0.75f),
            onClick = onNewMatch,
        )
        Spacer(Modifier.height(14.dp))
        HomeAction(
            title = "Host a match",
            caption = "Play with others: every phone and watch shows the score",
            background = Palette.TeamA.copy(alpha = 0.22f),
            titleColor = Palette.OnBackground,
            captionColor = Palette.Muted,
            onClick = onHostMatch,
        )
        Spacer(Modifier.height(14.dp))
        HomeAction(
            title = "Join a court",
            caption = "Follow or score a match someone nearby is hosting",
            background = Palette.TeamB.copy(alpha = 0.22f),
            titleColor = Palette.OnBackground,
            captionColor = Palette.Muted,
            onClick = onJoin,
        )
        if (hasSavedMatch) {
            Spacer(Modifier.height(14.dp))
            HomeAction(
                title = "Resume last match",
                caption = "Pick up where this phone left off",
                background = Palette.SurfaceHigh,
                titleColor = Palette.OnBackground,
                captionColor = Palette.Muted,
                onClick = onResume,
            )
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onHistory) {
            Text("Match history", fontSize = 18.sp)
        }
    }
}

@Composable
private fun HomeAction(
    title: String,
    caption: String,
    background: Color,
    titleColor: Color,
    captionColor: Color,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Black, color = titleColor)
            Text(caption, fontSize = 15.sp, color = captionColor)
        }
        Box(Modifier.width(8.dp))
        Text("›", fontSize = 30.sp, fontWeight = FontWeight.Black, color = titleColor)
    }
}
