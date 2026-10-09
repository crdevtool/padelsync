package com.padelsync.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Where the offer to put PadelSync on the watch has got to. */
private enum class Offer { OFFERED, OPENING, OPENED, FAILED }

/**
 * Offers to put PadelSync on a watch connected to this phone that does not
 * have it yet. Shows nothing when there is no such watch, which includes
 * every phone without a Wear OS watch.
 */
@Composable
fun WatchAppCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val installer = remember { WatchAppInstaller(context) }
    var watch by remember { mutableStateOf<WatchAppInstaller.Watch?>(null) }
    var offer by remember { mutableStateOf(Offer.OFFERED) }
    // Asked each time the home screen shows, so the card goes away once the
    // watch has the app.
    LaunchedEffect(Unit) { watch = installer.watchWithoutApp() }

    val shown = watch ?: return
    val open = {
        offer = Offer.OPENING
        installer.openStoreOn(shown) { sent -> offer = if (sent) Offer.OPENED else Offer.FAILED }
    }
    val (title, body) = when (offer) {
        Offer.OFFERED, Offer.OPENING -> "PadelSync isn't on your watch yet" to
            "${shown.name} is connected to this phone. Install the watch app to score from your wrist."
        Offer.OPENED -> "Look at your watch" to
            "The Play Store is open on ${shown.name} at PadelSync. Tap Install there; it takes a minute."
        Offer.FAILED -> "Couldn't reach your watch" to
            "Open the Play Store on ${shown.name}, search for PadelSync and tap Install."
    }

    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.SurfaceHigh)
            .border(1.5.dp, Palette.Accent, shape)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WatchGlyph()
            Spacer(Modifier.width(12.dp))
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.OnBackground)
        }
        Spacer(Modifier.height(6.dp))
        Text(body, fontSize = 15.sp, lineHeight = 21.sp, color = Palette.Muted)
        if (offer != Offer.OPENED) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = open, enabled = offer != Offer.OPENING) {
                    Text(
                        if (offer == Offer.FAILED) "Try again" else "Install on watch",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                TextButton(onClick = {
                    installer.dismiss(shown)
                    watch = null
                }) {
                    Text("Not now", fontSize = 16.sp, color = Palette.Muted)
                }
            }
        }
    }
}

/** A small watch: a strap with a round face on it. */
@Composable
private fun WatchGlyph() {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(width = 26.dp, height = 34.dp)) {
        Box(
            Modifier
                .size(width = 14.dp, height = 34.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Palette.Muted),
        )
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Palette.OnAccent)
                .border(2.5.dp, Palette.Accent, CircleShape),
        )
    }
}
