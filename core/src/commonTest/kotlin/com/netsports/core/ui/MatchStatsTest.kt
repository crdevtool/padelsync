package com.netsports.core.ui

import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.Team
import com.netsports.core.match.MatchSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MatchStatsTest {
    private fun stats(points: String, config: MatchConfig = MatchConfig.padel()): MatchStats {
        val teams = points.map { if (it == 'A') Team.A else Team.B }
        return MatchStats.of(MatchSnapshot(1, 1, teams.size, config, teams))
    }

    @Test
    fun aNewMatchHasNoNumbers() {
        val stats = stats("")
        assertEquals(TeamStats(0, 0, 0, 0), stats.teamA)
        assertEquals(TeamStats(0, 0, 0, 0), stats.teamB)
        assertNull(stats.streakTeam)
        assertEquals(0, stats.streak)
        assertEquals(0, stats.totalPoints)
    }

    @Test
    fun pointsAndStreaksAreCounted() {
        val stats = stats("AABAAAB")
        assertEquals(5, stats.teamA.points)
        assertEquals(2, stats.teamB.points)
        assertEquals(3, stats.teamA.longestStreak)
        assertEquals(1, stats.teamB.longestStreak)
        assertEquals(Team.B, stats.streakTeam)
        assertEquals(1, stats.streak)
        assertEquals(7, stats.totalPoints)
    }

    @Test
    fun holdsAndBreaksAreToldApart() {
        // A serves and holds, B serves and is broken, A serves and is broken.
        val stats = stats("AAAA" + "AAAA" + "BBBB")
        assertEquals(2, stats.teamA.games)
        assertEquals(1, stats.teamA.breaks)
        assertEquals(1, stats.teamB.games)
        assertEquals(1, stats.teamB.breaks)
        assertEquals(Team.B, stats.streakTeam)
        assertEquals(4, stats.streak)
        assertEquals(8, stats.of(Team.A).longestStreak)
    }

    @Test
    fun aTiebreakIsAGameButNeverABreak() {
        // 6-6, then A wins the tiebreak 7-0.
        val stats = stats("AAAABBBB".repeat(6) + "AAAAAAA")
        assertEquals(7, stats.teamA.games)
        assertEquals(6, stats.teamB.games)
        // Every standard game was held: A served and won the odd games, B the even ones.
        assertEquals(0, stats.teamA.breaks)
        assertEquals(0, stats.teamB.breaks)
    }

    @Test
    fun theLastGameOfASetCounts() {
        val stats = stats("A".repeat(24))
        assertEquals(6, stats.teamA.games)
        assertEquals(3, stats.teamA.breaks)
        assertEquals(24, stats.teamA.longestStreak)
    }
}
