package com.netsports.core.ui

import com.netsports.core.engine.DeuceRule
import com.netsports.core.engine.FinalSetRule
import com.netsports.core.engine.MatchConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatHelpTest {
    @Test
    fun everyChoiceIsExplainedInOneLine() {
        assertEquals("One set decides the match.", FormatHelp.sets(1))
        assertEquals("Best of 5: the first team to win 3 sets wins the match.", FormatHelp.sets(5))
        assertEquals("Short set: first to 4 games, two games clear.", FormatHelp.setLength(4))
        assertEquals("Standard set: first to 6 games, two games clear.", FormatHelp.setLength(6))
        assertEquals("Pro set: first to 9 games, two games clear.", FormatHelp.setLength(9))
        for (rule in DeuceRule.entries) assertTrue(FormatHelp.deuce(rule).endsWith("."))
        for (rule in FinalSetRule.entries) assertTrue(FormatHelp.finalSet(rule, 10).endsWith("."))
        assertTrue(FormatHelp.finalSet(FinalSetRule.MATCH_TIEBREAK, 10).contains("to 10 points"))
    }

    @Test
    fun theWordingFollowsTheSetLength() {
        assertEquals("At 4-4", FormatHelp.gamesAllTitle(4))
        assertEquals(
            "Tiebreak: at 4-4 the set is decided by a tiebreak to 7 points.",
            FormatHelp.gamesAll(4, tiebreak = true, tiebreakPoints = 7),
        )
        assertEquals(
            "Advantage set: no tiebreak. At 6-6 you keep playing games.",
            FormatHelp.gamesAll(6, tiebreak = false, tiebreakPoints = 7),
        )
    }

    @Test
    fun aCapIsExplainedWithTheScoreThatTriggersIt() {
        assertEquals("First to 8", FormatHelp.capLabel(8))
        assertEquals("No limit", FormatHelp.capLabel(0))
        assertEquals(
            "The first team to reach 8 games wins the set. At 7-7 one last game decides it.",
            FormatHelp.cap(6, 8),
        )
        assertEquals(
            "The set goes on until a team is two games ahead: 8-6, 9-7 and so on.",
            FormatHelp.cap(6, 0),
        )
    }

    @Test
    fun pointsMatchesAndTheOtherFormatsAreExplained() {
        assertTrue(FormatHelp.scoring(pointsMatch = true).startsWith("Americano:"))
        assertTrue(FormatHelp.scoring(pointsMatch = false).startsWith("Sets:"))
        assertEquals("24 points", FormatHelp.matchLengthLabel(24))
        assertEquals("Timed", FormatHelp.matchLengthLabel(0))
        assertEquals(
            "The match is 24 points in all, so a result looks like 14-10. The serve changes every 4 points.",
            FormatHelp.matchLength(24),
        )
        assertTrue(FormatHelp.matchLength(0).startsWith("Timed:"))
        assertEquals(
            "Fast4: a tiebreak already at 3-3, first to 5 points. At 4-4 in it, the next point wins.",
            FormatHelp.fast4(4),
        )
        assertEquals(
            "Tiebreak to 10: a full last set, but its tiebreak is played to 10 points.",
            FormatHelp.finalSet(FinalSetRule.LONG_TIEBREAK, 10),
        )
        val americano = MatchConfig.padel().copy(pointsMatch = true, pointsTotal = 32)
        assertEquals("Americano · 32 points", FormatHelp.pointsLine(americano))
        assertEquals("Americano · Timed", FormatHelp.pointsLine(americano.copy(pointsTotal = 0)))
    }

    @Test
    fun onlyWhatIsUnusualIsAddedToAFormatLine() {
        assertEquals("", FormatHelp.extras(MatchConfig.padel()))
        assertEquals(" · Sets to 4", FormatHelp.extras(MatchConfig.padel().copy(gamesPerSet = 4)))
        assertEquals(" · Advantage sets", FormatHelp.extras(MatchConfig.tennis().copy(setTiebreak = false)))
        assertEquals(
            " · Sets to 4 · Advantage sets to 6",
            FormatHelp.extras(MatchConfig.tennis().copy(gamesPerSet = 4, setTiebreak = false, setGamesCap = 6)),
        )
    }
}
