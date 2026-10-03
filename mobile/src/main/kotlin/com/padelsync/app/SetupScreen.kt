package com.padelsync.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netsports.core.engine.DeuceRule
import com.netsports.core.engine.FinalSetRule
import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.Sport
import com.netsports.core.engine.Team
import com.netsports.core.match.Roster
import com.padelsync.kit.Labels
import com.padelsync.kit.MatchSetup

/**
 * Lets the player choose who is playing and the match format.
 *
 * @param hosting whether the match will be shared with other devices, which
 * adds the choice of who may score.
 * @param initial the last match set up on this device, to start from.
 */
@Composable
fun SetupScreen(
    title: String,
    startLabel: String,
    hosting: Boolean,
    initial: MatchSetup?,
    voiceOn: Boolean,
    onVoiceChange: (Boolean) -> Unit,
    onStart: (MatchSetup) -> Unit,
    onBack: () -> Unit,
) {
    val start = initial?.config ?: MatchConfig.padel().copy(playAllSets = true)
    var sport by rememberSaveable { mutableStateOf(start.sport) }
    var doubles by rememberSaveable { mutableStateOf(start.doubles) }
    var bestOf by rememberSaveable { mutableStateOf(start.bestOf) }
    var playAllSets by rememberSaveable { mutableStateOf(start.playAllSets) }
    var deuceRule by rememberSaveable { mutableStateOf(start.deuceRule) }
    var finalSet by rememberSaveable { mutableStateOf(start.finalSetRule) }
    var firstServer by rememberSaveable { mutableStateOf(start.firstServer) }
    var guestsCanScore by rememberSaveable { mutableStateOf(initial?.guestsCanScore ?: true) }

    val names = initial?.roster ?: Roster.EMPTY
    var a1 by rememberSaveable { mutableStateOf(names.playerName(Team.A, 0).orEmpty()) }
    var a2 by rememberSaveable { mutableStateOf(names.playerName(Team.A, 1).orEmpty()) }
    var b1 by rememberSaveable { mutableStateOf(names.playerName(Team.B, 0).orEmpty()) }
    var b2 by rememberSaveable { mutableStateOf(names.playerName(Team.B, 1).orEmpty()) }

    // In singles the second field of each team is hidden, and ignored.
    val roster = if (doubles) Roster.of(listOf(a1, a2), listOf(b1, b2)) else Roster.of(listOf(a1), listOf(b1))

    Column(
        Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Row(Modifier.padding(start = 8.dp, top = 12.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back", fontSize = 16.sp) }
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.OnBackground)
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            OptionGroup(
                title = "Sport",
                options = Sport.entries,
                selected = sport,
                label = { Labels.sport(it) },
                onSelect = {
                    if (it != sport) {
                        sport = it
                        // Each sport's usual habits; every one can be changed below.
                        deuceRule = if (it == Sport.PADEL) DeuceRule.GOLDEN_POINT else DeuceRule.ADVANTAGE
                        doubles = it == Sport.PADEL
                        playAllSets = it == Sport.PADEL
                    }
                },
            )
            OptionGroup(
                title = "Players",
                options = listOf(true, false),
                selected = doubles,
                label = { if (it) "Doubles" else "Singles" },
                onSelect = { doubles = it },
            )

            Spacer(Modifier.height(6.dp))
            Text("Team A players", fontSize = 15.sp, color = Palette.TeamA, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NameField(a1, { a1 = it }, if (doubles) "Player 1" else "Player", Modifier.weight(1f))
                if (doubles) NameField(a2, { a2 = it }, "Player 2", Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text("Team B players", fontSize = 15.sp, color = Palette.TeamB, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NameField(b1, { b1 = it }, if (doubles) "Player 1" else "Player", Modifier.weight(1f), last = !doubles)
                if (doubles) NameField(b2, { b2 = it }, "Player 2", Modifier.weight(1f), last = true)
            }
            Text(
                "Names are optional. In doubles, player 1 serves first for their team.",
                fontSize = 13.sp,
                color = Palette.Muted,
                modifier = Modifier.padding(top = 6.dp),
            )

            OptionGroup(
                title = "First to serve",
                options = Team.entries,
                selected = firstServer,
                label = { roster.teamName(it) },
                onSelect = { firstServer = it },
            )
            OptionGroup(
                title = "Sets",
                options = listOf(1, 3, 5),
                selected = bestOf,
                label = { if (it == 1) "1 set" else "Best of $it" },
                onSelect = {
                    bestOf = it
                    // A match tiebreak replaces a deciding set, so it needs more than one set.
                    if (it == 1 && finalSet == FinalSetRule.MATCH_TIEBREAK) finalSet = FinalSetRule.SAME_AS_OTHER_SETS
                },
            )
            if (bestOf > 1) {
                SwitchRow(
                    title = "Play all $bestOf sets",
                    caption = "Keep playing after a team has already won the match",
                    checked = playAllSets,
                    onCheckedChange = { playAllSets = it },
                )
            }
            OptionGroup(
                title = "At deuce",
                options = DeuceRule.entries,
                selected = deuceRule,
                label = { Labels.deuceRule(it) },
                onSelect = { deuceRule = it },
            )
            OptionGroup(
                title = "Final set",
                options = if (bestOf == 1) {
                    listOf(FinalSetRule.SAME_AS_OTHER_SETS, FinalSetRule.ADVANTAGE_SET)
                } else {
                    FinalSetRule.entries
                },
                selected = finalSet,
                label = { Labels.finalSet(it) },
                onSelect = { finalSet = it },
            )

            Spacer(Modifier.height(6.dp))
            if (hosting) {
                SwitchRow(
                    title = "Others can score",
                    caption = if (guestsCanScore) {
                        "Every phone and watch that joins can add points"
                    } else {
                        "Only this phone scores; the others watch. You can allow people one by one later."
                    },
                    checked = guestsCanScore,
                    onCheckedChange = { guestsCanScore = it },
                )
            }
            SwitchRow(
                title = "Call the score out loud",
                caption = "Points, games, who serves. Fine-tune it from the match menu.",
                checked = voiceOn,
                onCheckedChange = onVoiceChange,
            )
            Spacer(Modifier.height(12.dp))
        }

        // Outside the scrolling area, so it is always within reach.
        Button(
            onClick = {
                onStart(
                    MatchSetup(
                        config = MatchConfig(
                            sport = sport,
                            bestOf = bestOf,
                            deuceRule = deuceRule,
                            finalSetRule = finalSet,
                            firstServer = firstServer,
                            playAllSets = playAllSets && bestOf > 1,
                            doubles = doubles,
                        ),
                        roster = roster,
                        guestsCanScore = guestsCanScore,
                    ),
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .height(64.dp),
        ) {
            Text(startLabel, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}
