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
import com.netsports.core.sync.Sessions
import com.netsports.core.ui.FormatHelp
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
    val start = initial?.config ?: Sessions.clubPadel()
    var sport by rememberSaveable { mutableStateOf(start.sport) }
    var doubles by rememberSaveable { mutableStateOf(start.doubles) }
    var bestOf by rememberSaveable { mutableStateOf(start.bestOf) }
    var playAllSets by rememberSaveable { mutableStateOf(start.playAllSets) }
    var deuceRule by rememberSaveable { mutableStateOf(start.deuceRule) }
    var gamesPerSet by rememberSaveable { mutableStateOf(start.gamesPerSet) }
    // A one-set match saved with "No tiebreak" as its final set is the same
    // thing as an advantage set, which is now chosen under "At 6-6".
    val startedWithoutTiebreak = start.bestOf == 1 && start.finalSetRule == FinalSetRule.ADVANTAGE_SET
    var setTiebreak by rememberSaveable { mutableStateOf(start.setTiebreak && !startedWithoutTiebreak) }
    var setGamesCap by rememberSaveable { mutableStateOf(start.setGamesCap) }
    var finalSet by rememberSaveable {
        mutableStateOf(if (startedWithoutTiebreak) FinalSetRule.SAME_AS_OTHER_SETS else start.finalSetRule)
    }
    var fast4 by rememberSaveable { mutableStateOf(start.isFast4Tiebreak) }
    // Americano: a match of points, with no games or sets.
    var pointsMatch by rememberSaveable { mutableStateOf(start.pointsMatch) }
    var pointsTotal by rememberSaveable { mutableStateOf(if (start.pointsMatch) start.pointsTotal else DEFAULT_POINTS) }
    var firstServer by rememberSaveable { mutableStateOf(start.firstServer) }
    var guestsCanScore by rememberSaveable { mutableStateOf(initial?.guestsCanScore ?: true) }

    val names = initial?.roster ?: Roster.EMPTY
    var a1 by rememberSaveable { mutableStateOf(names.playerName(Team.A, 0).orEmpty()) }
    var a2 by rememberSaveable { mutableStateOf(names.playerName(Team.A, 1).orEmpty()) }
    var b1 by rememberSaveable { mutableStateOf(names.playerName(Team.B, 0).orEmpty()) }
    var b2 by rememberSaveable { mutableStateOf(names.playerName(Team.B, 1).orEmpty()) }

    // In singles the second field of each team is hidden, and ignored.
    val roster = rosterOf(doubles, a1, a2, b1, b2)

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
                        doubles = it == Sport.PADEL
                        playAllSets = it == Sport.PADEL
                        // Americano is a padel format.
                        if (it != Sport.PADEL) pointsMatch = false
                    }
                },
            )
            if (sport == Sport.PADEL) {
                OptionGroup(
                    title = "Scoring",
                    options = listOf(false, true),
                    selected = pointsMatch,
                    label = { if (it) "Americano" else "Sets" },
                    onSelect = { pointsMatch = it },
                    caption = FormatHelp.scoring(pointsMatch),
                )
            }
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
            if (pointsMatch) {
                OptionGroup(
                    title = "Match length",
                    options = POINTS_TOTALS,
                    selected = pointsTotal,
                    label = { FormatHelp.matchLengthLabel(it) },
                    onSelect = { pointsTotal = it },
                    caption = FormatHelp.matchLength(pointsTotal),
                )
            } else {
                OptionGroup(
                    title = "Sets",
                    options = listOf(1, 3, 5),
                    selected = bestOf,
                    label = { if (it == 1) "1 set" else "Best of $it" },
                    onSelect = {
                        bestOf = it
                        // A one-set match has no final set to play differently.
                        if (it == 1) finalSet = FinalSetRule.SAME_AS_OTHER_SETS
                    },
                    caption = FormatHelp.sets(bestOf),
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
                    title = "Set length",
                    options = SET_LENGTHS,
                    selected = gamesPerSet,
                    label = { "$it games" },
                    onSelect = {
                        gamesPerSet = it
                        // The cap is counted from the set length, so it moves with it.
                        if (setGamesCap != 0) setGamesCap = it + 2
                        // Fast4 is played in sets to four games only.
                        if (it != FAST4_SET_LENGTH) fast4 = false
                    },
                    caption = FormatHelp.setLength(gamesPerSet),
                )
                OptionGroup(
                    title = "At deuce",
                    options = DeuceRule.entries,
                    selected = deuceRule,
                    label = { Labels.deuceRule(it) },
                    onSelect = { deuceRule = it },
                    caption = FormatHelp.deuce(deuceRule),
                )
                val gamesAll = when {
                    !setTiebreak -> GamesAll.ADVANTAGE_SET
                    fast4 -> GamesAll.FAST4
                    else -> GamesAll.TIEBREAK
                }
                OptionGroup(
                    title = FormatHelp.gamesAllTitle(gamesPerSet),
                    options = GamesAll.entries.filter { it != GamesAll.FAST4 || gamesPerSet == FAST4_SET_LENGTH },
                    selected = gamesAll,
                    label = { it.label },
                    onSelect = {
                        if (it != gamesAll) {
                            setTiebreak = it != GamesAll.ADVANTAGE_SET
                            fast4 = it == GamesAll.FAST4
                            // An advantage set starts with no limit; one is chosen below.
                            setGamesCap = 0
                            // Some ways of playing the final set need an ordinary tiebreak in the others.
                            if (finalSet !in finalSetChoices(setTiebreak, fast4)) {
                                finalSet = FinalSetRule.SAME_AS_OTHER_SETS
                            }
                        }
                    },
                    caption = if (gamesAll == GamesAll.FAST4) {
                        FormatHelp.fast4(gamesPerSet)
                    } else {
                        FormatHelp.gamesAll(gamesPerSet, setTiebreak, MatchConfig(sport).tiebreakPoints)
                    },
                )
                if (!setTiebreak) {
                    OptionGroup(
                        title = FormatHelp.capTitle(),
                        options = listOf(gamesPerSet + 2, gamesPerSet + 3, gamesPerSet + 4, 0),
                        selected = setGamesCap,
                        label = { FormatHelp.capLabel(it) },
                        onSelect = { setGamesCap = it },
                        caption = FormatHelp.cap(gamesPerSet, setGamesCap),
                    )
                }
                if (bestOf > 1) {
                    OptionGroup(
                        title = "Final set",
                        options = finalSetChoices(setTiebreak, fast4),
                        selected = finalSet,
                        label = { Labels.finalSet(it) },
                        onSelect = { finalSet = it },
                        caption = FormatHelp.finalSet(finalSet, MatchConfig(sport).matchTiebreakPoints),
                    )
                }
            }

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
                        config = if (pointsMatch) {
                            Sessions.pointsConfig(sport, pointsTotal, firstServer, doubles)
                        } else {
                            Sessions.config(
                                sport = sport,
                                bestOf = bestOf,
                                gamesPerSet = gamesPerSet,
                                deuceRule = deuceRule,
                                setTiebreak = setTiebreak,
                                setGamesCap = setGamesCap,
                                fast4Tiebreak = fast4,
                                finalSetRule = finalSet,
                                firstServer = firstServer,
                                playAllSets = playAllSets && bestOf > 1,
                                doubles = doubles,
                            )
                        },
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

