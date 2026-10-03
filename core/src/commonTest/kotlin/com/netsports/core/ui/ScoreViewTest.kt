package com.netsports.core.ui

import com.netsports.core.engine.ServeSide
import com.netsports.core.match.MatchSnapshot
import com.netsports.core.match.Roster
import com.netsports.core.engine.FinalSetRule
import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.ScoringEngine
import com.netsports.core.engine.Team
import com.netsports.core.engine.play
import com.netsports.core.engine.reachGamesAll
import com.netsports.core.engine.winGames
import com.netsports.core.engine.winSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoreViewTest {
    private val tennis = MatchConfig.tennis()
    private val padel = MatchConfig.padel()

    private fun view(config: MatchConfig, points: String) = ScoreView.of(play(ScoringEngine.start(config), points))

    @Test
    fun aFreshMatch() {
        val view = view(tennis, "")
        assertEquals("0", view.pointsA)
        assertEquals("0", view.pointsB)
        assertEquals(Team.A, view.server)
        assertEquals(Highlight.NONE, view.highlight)
        assertEquals("", view.setSummary)
        assertFalse(view.canUndo)
        assertNull(view.winner)
    }

    @Test
    fun undoIsAvailableOnceAPointIsPlayed() {
        assertTrue(view(tennis, "A").canUndo)
    }

    @Test
    fun gamePointBelongsToTheServer() {
        val view = view(tennis, "AAA")
        assertEquals(Highlight.GAME_POINT, view.highlight)
        assertEquals(Team.A, view.highlightTeam)
    }

    @Test
    fun breakPointBelongsToTheReceiver() {
        val view = view(tennis, "BBB")
        assertEquals(Highlight.BREAK_POINT, view.highlight)
        assertEquals(Team.B, view.highlightTeam)
    }

    @Test
    fun deuceAndAdvantage() {
        assertEquals(Highlight.DEUCE, view(tennis, "AAABBB").highlight)

        val advantage = view(tennis, "AAABBBB")
        assertEquals("40", advantage.pointsA)
        assertEquals("AD", advantage.pointsB)
        assertEquals(Highlight.BREAK_POINT, advantage.highlight)
        assertEquals(Team.B, advantage.highlightTeam)
    }

    @Test
    fun goldenPointIsADecidingPointForBothTeams() {
        val view = view(padel, "AAABBB")
        assertEquals(Highlight.DECIDING_POINT, view.highlight)
        assertNull(view.highlightTeam)
    }

    @Test
    fun setPointOutranksGamePoint() {
        val view = ScoreView.of(play(winGames(ScoringEngine.start(tennis), Team.A, 5), "AAA"))
        assertEquals(Highlight.SET_POINT, view.highlight)
        assertEquals(Team.A, view.highlightTeam)
        assertEquals(5, view.gamesA)
    }

    @Test
    fun matchPointOnAGoldenPointBelongsToOneTeamOnly() {
        // A leads by a set and 5-0; at 40-40 only A can win the match.
        var state = winGames(winSet(ScoringEngine.start(padel), Team.A), Team.A, 5)
        state = play(state, "AAABBB")
        val view = ScoreView.of(state)
        assertEquals(Highlight.MATCH_POINT, view.highlight)
        assertEquals(Team.A, view.highlightTeam)
    }

    @Test
    fun tiebreakIsCalledOutUntilSomethingBiggerIsAtStake() {
        val start = reachGamesAll(ScoringEngine.start(tennis))
        assertEquals(Highlight.TIEBREAK, ScoreView.of(start).highlight)
        assertTrue(ScoreView.of(start).isTiebreak)
        assertEquals(Highlight.SET_POINT, ScoreView.of(play(start, "AAAAAA")).highlight)
    }

    @Test
    fun aFinishedMatch() {
        val view = ScoreView.of(winSet(winSet(ScoringEngine.start(tennis), Team.B), Team.B))
        assertEquals(Highlight.MATCH_WON, view.highlight)
        assertEquals(Team.B, view.highlightTeam)
        assertEquals(Team.B, view.winner)
        assertNull(view.server)
        assertEquals(0, view.setsA)
        assertEquals(2, view.setsB)
        assertEquals("0-6 0-6", view.setSummary)
        assertTrue(view.canUndo)
    }

    @Test
    fun setSummaryShowsTiebreaksAndMatchTiebreaks() {
        val config = padel.copy(finalSetRule = FinalSetRule.MATCH_TIEBREAK)
        var state = play(reachGamesAll(ScoringEngine.start(config)), "AAAAAABBBBBA")
        state = winSet(state, Team.B)
        state = play(state, "A".repeat(8) + "B".repeat(10))
        assertEquals("7-6(5) 0-6 [8-10]", ScoreView.of(state).setSummary)
        assertEquals(Team.B, ScoreView.of(state).winner)
    }

    // --- Names, server and deciding the match -------------------------------

    private val roster = Roster(listOf("Ana", "Leo"), listOf("Mia", "Sam"))

    @Test
    fun withoutNamesTeamsAreCalledAAndB() {
        val view = view(padel, "")
        assertEquals("Team A", view.nameA)
        assertEquals("Team B", view.nameB)
        assertEquals("Team A", view.serverName)
        assertTrue(view.playersA.isEmpty())
        assertTrue(view.doubles)
        assertFalse(view(tennis, "").doubles)
    }

    @Test
    fun namesAndTheServingPlayerAreShown() {
        val start = ScoreView.of(ScoringEngine.start(padel), roster)
        assertEquals("Ana & Leo", start.nameA)
        assertEquals("Mia & Sam", start.nameB)
        assertEquals("Ana", start.serverName)
        assertEquals(ServeSide.RIGHT, start.serveSide)

        val afterOnePoint = ScoreView.of(play(ScoringEngine.start(padel), "B"), roster)
        assertEquals("Ana", afterOnePoint.serverName)
        assertEquals(ServeSide.LEFT, afterOnePoint.serveSide)

        val secondGame = ScoreView.of(play(ScoringEngine.start(padel), "AAAA"), roster)
        assertEquals(Team.B, secondGame.server)
        assertEquals("Mia", secondGame.serverName)

        val thirdGame = ScoreView.of(play(ScoringEngine.start(padel), "AAAAAAAA"), roster)
        assertEquals("Leo", thirdGame.serverName)
        assertEquals(1, thirdGame.serverPlayerIndex)
    }

    @Test
    fun aSwappedOrderShowsTheOtherPlayer() {
        val state = ScoringEngine.start(padel)
        assertEquals("Leo", ScoreView.of(state, roster, serveFlipA = true).serverName)
        // Team B's swap does not affect team A's game.
        assertEquals("Ana", ScoreView.of(state, roster, serveFlipB = true).serverName)
        val snapshot = MatchSnapshot(1, 1, 5, padel, List(4) { Team.A }, roster = roster, serveFlipB = true)
        assertEquals("Sam", ScoreView.of(snapshot).serverName)
    }

    @Test
    fun aHalfNamedTeamFallsBackToTheTeamNameForTheUnnamedPlayer() {
        val partial = Roster(listOf("Ana"), emptyList())
        val thirdGame = ScoreView.of(play(ScoringEngine.start(padel), "AAAAAAAA"), partial)
        assertEquals("Ana", thirdGame.nameA)
        assertEquals("Ana", thirdGame.serverName)
        val secondGame = ScoreView.of(play(ScoringEngine.start(padel), "AAAA"), partial)
        assertEquals("Team B", secondGame.serverName)
    }

    @Test
    fun nothingIsServedOnceTheMatchIsOver() {
        val done = ScoreView.of(winSet(winSet(ScoringEngine.start(padel), Team.A), Team.A), roster)
        assertNull(done.server)
        assertNull(done.serverName)
        assertNull(done.serveSide)
        assertEquals(2, done.setNumber)
    }

    @Test
    fun aDecidedMatchStillBeingPlayedIsNotYetWon() {
        val social = padel.copy(playAllSets = true)
        val decided = ScoreView.of(winSet(winSet(ScoringEngine.start(social), Team.A), Team.A), roster)
        assertNull(decided.winner)
        assertEquals(Team.A, decided.decidedWinner)
        assertEquals(Highlight.NONE, decided.highlight)
        assertEquals(3, decided.setNumber)
        assertEquals("6-0 6-0", decided.setSummary)

        val matchPoint = ScoreView.of(play(winGames(winSet(ScoringEngine.start(social), Team.A), Team.A, 5), "AAA"))
        assertEquals(Highlight.MATCH_POINT, matchPoint.highlight)
        assertEquals(Team.A, matchPoint.highlightTeam)

        val lastSetPoint = ScoreView.of(
            play(winGames(winSet(winSet(ScoringEngine.start(social), Team.A), Team.A), Team.B, 5), "BBB"),
        )
        assertEquals(Highlight.SET_POINT, lastSetPoint.highlight)
        assertEquals(Team.B, lastSetPoint.highlightTeam)
    }

    @Test
    fun changeEndsIsFlagged() {
        assertTrue(view(padel, "AAAA").changeEnds)
        assertFalse(view(padel, "AAAAA").changeEnds)
    }
}
