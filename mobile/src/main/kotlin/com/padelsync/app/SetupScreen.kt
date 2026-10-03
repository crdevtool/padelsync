package com.padelsync.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.netsports.core.engine.DeuceRule
import com.netsports.core.engine.FinalSetRule
import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.Sport
import com.netsports.core.engine.Team
import com.padelsync.kit.Labels

/** Lets the player choose the match format. */
@Composable
fun SetupScreen(onStart: (MatchConfig) -> Unit, onBack: () -> Unit) {
    var sport by rememberSaveable { mutableStateOf(Sport.PADEL) }
    var bestOf by rememberSaveable { mutableStateOf(3) }
    var deuceRule by rememberSaveable { mutableStateOf(DeuceRule.GOLDEN_POINT) }
    var finalSet by rememberSaveable { mutableStateOf(FinalSetRule.SAME_AS_OTHER_SETS) }
    var firstServer by rememberSaveable { mutableStateOf(Team.A) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back", fontSize = 16.sp) }
            Text("New match", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.OnBackground)
        }
        Spacer(Modifier.height(12.dp))

        OptionGroup(
            title = "Sport",
            options = Sport.entries,
            selected = sport,
            label = { Labels.sport(it) },
            onSelect = {
                sport = it
                // Each sport's usual way of settling deuce.
                deuceRule = if (it == Sport.PADEL) DeuceRule.GOLDEN_POINT else DeuceRule.ADVANTAGE
            },
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
        OptionGroup(
            title = "First to serve",
            options = Team.entries,
            selected = firstServer,
            label = { Labels.team(it) },
            onSelect = { firstServer = it },
        )

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = {
                onStart(
                    MatchConfig(
                        sport = sport,
                        bestOf = bestOf,
                        deuceRule = deuceRule,
                        finalSetRule = finalSet,
                        firstServer = firstServer,
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Text("Start match", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** A titled row of mutually exclusive choices. */
@Composable
private fun <T> OptionGroup(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Text(title, fontSize = 15.sp, color = Palette.Muted, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in options) {
                val isSelected = option == selected
                Box(
                    Modifier
                        .weight(1f)
                        .height(56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isSelected) Palette.Accent else Palette.SurfaceHigh)
                        .clickable(role = Role.RadioButton) { onSelect(option) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label(option),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) Palette.OnAccent else Palette.OnBackground,
                    )
                }
            }
        }
    }
}
