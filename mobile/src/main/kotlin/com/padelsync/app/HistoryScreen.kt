package com.padelsync.app

import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netsports.core.engine.Team
import com.netsports.core.history.MatchRecord
import com.padelsync.kit.Labels
import java.text.DateFormat
import java.util.Date

/** Finished matches this device took part in, newest first. */
@Composable
fun HistoryScreen(records: List<MatchRecord>, onBack: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back", fontSize = 16.sp) }
            Text("Match history", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.OnBackground)
        }
        Spacer(Modifier.height(12.dp))

        if (records.isEmpty()) {
            Text("No finished matches yet.", color = Palette.Muted, fontSize = 18.sp)
        } else {
            Text(
                "${records.size} played · ${Labels.duration(records.sumOf { it.durationMillis })} on court",
                color = Palette.Muted,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(12.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(records, key = { it.matchId }) { record ->
                    val score = record.score
                    // A match stopped after it was decided still has a winner.
                    val winner = score.winner ?: score.decidedWinner
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Palette.SurfaceHigh)
                            .padding(16.dp),
                    ) {
                        Text(
                            when {
                                winner != null -> "${score.nameOf(winner)} beat ${score.nameOf(winner.opponent)}"
                                score.drawn -> "${score.nameA} drew with ${score.nameB}"
                                else -> "${score.nameA} vs ${score.nameB}"
                            },
                            color = if (winner == Team.B) Palette.TeamB else Palette.TeamA,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                        )
                        Text(
                            score.setSummary,
                            color = Palette.OnBackground,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${Labels.format(record.snapshot.config)} · ${Labels.duration(record.durationMillis)}",
                            color = Palette.Muted,
                            fontSize = 14.sp,
                        )
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                                .format(Date(record.finishedAtMillis)),
                            color = Palette.Muted,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}
