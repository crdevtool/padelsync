package com.padelsync.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.padelsync.kit.BlePermissions
import com.padelsync.kit.CourtController
import com.padelsync.kit.Labels
import com.padelsync.kit.NearbyCourt

/** Lists courts being hosted nearby and joins the one the player picks. */
@Composable
fun JoinScreen(controller: CourtController, error: String?, onBack: () -> Unit) {
    val courts by controller.nearby.collectAsState()
    val gate = rememberBluetoothGate()
    var chosen by remember { mutableStateOf<NearbyCourt?>(null) }
    val locationOff = rememberLocationOff()

    // Searching starts as soon as it can find anything: straight away, or
    // when the player comes back from switching Location on, with no tap.
    LaunchedEffect(locationOff) {
        if (locationOff) controller.stopScan() else gate { controller.startScan() }
    }
    DisposableEffect(Unit) { onDispose { controller.stopScan() } }

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back", fontSize = 16.sp) }
            Text("Join a court", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.OnBackground)
        }
        Spacer(Modifier.height(12.dp))

        if (locationOff) {
            LocationOffNotice()
        } else if (error != null) {
            Text(error, color = Palette.Danger, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { gate { controller.startScan() } }) { Text("Try again", fontSize = 16.sp) }
        } else if (courts.isEmpty()) {
            Text("Looking for courts nearby…", color = Palette.Muted, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "On the host's device, open the match menu and choose \"Play with others\".",
                color = Palette.Muted,
                fontSize = 15.sp,
            )
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(courts, key = { it.id }) { court ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Palette.SurfaceHigh)
                        .clickable { chosen = court }
                        .padding(horizontal = 20.dp, vertical = 22.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        court.name,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Palette.OnBackground,
                        modifier = Modifier.weight(1f),
                    )
                    Text(signalLabel(court.rssi), fontSize = 14.sp, color = Palette.Muted)
                }
            }
        }
    }

    chosen?.let { court ->
        JoinCodeDialog(
            courtName = court.name,
            onDismiss = { chosen = null },
            onJoin = { code ->
                chosen = null
                controller.join(court, code)
            },
        )
    }
}

/**
 * Says why no court can be found while the phone's Location switch is off
 * (Android 11 and older), and offers the way to the switch.
 */
@Composable
private fun LocationOffNotice() {
    val context = LocalContext.current
    var noSettingsScreen by remember { mutableStateOf(false) }

    Text(Labels.LOCATION_OFF_TITLE, color = Palette.OnBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(Labels.LOCATION_OFF_BODY, color = Palette.Muted, fontSize = 15.sp)
    Spacer(Modifier.height(16.dp))
    Button(
        onClick = { noSettingsScreen = !BlePermissions.openLocationSettings(context) },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Text(Labels.LOCATION_OFF_BUTTON, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
    if (noSettingsScreen) {
        Spacer(Modifier.height(12.dp))
        Text(Labels.LOCATION_OFF_NO_SETTINGS, color = Palette.Muted, fontSize = 15.sp)
    }
    Spacer(Modifier.height(12.dp))
}

private fun signalLabel(rssi: Int): String = when {
    rssi >= -60 -> "Very close"
    rssi >= -75 -> "Close"
    else -> "Far"
}

@Composable
private fun JoinCodeDialog(courtName: String, onDismiss: () -> Unit, onJoin: (Int?) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join $courtName") },
        text = {
            Column {
                Text("Enter the 4-digit code shown on the host's screen.")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> text = input.filter(Char::isDigit).take(4) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    label = { Text("Code") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onJoin(text.toIntOrNull()) }, enabled = text.length == 4) { Text("Join") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
