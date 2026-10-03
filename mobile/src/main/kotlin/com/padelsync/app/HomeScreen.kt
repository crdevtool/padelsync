package com.padelsync.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun HomeScreen(
    hasSavedMatch: Boolean,
    onNewMatch: () -> Unit,
    onJoin: () -> Unit,
    onResume: () -> Unit,
    onHistory: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("PadelSync", fontSize = 44.sp, fontWeight = FontWeight.Black, color = Palette.Accent)
        Spacer(Modifier.height(8.dp))
        Text(
            "One score on every phone and watch on the court.",
            fontSize = 18.sp,
            color = Palette.Muted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(48.dp))

        Button(onClick = onNewMatch, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("New match", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text("Join a court", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        if (hasSavedMatch) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onResume, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                Text("Resume last match", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onHistory) {
            Text("Match history", fontSize = 18.sp)
        }
    }
}