/** Games that win a set: a short set, the standard one, and the two usual pro sets. */
private val SET_LENGTHS = listOf(4, 6, 8, 9)

/** Fast4 is played in sets to this many games. */
private const val FAST4_SET_LENGTH = 4

/** The usual lengths of an Americano match, and 0 for one that is timed. */
private val POINTS_TOTALS = listOf(16, 24, 32, 0)
private const val DEFAULT_POINTS = 24

/** How a set that reaches games-all is settled. */
private enum class GamesAll(val label: String) {
    TIEBREAK("Tiebreak"),
    FAST4("Fast4"),
    ADVANTAGE_SET("Advantage set"),
}

/**
 * The ways a final set can be played, given how the other sets are settled:
 * "No tiebreak" and "Tiebreak to 10" only mean something when the other sets
 * end in an ordinary tiebreak.
 */
private fun finalSetChoices(setTiebreak: Boolean, fast4: Boolean): List<FinalSetRule> = FinalSetRule.entries.filter {
    when (it) {
        FinalSetRule.SAME_AS_OTHER_SETS, FinalSetRule.MATCH_TIEBREAK -> true
        FinalSetRule.ADVANTAGE_SET -> setTiebreak
        FinalSetRule.LONG_TIEBREAK -> setTiebreak && !fast4
    }
}
