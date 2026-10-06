package com.netsports.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoringEngineTest {
    private val tennis = MatchConfig.tennis()
    /** Padel as most of these tests want it: with the golden point. */
    private val padel = MatchConfig.padel().copy(deuceRule = DeuceRule.GOLDEN_POINT)

    // --- Standard game ---------------------------------------------------

    @Test
    fun startsAtLoveAllWithTheConfiguredServer() {
        val state = ScoringEngine.start(tennis.copy(firstServer = Team.B))
        assertEquals("0", state.pointLabel(Team.A))
        assertEquals("0", state.pointLabel(Team.B))
        assertEquals(Team.B, state.server)
        assertEquals(GameKind.STANDARD, state.gameKind)
        assertEquals(1, state.currentSetNumber)
        assertFalse(state.isComplete)
    }

    @Test
    fun labelsProgress15_30_40() {
        var state = ScoringEngine.start(tennis)
        for (label in listOf("15", "30", "40")) {
            state = play(state, "A")
            assertEquals(label, state.pointLabel(Team.A))
            assertEquals("0", state.pointLabel(Team.B))
        }
    }

    @Test
    fun fourStraightPointsWinTheGameAndResetThePoints() {
        val state = play(ScoringEngine.start(tennis), "AAAA")
        assertEquals(1, state.gamesA)
        assertEquals(0, state.gamesB)
        assertEquals(0, state.pointsA)
        assertEquals(0, state.pointsB)
        assertEquals(4, state.totalPointsPlayed)
    }

    @Test
    fun advantageGoesDeuceAdvantageDeuceThenGame() {
        var state = play(ScoringEngine.start(tennis), "AAABBB")
        assertTrue(state.isDeuce)
        assertFalse(state.isDecidingPoint)
        assertEquals("40", state.pointLabel(Team.A))
        assertEquals("40", state.pointLabel(Team.B))

        state = play(state, "A")
        assertEquals(Team.A, state.advantage)
        assertEquals("AD", state.pointLabel(Team.A))
        assertEquals("40", state.pointLabel(Team.B))
        assertEquals(0, state.gamesA)

        state = play(state, "B")
        assertTrue(state.isDeuce)
        assertNull(state.advantage)

        state = play(state, "BB")
        assertEquals(1, state.gamesB)
        assertEquals(0, state.gamesA)
    }

    @Test
    fun advantageNeverEndsOnAOnePointLead() {
        var state = play(ScoringEngine.start(tennis), "AAABBB")
        repeat(10) {
            state = play(state, "AB")
            assertTrue(state.isDeuce)
            assertEquals(0, state.gamesA + state.gamesB)
        }
    }

    @Test
    fun goldenPointDecidesTheFirstDeuce() {
        var state = play(ScoringEngine.start(padel), "AAABBB")
        assertTrue(state.isDeuce)
        assertTrue(state.isDecidingPoint)

        state = play(state, "B")
        assertEquals(1, state.gamesB)
        assertEquals(0, state.pointsA)
    }

    @Test
    fun starPointPlaysAdvantageTwiceThenDecidesTheThirdDeuce() {
        var state = play(ScoringEngine.start(padel.copy(deuceRule = DeuceRule.STAR_POINT)), "AAABBB")
        assertFalse(state.isDecidingPoint, "first deuce")

        state = play(state, "A")
        assertEquals(Team.A, state.advantage)
        state = play(state, "B")
        assertFalse(state.isDecidingPoint, "second deuce")

        state = play(state, "AB")
        assertTrue(state.isDeuce)
        assertTrue(state.isDecidingPoint, "third deuce")

        state = play(state, "B")
        assertEquals(1, state.gamesB)
    }

    @Test
    fun starPointGameCanStillBeWonOnAdvantage() {
        val state = play(ScoringEngine.start(padel.copy(deuceRule = DeuceRule.STAR_POINT)), "AAABBBAA")
        assertEquals(1, state.gamesA)
    }

    // --- Set ---------------------------------------------------------------

    @Test
    fun setIsWon6_4() {
        var state = winGames(ScoringEngine.start(tennis), Team.A, 5)
        state = winGames(state, Team.B, 4)
        state = winGame(state, Team.A)
        assertEquals(listOf(SetScore(6, 4)), state.completedSets)
        assertEquals(0, state.gamesA)
        assertEquals(0, state.gamesB)
        assertEquals(2, state.currentSetNumber)
    }

    @Test
    fun setIsNotWonAt6_5AndIsWon7_5() {
        var state = winGames(ScoringEngine.start(tennis), Team.A, 5)
        state = winGames(state, Team.B, 5)
        state = winGame(state, Team.A)
        assertTrue(state.completedSets.isEmpty())
        assertEquals(6, state.gamesA)
        assertEquals(5, state.gamesB)

        state = winGame(state, Team.A)
        assertEquals(listOf(SetScore(7, 5)), state.completedSets)
    }

    @Test
    fun setGoesToATiebreakAt6_6() {
        val state = reachGamesAll(ScoringEngine.start(tennis))
        assertEquals(6, state.gamesA)
        assertEquals(6, state.gamesB)
        assertEquals(GameKind.TIEBREAK, state.gameKind)
        assertTrue(state.isTiebreak)
        assertEquals("0", state.pointLabel(Team.A))
    }

    @Test
    fun tiebreakUsesNumericPointsAndIsWon7_5() {
        var state = play(reachGamesAll(ScoringEngine.start(tennis)), "AAAAAABBBBB")
        assertEquals("6", state.pointLabel(Team.A))
        assertEquals("5", state.pointLabel(Team.B))
        assertFalse(state.isDeuce)

        state = play(state, "A")
        assertEquals(listOf(SetScore(7, 6, tiebreakPointsA = 7, tiebreakPointsB = 5)), state.completedSets)
        assertEquals(GameKind.STANDARD, state.gameKind)
    }

    @Test
    fun tiebreakMustBeWonByTwoPoints() {
        var state = play(reachGamesAll(ScoringEngine.start(tennis)), "AAAAAABBBBBB")
        state = play(state, "A")
        assertTrue(state.completedSets.isEmpty(), "7-6 is not enough")
        state = play(state, "B")
        assertTrue(state.completedSets.isEmpty(), "7-7")

        state = play(state, "BB")
        assertEquals(listOf(SetScore(6, 7, tiebreakPointsA = 7, tiebreakPointsB = 9)), state.completedSets)
        assertEquals(Team.B, state.completedSets.single().winner)
    }

    @Test
    fun goldenPointDoesNotApplyInsideATiebreak() {
        val state = play(reachGamesAll(ScoringEngine.start(padel)), "AAABBB")
        assertFalse(state.isDeuce)
        assertFalse(state.isDecidingPoint)
        assertEquals(GameKind.TIEBREAK, state.gameKind)
    }

    @Test
    fun advantageSetHasNoTiebreakAndNeedsTwoClearGames() {
        var state = reachGamesAll(ScoringEngine.start(tennis.copy(setTiebreak = false)))
        assertEquals(GameKind.STANDARD, state.gameKind)

        state = winGame(state, Team.A)
        assertTrue(state.completedSets.isEmpty(), "7-6")
        state = winGame(state, Team.A)
        assertEquals(listOf(SetScore(8, 6)), state.completedSets)
    }

    @Test
    fun aCappedAdvantageSetEndsWhenATeamReachesTheCap() {
        val config = tennis.copy(setTiebreak = false, setGamesCap = 8)
        var state = reachGamesAll(ScoringEngine.start(config))
        assertEquals(GameKind.STANDARD, state.gameKind, "no tiebreak at 6-6")

        state = winGame(state, Team.A)
        assertTrue(state.completedSets.isEmpty(), "7-6 is not enough")
        state = winGame(state, Team.B)
        assertTrue(state.completedSets.isEmpty(), "7-7")
        assertEquals(GameKind.STANDARD, state.gameKind, "no tiebreak at 7-7 either")

        // One last game decides it, so a game point is a set point for both.
        state = play(state, "AAABB")
        assertEquals(PointStake.SET_POINT, ScoringEngine.stakeFor(state, Team.A))
        assertEquals(PointStake.NONE, ScoringEngine.stakeFor(state, Team.B))
        state = play(state, "A")
        assertEquals(listOf(SetScore(8, 7)), state.completedSets)
        assertEquals(0, state.gamesA)
    }

    @Test
    fun aCappedAdvantageSetIsStillWonByTwoClearGamesBelowTheCap() {
        val config = tennis.copy(setTiebreak = false, setGamesCap = 8)
        // 7-5: over before the cap matters.
        var state = winGames(ScoringEngine.start(config), Team.A, 5)
        state = winGames(state, Team.B, 5)
        state = winGame(state, Team.A)
        assertTrue(state.completedSets.isEmpty(), "6-5 is not enough")
        state = winGame(state, Team.A)
        assertEquals(listOf(SetScore(7, 5)), state.completedSets)

        // 8-6: two clear games and the cap at once.
        state = reachGamesAll(ScoringEngine.start(config))
        state = winGames(state, Team.B, 2)
        assertEquals(listOf(SetScore(6, 8)), state.completedSets)
    }

    @Test
    fun aHigherCapAndShortSetsWorkTheSameWay() {
        // Capped at 10: 8-8 and 9-9 are played on, 10-9 ends it.
        var state = reachGamesAll(ScoringEngine.start(tennis.copy(setTiebreak = false, setGamesCap = 10)), games = 9)
        assertTrue(state.completedSets.isEmpty(), "9-9")
        state = winGame(state, Team.B)
        assertEquals(listOf(SetScore(9, 10)), state.completedSets)

        // Sets to four games, capped at six.
        val short = padel.copy(gamesPerSet = 4, setTiebreak = false, setGamesCap = 6)
        state = reachGamesAll(ScoringEngine.start(short), games = 5)
        assertTrue(state.completedSets.isEmpty(), "5-5")
        state = winGame(state, Team.A)
        assertEquals(listOf(SetScore(6, 5)), state.completedSets)
    }

    @Test
    fun aCapMustBeAboveTheSetLengthAndNeedsSetsWithoutATiebreak() {
        assertFailsWith<IllegalArgumentException> { tennis.copy(setTiebreak = false, setGamesCap = 6) }
        assertFailsWith<IllegalArgumentException> { tennis.copy(setTiebreak = false, setGamesCap = 100) }
        assertFailsWith<IllegalArgumentException> { tennis.copy(setGamesCap = 8) }
        assertEquals(7, tennis.copy(setTiebreak = false, setGamesCap = 7).setGamesCap)
    }

    // --- Points matches (Americano) ------------------------------------------

    private val americano = MatchConfig(Sport.PADEL, doubles = true, pointsMatch = true, pointsTotal = 24)
    private val timed = americano.copy(pointsTotal = 0)

    @Test
    fun aPointsMatchCountsRalliesAndEndsAtItsTotal() {
        var state = ScoringEngine.start(americano)
        assertEquals(GameKind.POINTS, state.gameKind)
        assertFalse(state.isTiebreak)

        state = play(state, "A".repeat(13) + "B".repeat(10))
        assertEquals("13", state.pointLabel(Team.A))
        assertEquals("10", state.pointLabel(Team.B))
        assertFalse(state.isComplete)
        assertEquals(0, state.gamesA)
        assertTrue(state.completedSets.isEmpty())

        state = play(state, "A")
        assertTrue(state.isComplete)
        assertEquals(Team.A, state.winner)
        assertEquals(Team.A, state.decidedWinner)
        assertFalse(state.drawn)
        assertEquals(14, state.pointsA)
        assertFailsWith<IllegalStateException> { ScoringEngine.pointWonBy(state, Team.B) }
    }

    @Test
    fun aPointsMatchCanEndLevel() {
        val state = play(ScoringEngine.start(americano), "AB".repeat(12))
        assertTrue(state.isComplete)
        assertTrue(state.drawn)
        assertNull(state.winner)
        assertNull(state.decidedWinner)
    }

    @Test
    fun inAPointsMatchTheServeMovesOnEveryFourPoints() {
        var state = ScoringEngine.start(americano.copy(firstServer = Team.B))
        // B's first player, A's first, B's second, A's second, then round again.
        val expected = listOf(Team.B to 0, Team.A to 0, Team.B to 1, Team.A to 1, Team.B to 0)
        for ((team, player) in expected) {
            for (point in 0 until 4) {
                assertEquals(team, state.server)
                assertEquals(player, state.serverPlayerIndex())
                assertEquals(if (point % 2 == 0) ServeSide.RIGHT else ServeSide.LEFT, state.serveSide)
                assertFalse(state.changeEnds)
                state = play(state, "A")
            }
        }
    }

    @Test
    fun onlyTheLastPointOfAPointsMatchCanBeAMatchPoint() {
        val state = play(ScoringEngine.start(americano), "A".repeat(12) + "B".repeat(11))
        // A wins 13-11 with it; B would only draw level.
        assertEquals(PointStake.MATCH_POINT, ScoringEngine.stakeFor(state, Team.A))
        assertEquals(PointStake.NONE, ScoringEngine.stakeFor(state, Team.B))
        assertEquals(PointStake.NONE, ScoringEngine.stakeFor(play(ScoringEngine.start(americano), "AAAA"), Team.A))
    }

    @Test
    fun aTimedMatchRunsUntilItIsEndedByHand() {
        var state = play(ScoringEngine.start(timed), "A".repeat(40) + "B".repeat(31))
        assertFalse(state.isComplete)
        state = ScoringEngine.finish(state)
        assertEquals(Team.A, state.winner)
        assertTrue(state.isComplete)
        // Ending it twice changes nothing.
        assertEquals(state, ScoringEngine.finish(state))

        val level = ScoringEngine.finish(play(ScoringEngine.start(timed), "ABBA"))
        assertTrue(level.drawn)
        assertNull(level.winner)

        // A match that ends by its score cannot be ended by hand.
        assertFailsWith<IllegalArgumentException> { ScoringEngine.finish(ScoringEngine.start(americano)) }
        assertFailsWith<IllegalArgumentException> { ScoringEngine.finish(ScoringEngine.start(tennis)) }
    }

    @Test
    fun aPointsTotalNeedsAPointsMatch() {
        assertFailsWith<IllegalArgumentException> { tennis.copy(pointsTotal = 24) }
        assertFailsWith<IllegalArgumentException> { americano.copy(pointsTotal = 1) }
        assertFailsWith<IllegalArgumentException> { americano.copy(pointsTotal = 100) }
        assertTrue(timed.isOpenEnded)
        assertFalse(americano.isOpenEnded)
        assertFalse(tennis.isOpenEnded)
    }

    // --- Fast4 and the long final-set tiebreak -------------------------------

    private val fast4 = tennis.copy(
        gamesPerSet = 4,
        deuceRule = DeuceRule.GOLDEN_POINT,
        tiebreakPoints = 5,
        earlyTiebreak = true,
        tiebreakSuddenDeath = true,
    )

    @Test
    fun fast4PlaysAShortSuddenDeathTiebreakAtThreeAll() {
        var state = reachGamesAll(ScoringEngine.start(fast4), games = 3)
        assertEquals(GameKind.TIEBREAK, state.gameKind, "3-3")

        state = play(state, "AAAABBBB")
        assertTrue(state.completedSets.isEmpty(), "4-4 in the tiebreak")
        assertEquals(PointStake.SET_POINT, ScoringEngine.stakeFor(state, Team.A))
        assertEquals(PointStake.SET_POINT, ScoringEngine.stakeFor(state, Team.B))
        state = play(state, "B")
        assertEquals(listOf(SetScore(3, 4, tiebreakPointsA = 4, tiebreakPointsB = 5)), state.completedSets)
    }

    @Test
    fun fast4SetsAreStillWonOutrightBeforeThreeAll() {
        var state = winGames(ScoringEngine.start(fast4), Team.A, 3)
        state = winGames(state, Team.B, 2)
        state = winGame(state, Team.A)
        assertEquals(listOf(SetScore(4, 2)), state.completedSets)
    }

    @Test
    fun theLongTiebreakIsPlayedInTheFinalSetOnly() {
        val config = tennis.copy(finalSetRule = FinalSetRule.LONG_TIEBREAK)
        // First set: an ordinary tiebreak to seven.
        var state = play(reachGamesAll(ScoringEngine.start(config)), "A".repeat(7))
        assertEquals(1, state.completedSets.size)

        state = winSet(state, Team.B)
        state = reachGamesAll(state)
        assertEquals(GameKind.TIEBREAK, state.gameKind, "6-6 in the final set")
        state = play(state, "A".repeat(9))
        assertFalse(state.isComplete, "9-0 is not enough in a tiebreak to ten")
        state = play(state, "A")
        assertEquals(Team.A, state.winner)
        assertEquals(SetScore(7, 6, tiebreakPointsA = 10, tiebreakPointsB = 0), state.completedSets.last())
    }

    @Test
    fun tiebreakOptionsNeedSetsThatHaveATiebreak() {
        assertFailsWith<IllegalArgumentException> { tennis.copy(setTiebreak = false, earlyTiebreak = true) }
        assertFailsWith<IllegalArgumentException> { tennis.copy(setTiebreak = false, tiebreakSuddenDeath = true) }
        assertFailsWith<IllegalArgumentException> {
            tennis.copy(setTiebreak = false, finalSetRule = FinalSetRule.LONG_TIEBREAK)
        }
    }

    @Test
    fun shortSetsUseATiebreakAtGamesAll() {
        val state = reachGamesAll(ScoringEngine.start(padel.copy(gamesPerSet = 4)), games = 4)
        assertEquals(4, state.gamesA)
        assertEquals(4, state.gamesB)
        assertEquals(GameKind.TIEBREAK, state.gameKind)
    }

    // --- Match -------------------------------------------------------------

    @Test
    fun bestOf3IsWonInTwoStraightSets() {
        var state = winSet(ScoringEngine.start(tennis), Team.A)
        assertFalse(state.isComplete)
        assertEquals(1, state.setsWonBy(Team.A))

        state = winSet(state, Team.A)
        assertEquals(Team.A, state.winner)
        assertTrue(state.isComplete)
        assertEquals(2, state.completedSets.size)
        assertEquals(2, state.currentSetNumber)
    }

    @Test
    fun bestOf3GoesToAThirdSetAtOneSetAll() {
        var state = winSet(winSet(ScoringEngine.start(tennis), Team.A), Team.B)
        assertFalse(state.isComplete)
        assertEquals(3, state.currentSetNumber)
        assertEquals(GameKind.STANDARD, state.gameKind)

        state = winSet(state, Team.B)
        assertEquals(Team.B, state.winner)
    }

    @Test
    fun bestOf5NeedsThreeSets() {
        var state = winSet(winSet(ScoringEngine.start(tennis.copy(bestOf = 5)), Team.A), Team.A)
        assertFalse(state.isComplete)

        state = winSet(winSet(state, Team.B), Team.A)
        assertEquals(Team.A, state.winner)
        assertEquals(4, state.completedSets.size)
    }

    @Test
    fun bestOf1EndsAfterASingleSet() {
        val state = winSet(ScoringEngine.start(tennis.copy(bestOf = 1)), Team.B)
        assertEquals(Team.B, state.winner)
    }

    @Test
    fun matchTiebreakReplacesTheFinalSet() {
        val config = padel.copy(finalSetRule = FinalSetRule.MATCH_TIEBREAK)
        var state = winSet(ScoringEngine.start(config), Team.A)
        assertEquals(GameKind.STANDARD, state.gameKind, "second set is normal")

        state = winSet(state, Team.B)
        assertEquals(GameKind.MATCH_TIEBREAK, state.gameKind)

        state = play(state, "A".repeat(9))
        assertFalse(state.isComplete)
        state = play(state, "A")
        assertEquals(Team.A, state.winner)
        assertEquals(
            SetScore(1, 0, tiebreakPointsA = 10, tiebreakPointsB = 0, isMatchTiebreak = true),
            state.completedSets.last(),
        )
    }

    @Test
    fun matchTiebreakMustBeWonByTwoPoints() {
        val config = padel.copy(finalSetRule = FinalSetRule.MATCH_TIEBREAK)
        var state = winSet(winSet(ScoringEngine.start(config), Team.A), Team.B)
        state = play(state, "A".repeat(9) + "B".repeat(9))
        state = play(state, "B")
        assertFalse(state.isComplete, "9-10")
        state = play(state, "B")
        assertEquals(Team.B, state.winner)
        assertEquals(11, state.completedSets.last().tiebreakPointsB)
    }

    @Test
    fun advantageFinalSetKeepsTiebreaksInEarlierSetsOnly() {
        val config = tennis.copy(finalSetRule = FinalSetRule.ADVANTAGE_SET)
        var state = reachGamesAll(ScoringEngine.start(config))
        assertEquals(GameKind.TIEBREAK, state.gameKind, "first set")

        state = play(state, "A".repeat(7))
        state = winSet(state, Team.B)
        state = reachGamesAll(state)
        assertEquals(3, state.currentSetNumber)
        assertEquals(GameKind.STANDARD, state.gameKind, "deciding set")

        state = winGames(state, Team.B, 2)
        assertEquals(Team.B, state.winner)
        assertEquals(SetScore(6, 8), state.completedSets.last())
    }

    @Test
    fun aPointAfterTheMatchIsOverThrows() {
        val state = winSet(winSet(ScoringEngine.start(tennis), Team.A), Team.A)
        assertFailsWith<IllegalStateException> { ScoringEngine.pointWonBy(state, Team.A) }
    }

    @Test
    fun replayRejectsPointsAfterTheMatchEnded() {
        val points = List(49) { Team.A }
        assertFailsWith<IllegalArgumentException> { ScoringEngine.replay(tennis, points) }
        assertEquals(Team.A, ScoringEngine.replay(tennis, points.dropLast(1)).winner)
    }

    // --- Service -----------------------------------------------------------

    @Test
    fun serviceAlternatesEveryGame() {
        var state = ScoringEngine.start(tennis)
        assertEquals(Team.A, state.server)
        state = winGame(state, Team.A)
        assertEquals(Team.B, state.server)
        state = winGame(state, Team.A)
        assertEquals(Team.A, state.server)
    }

    @Test
    fun serviceDoesNotChangeDuringAStandardGame() {
        assertEquals(Team.A, play(ScoringEngine.start(tennis), "ABAB").server)
    }

    @Test
    fun tiebreakServiceIsOnePointThenAlternatingEveryTwo() {
        var state = reachGamesAll(ScoringEngine.start(tennis))
        val servers = ArrayList<Team>()
        for (point in "ABABABA") {
            servers += state.server
            state = play(state, point.toString())
        }
        assertEquals(listOf(Team.A, Team.B, Team.B, Team.A, Team.A, Team.B, Team.B), servers)
    }

    @Test
    fun tiebreakFirstServerReceivesFirstInTheNextSet() {
        var state = reachGamesAll(ScoringEngine.start(tennis))
        assertEquals(Team.A, state.gameFirstServer)
        state = play(state, "A".repeat(7))
        assertEquals(1, state.completedSets.size)
        assertEquals(Team.B, state.server)
    }

    // --- Point stakes ------------------------------------------------------

    @Test
    fun gamePointForTheServerIsNotABreakPoint() {
        val state = play(ScoringEngine.start(tennis), "AAA")
        assertEquals(PointStake.GAME_POINT, ScoringEngine.stakeFor(state, Team.A))
        assertEquals(PointStake.NONE, ScoringEngine.stakeFor(state, Team.B))
        assertFalse(ScoringEngine.isBreakPoint(state))
    }

    @Test
    fun gamePointForTheReceiverIsABreakPoint() {
        val state = play(ScoringEngine.start(tennis), "BBB")
        assertEquals(PointStake.GAME_POINT, ScoringEngine.stakeFor(state, Team.B))
        assertTrue(ScoringEngine.isBreakPoint(state))
    }

    @Test
    fun nothingIsAtStakeAtDeuceUnderAdvantageRules() {
        val state = play(ScoringEngine.start(tennis), "AAABBB")
        assertEquals(PointStake.NONE, ScoringEngine.stakeFor(state, Team.A))
        assertEquals(PointStake.NONE, ScoringEngine.stakeFor(state, Team.B))
    }

    @Test
    fun goldenPointIsAGamePointForBothTeams() {
        val state = play(ScoringEngine.start(padel), "AAABBB")
        assertEquals(PointStake.GAME_POINT, ScoringEngine.stakeFor(state, Team.A))
        assertEquals(PointStake.GAME_POINT, ScoringEngine.stakeFor(state, Team.B))
        assertTrue(ScoringEngine.isBreakPoint(state))
    }

    @Test
    fun setPoint() {
        val state = play(winGames(ScoringEngine.start(tennis), Team.A, 5), "AAA")
        assertEquals(PointStake.SET_POINT, ScoringEngine.stakeFor(state, Team.A))
        assertEquals(PointStake.NONE, ScoringEngine.stakeFor(state, Team.B))
    }

    @Test
    fun setPointInATiebreak() {
        val state = play(reachGamesAll(ScoringEngine.start(tennis)), "AAAAAABBBBB")
        assertEquals(PointStake.SET_POINT, ScoringEngine.stakeFor(state, Team.A))
        assertEquals(PointStake.NONE, ScoringEngine.stakeFor(state, Team.B))
        assertFalse(ScoringEngine.isBreakPoint(state))
    }

    @Test
    fun matchPoint() {
        var state = winSet(ScoringEngine.start(tennis), Team.A)
        state = play(winGames(state, Team.A, 5), "AAA")
        assertEquals(PointStake.MATCH_POINT, ScoringEngine.stakeFor(state, Team.A))
        assertEquals(PointStake.NONE, ScoringEngine.stakeFor(state, Team.B))
    }

    @Test
    fun nothingIsAtStakeOnceTheMatchIsOver() {
        val state = winSet(winSet(ScoringEngine.start(tennis), Team.A), Team.A)
        assertEquals(PointStake.NONE, ScoringEngine.stakeFor(state, Team.A))
        assertFalse(ScoringEngine.isBreakPoint(state))
    }

    // --- Config ------------------------------------------------------------

    @Test
    fun presets() {
        assertEquals(Sport.PADEL, MatchConfig.padel().sport)
        assertTrue(MatchConfig.padel().doubles)
        // Both sports start from advantage; golden point is a choice.
        assertEquals(DeuceRule.ADVANTAGE, MatchConfig.padel().deuceRule)
        assertEquals(DeuceRule.ADVANTAGE, tennis.deuceRule)
        assertEquals(2, tennis.setsToWin)
        assertEquals(3, tennis.copy(bestOf = 5).setsToWin)
        assertEquals(1, tennis.copy(bestOf = 1).setsToWin)
    }

    @Test
    fun configRejectsInvalidFormats() {
        assertFailsWith<IllegalArgumentException> { tennis.copy(bestOf = 2) }
        assertFailsWith<IllegalArgumentException> { tennis.copy(bestOf = 7) }
        assertFailsWith<IllegalArgumentException> { tennis.copy(gamesPerSet = 0) }
        assertFailsWith<IllegalArgumentException> { tennis.copy(tiebreakPoints = 0) }
        assertFailsWith<IllegalArgumentException> { tennis.copy(matchTiebreakPoints = 100) }
        assertFailsWith<IllegalArgumentException> {
            padel.copy(bestOf = 1, finalSetRule = FinalSetRule.MATCH_TIEBREAK)
        }
    }

    // --- Play every set ----------------------------------------------------

    private val social = padel.copy(playAllSets = true)

    @Test
    fun withPlayAllSetsTheMatchGoesOnAfterItIsDecided() {
        var state = winSet(winSet(ScoringEngine.start(social), Team.A), Team.A)
        assertFalse(state.isComplete)
        assertNull(state.winner)
        assertEquals(Team.A, state.decidedWinner)
        assertEquals(3, state.currentSetNumber)
        // The extra set is an ordinary set, not a match tiebreak.
        assertEquals(GameKind.STANDARD, state.gameKind)

        state = winSet(state, Team.B)
        assertTrue(state.isComplete)
        assertEquals(Team.A, state.winner)
        assertEquals(listOf(Team.A, Team.A, Team.B), state.completedSets.map { it.winner })
    }

    @Test
    fun withPlayAllSetsADecidingSetStillFollowsTheFinalSetRule() {
        val config = social.copy(finalSetRule = FinalSetRule.MATCH_TIEBREAK)
        val level = winSet(winSet(ScoringEngine.start(config), Team.A), Team.B)
        assertNull(level.decidedWinner)
        assertEquals(GameKind.MATCH_TIEBREAK, level.gameKind)

        // But at two sets to love the third set is a full set.
        val decided = winSet(winSet(ScoringEngine.start(config), Team.B), Team.B)
        assertEquals(GameKind.STANDARD, decided.gameKind)
        val done = winSet(decided, Team.A)
        assertEquals(Team.B, done.winner)
    }

    @Test
    fun withPlayAllSetsMatchPointIsThePointThatDecidesTheMatch() {
        // 6-0, 5-0, 40-0: the next point decides the match for A.
        val beforeDecision = play(winGames(winSet(ScoringEngine.start(social), Team.A), Team.A, 5), "AAA")
        assertEquals(PointStake.MATCH_POINT, ScoringEngine.stakeFor(beforeDecision, Team.A))

        // In the set played afterwards the last point is only a set point.
        val lastSet = play(winGames(winSet(winSet(ScoringEngine.start(social), Team.A), Team.A), Team.B, 5), "BBB")
        assertEquals(PointStake.SET_POINT, ScoringEngine.stakeFor(lastSet, Team.B))
        assertEquals(PointStake.NONE, ScoringEngine.stakeFor(lastSet, Team.A))
    }

    @Test
    fun withoutPlayAllSetsNothingChanges() {
        val state = winSet(winSet(ScoringEngine.start(padel), Team.A), Team.A)
        assertTrue(state.isComplete)
        assertEquals(Team.A, state.winner)
        assertEquals(Team.A, state.decidedWinner)
    }

    @Test
    fun aFiveSetMatchPlaysAllFive() {
        var state = ScoringEngine.start(social.copy(bestOf = 5))
        for (winner in listOf(Team.B, Team.B, Team.B, Team.A)) {
            state = winSet(state, winner)
            assertFalse(state.isComplete)
        }
        state = winSet(state, Team.A)
        assertEquals(Team.B, state.winner)
    }

    // --- Serving -------------------------------------------------------------

    @Test
    fun theServeStartsOnTheRightAndAlternates() {
        var state = ScoringEngine.start(padel)
        for (side in listOf(ServeSide.RIGHT, ServeSide.LEFT, ServeSide.RIGHT, ServeSide.LEFT)) {
            assertEquals(side, state.serveSide)
            state = play(state, "A")
        }
        // New game: back to the right.
        assertEquals(ServeSide.RIGHT, state.serveSide)
    }

    @Test
    fun inDoublesTheFourPlayersServeInRotation() {
        var state = ScoringEngine.start(padel)
        val order = ArrayList<String>()
        repeat(7) {
            order += "${state.server}${state.serverPlayerIndex()}"
            state = winGame(state, Team.A)
        }
        // The seventh game opens the next set, where the rotation starts again.
        assertEquals(listOf("A0", "B0", "A1", "B1", "A0", "B0", "A0"), order)
    }

    @Test
    fun swappingATeamsOrderMovesOnlyThatTeam() {
        val state = ScoringEngine.start(padel)
        assertEquals(0, state.serverPlayerIndex(flipped = false))
        assertEquals(1, state.serverPlayerIndex(flipped = true))
        val third = winGames(state, Team.A, 2)
        assertEquals(1, third.serverPlayerIndex(flipped = false))
        assertEquals(0, third.serverPlayerIndex(flipped = true))
    }

    @Test
    fun singlesAlwaysHasOneServer() {
        var state = ScoringEngine.start(tennis)
        repeat(5) {
            assertEquals(0, state.serverPlayerIndex())
            assertEquals(0, state.serverPlayerIndex(flipped = true))
            state = winGame(state, Team.B)
        }
    }

    @Test
    fun aTiebreakCarriesTheRotationOn() {
        var state = reachGamesAll(ScoringEngine.start(padel))
        assertEquals(GameKind.TIEBREAK, state.gameKind)
        // After B1 served game 12, the tiebreak goes A0, then B0 B0, A1 A1, B1 B1, A0 A0.
        val order = ArrayList<String>()
        repeat(9) {
            order += "${state.server}${state.serverPlayerIndex()}"
            state = play(state, if (it % 2 == 0) "A" else "B")
        }
        assertEquals(listOf("A0", "B0", "B0", "A1", "A1", "B1", "B1", "A0", "A0"), order)
    }

    @Test
    fun endsChangeAfterOddGamesAndEverySixTiebreakPoints() {
        var state = ScoringEngine.start(padel)
        assertFalse(state.changeEnds)
        val changes = ArrayList<Boolean>()
        repeat(4) {
            state = winGame(state, Team.A)
            changes += state.changeEnds
        }
        assertEquals(listOf(true, false, true, false), changes)
        // Not in the middle of a game.
        assertFalse(play(winGame(ScoringEngine.start(padel), Team.A), "A").changeEnds)

        // 6-0 is an even set: no change into the next set. 6-1 is odd: change.
        assertFalse(winSet(ScoringEngine.start(padel), Team.A).changeEnds)
        val sixOne = winGames(winGame(ScoringEngine.start(padel), Team.B), Team.A, 6)
        assertEquals(1, sixOne.completedSets.size)
        assertTrue(sixOne.changeEnds)

        var tiebreak = reachGamesAll(ScoringEngine.start(padel))
        val tiebreakChanges = ArrayList<Int>()
        repeat(12) {
            tiebreak = play(tiebreak, if (it % 2 == 0) "A" else "B")
            if (tiebreak.changeEnds) tiebreakChanges += tiebreak.pointsA + tiebreak.pointsB
        }
        assertEquals(listOf(6, 12), tiebreakChanges)

        // Never once the match is over.
        assertFalse(winSet(winSet(ScoringEngine.start(padel), Team.A), Team.A).changeEnds)
    }
}
