package com.netsports.core.ui

import com.netsports.core.engine.DeuceRule
import com.netsports.core.engine.FinalSetRule
import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.Team
import com.netsports.core.match.MatchSnapshot
import com.netsports.core.match.Roster
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScoreSpeechTest {
    /** Padel as most of these tests want it: with the golden point. */
    private val padel = MatchConfig.padel().copy(deuceRule = DeuceRule.GOLDEN_POINT)
    private val tennis = MatchConfig.tennis()
    private val roster = Roster(listOf("Ana", "Leo"), listOf("Mia", "Sam"))
    private val all = SpeechSettings()

    private fun snapshot(
        points: String,
        config: MatchConfig = padel,
        roster: Roster = Roster.EMPTY,
        extraCommands: Int = 0,
        serveFlipA: Boolean = false,
        matchId: Long = 1,
    ): MatchSnapshot {
        val teams = points.map { if (it == 'A') Team.A else Team.B }
        return MatchSnapshot(matchId, 1, teams.size + extraCommands, config, teams, roster = roster, serveFlipA = serveFlipA)
    }

    /** What is said when [last] is played after [points]. */
    private fun say(
        points: String,
        last: String,
        config: MatchConfig = padel,
        roster: Roster = Roster.EMPTY,
        settings: SpeechSettings = all,
    ): List<String> = ScoreSpeech.announce(snapshot(points, config, roster), snapshot(points + last, config, roster), settings)

    // --- Points --------------------------------------------------------------

    @Test
    fun pointsAreCalledServerFirst() {
        assertEquals(listOf("15 love."), say("", "A"))
        assertEquals(listOf("love 15."), say("", "B"))
        assertEquals(listOf("15 all."), say("A", "B"))
        assertEquals(listOf("30 15."), say("AB", "A"))
        assertEquals(listOf("40 30."), say("ABAB", "A"))
        // Second game: B serves, so B's score comes first.
        assertEquals(listOf("love 15."), say("AAAA", "A"))
        assertEquals(listOf("15 love."), say("AAAA", "B"))
    }

    @Test
    fun aPointsMatchIsCalledInPlainCounts() {
        val americano = MatchConfig(com.netsports.core.engine.Sport.PADEL, doubles = true, pointsMatch = true, pointsTotal = 6)
        assertEquals(listOf("1 love, Team A."), say("", "A", americano))
        assertEquals(listOf("1 all."), say("A", "B", americano))
        assertEquals(listOf("2 1, Ana and Leo."), say("AB", "A", americano, roster))
        // After four points the serve moves to the other team.
        assertEquals(listOf("2 all.", "Mia to serve."), say("ABA", "B", americano, roster))

        assertEquals(listOf("Match over.", "Ana and Leo win, 4 to 2."), say("ABABA", "A", americano, roster))
        assertEquals(listOf("Match over.", "A draw, 3 all."), say("ABABA", "B", americano, roster))
        assertEquals(listOf("Match over.", "Team B win, 6 to love."), say("BBBBB", "B", americano))
    }

    @Test
    fun deuceAdvantageAndTheDecidingPoint() {
        assertEquals(listOf("Deuce."), say("AAABB", "B", tennis))
        assertEquals(listOf("Advantage, Team B."), say("AAABBB", "B", tennis))
        assertEquals(listOf("Deuce."), say("AAABBBB", "A", tennis))
        assertEquals(listOf("Advantage, Ana."), say("AAABBB", "A", tennis, Roster(listOf("Ana"), listOf("Mia"))))

        assertEquals(listOf("Golden point."), say("AAABB", "B", padel))

        val star = tennis.copy(deuceRule = DeuceRule.STAR_POINT)
        assertEquals(listOf("Deuce."), say("AAABB", "B", star))
        assertEquals(listOf("Deciding point."), say("AAABBB" + "AB" + "A", "B", star))
    }

    @Test
    fun bigPointsAreCalledOut() {
        // 40-30 on serve is just a game point: not announced.
        assertEquals(listOf("40 30."), say("ABAB", "A", tennis))
        // 30-40 against the serve is a break point.
        assertEquals(listOf("30 40.", "Break point."), say("ABAB", "B", tennis))
        // 5-0 with B serving at 0-40: set point for A.
        assertEquals(listOf("love 40.", "Set point, Team A."), say("A".repeat(20) + "AA", "A", tennis))
        // 6-0 5-0, B serving at 0-40: match point for A.
        assertEquals(
            listOf("love 40.", "Match point, Ana and Leo."),
            say("A".repeat(24 + 20) + "AA", "A", padel, roster),
        )
        // A golden point at 5-4: only the team ahead can take the set with it.
        val fiveFour = "AAAABBBB".repeat(4) + "AAAA"
        assertEquals(listOf("Golden point.", "Set point, Team A."), say(fiveFour + "AAABB", "B", padel))
        // In a tiebreak at 6-6 in points nobody holds set point; at 6-5 one team does.
        val sixAll = "AAAABBBB".repeat(6)
        assertEquals(
            listOf("6 5, Team A.", "Set point, Team A.", "Team A to serve."),
            say(sixAll + "ABABABABAB", "A", padel),
        )
    }

    // --- Games, sets, match -------------------------------------------------

    @Test
    fun aGameIsCalledWithTheStandingAndTheNextServer() {
        assertEquals(
            listOf("Game, Team A.", "Team A lead 1 game to love.", "Change ends.", "Team B to serve."),
            say("AAA", "A"),
        )
        assertEquals(
            listOf("Game, Mia and Sam.", "1 game all.", "Leo to serve."),
            say("AAAA" + "BBB", "B", padel, roster),
        )
        assertEquals(
            listOf("Game, Ana.", "Ana leads 2 games to love.", "Ana to serve."),
            say("AAAA" + "AAA", "A", tennis, Roster(listOf("Ana"), listOf("Mia"))),
        )
    }

    @Test
    fun aTiebreakIsAnnouncedAndScoredInNumbers() {
        val sixAll = "AAAABBBB".repeat(6)
        assertEquals(
            listOf("Game, Team B.", "6 games all.", "Tiebreak.", "Team A to serve."),
            say(sixAll.dropLast(1), "B"),
        )
        assertEquals(listOf("1 love, Team A.", "Team B to serve."), say(sixAll, "A"))
        assertEquals(listOf("1 all."), say(sixAll + "A", "B"))
        assertEquals(listOf("2 1, Team B.", "Team A to serve."), say(sixAll + "AB", "B"))
        // Six points played: change ends. The serve does not move here.
        assertEquals(listOf("3 all.", "Change ends."), say(sixAll + "ABABA", "B"))
        // Set point in the tiebreak.
        assertEquals(listOf("6 love, Team A.", "Set point, Team A.", "Change ends."), say(sixAll + "AAAAA", "A"))
    }

    @Test
    fun aSetIsCalledWithItsScoreAndTheSetsStanding() {
        assertEquals(
            listOf("Game and set, Team A, 6 love.", "Team A lead 1 set to love.", "Team A to serve."),
            say("A".repeat(23), "A", tennis),
        )
        // 6-1 is an odd number of games: change ends into the next set.
        assertEquals(
            listOf("Game and set, Ana and Leo, 6 1.", "Ana and Leo lead 1 set to love.", "Change ends.", "Mia to serve."),
            say("BBBB" + "A".repeat(23), "A", padel, roster),
        )
        // One set all.
        assertEquals(
            listOf("Game and set, Team B, 6 love.", "1 set all.", "Team A to serve."),
            say("A".repeat(24) + "B".repeat(23), "B", tennis),
        )
    }

    @Test
    fun aMatchTiebreakIsAnnounced() {
        val config = padel.copy(finalSetRule = FinalSetRule.MATCH_TIEBREAK)
        val said = say("A".repeat(24) + "B".repeat(23), "B", config)
        assertEquals(listOf("Game and set, Team B, 6 love.", "1 set all.", "Match tiebreak.", "Team A to serve."), said)
    }

    @Test
    fun theMatchIsCalledWithEverySetFromTheWinnersSide() {
        assertEquals(
            listOf("Game, set and match, Team B.", "6 love, 6 love."),
            say("B".repeat(47), "B"),
        )
        val config = padel.copy(finalSetRule = FinalSetRule.MATCH_TIEBREAK)
        val oneAll = "A".repeat(24) + "B".repeat(24)
        assertEquals(
            listOf("Game, set and match, Mia and Sam.", "love 6, 6 love, 10 love."),
            say(oneAll + "B".repeat(9), "B", config, roster),
        )
    }

    @Test
    fun whenAllSetsArePlayedTheWinnerIsCalledAsSoonAsItIsKnown() {
        val social = padel.copy(playAllSets = true)
        assertEquals(
            listOf("Game and set, Ana and Leo, 6 love.", "Ana and Leo win the match.", "Playing set 3.", "Ana to serve."),
            say("A".repeat(47), "A", social, roster),
        )
        assertEquals(
            listOf("Game and set, Mia, 6 love.", "Mia wins the match.", "Playing set 3.", "Ana to serve."),
            say("B".repeat(47), "B", social.copy(doubles = false), Roster(listOf("Ana"), listOf("Mia"))),
        )
        // The last set then ends the match in the usual words.
        assertEquals(
            listOf("Game, set and match, Team A.", "6 love, 6 love, love 6."),
            say("A".repeat(48) + "B".repeat(23), "B", social),
        )
    }

    // --- Corrections, swaps, new matches ------------------------------------

    @Test
    fun anUndoIsCalledAsACorrectionWithTheScore() {
        fun undo(before: String, after: String, config: MatchConfig = padel) = ScoreSpeech.announce(
            snapshot(before, config),
            snapshot(after, config, extraCommands = before.length - after.length + 1),
            all,
        )
        assertEquals(listOf("Score corrected.", "15 love."), undo("AA", "A"))
        assertEquals(listOf("Score corrected.", "Love all."), undo("A", ""))
        assertEquals(listOf("Score corrected.", "40 love."), undo("AAAA", "AAA"))
        assertEquals(
            listOf("Score corrected.", "Team A lead 1 game to love.", "love 15."),
            undo("AAAA" + "AA", "AAAA" + "A"),
        )
        assertEquals(
            listOf("Score corrected.", "Team A lead 1 set to love.", "Team B lead 1 game to love."),
            undo("A".repeat(24) + "BBBBB", "A".repeat(24) + "BBBB", tennis),
        )
    }

    @Test
    fun swappingTheServerNamesTheNewServer() {
        val before = snapshot("A", roster = roster)
        val after = snapshot("A", roster = roster, extraCommands = 1, serveFlipA = true)
        assertEquals(listOf("Leo to serve."), ScoreSpeech.announce(before, after, all))
        // With no names the swap changes nothing anyone can hear.
        val unnamed = snapshot("A", extraCommands = 1, serveFlipA = true)
        assertTrue(ScoreSpeech.announce(snapshot("A"), unnamed, all).isEmpty())
    }

    @Test
    fun aNewMatchIsIntroducedButOneInProgressIsNot() {
        assertEquals(listOf("Team A to serve."), ScoreSpeech.announce(null, snapshot(""), all))
        assertEquals(
            listOf("Ana and Leo against Mia and Sam.", "Ana to serve."),
            ScoreSpeech.announce(snapshot("AAB", matchId = 1), snapshot("", roster = roster, matchId = 2), all),
        )
        assertTrue(ScoreSpeech.announce(null, snapshot("AAB"), all).isEmpty())
    }

    @Test
    fun aRepeatedSnapshotSaysNothing() {
        val same = snapshot("AAB")
        assertTrue(ScoreSpeech.announce(same, same, all).isEmpty())
        // A rename without a score change is silent too.
        assertTrue(ScoreSpeech.announce(same, snapshot("AAB", roster = roster), all).isEmpty())
    }

    // --- Settings ------------------------------------------------------------

    @Test
    fun eachKindOfAnnouncementCanBeSwitchedOff() {
        assertTrue(say("", "A", settings = SpeechSettings.OFF).isEmpty())
        assertTrue(say("", "A", settings = all.copy(points = false)).isEmpty())
        assertEquals(listOf("Break point."), say("ABAB", "B", tennis, settings = all.copy(points = false)))
        assertEquals(listOf("30 40."), say("ABAB", "B", tennis, settings = all.copy(stakes = false)))

        assertEquals(
            listOf("Change ends.", "Team B to serve."),
            say("AAA", "A", settings = all.copy(games = false)),
        )
        assertEquals(
            listOf("Game, Team A.", "Team A lead 1 game to love.", "Team B to serve."),
            say("AAA", "A", settings = all.copy(changeEnds = false)),
        )
        assertEquals(
            listOf("Game, Team A.", "Team A lead 1 game to love.", "Change ends."),
            say("AAA", "A", settings = all.copy(server = false)),
        )
        // Only games: a quiet scoreboard that speaks at the end of each game.
        val gamesOnly = SpeechSettings(points = false, stakes = false, server = false, changeEnds = false)
        assertTrue(say("", "A", settings = gamesOnly).isEmpty())
        assertEquals(listOf("Game, Team A.", "Team A lead 1 game to love."), say("AAA", "A", settings = gamesOnly))
        assertTrue(say("B".repeat(47), "B", settings = all.copy(games = false)).isEmpty())
    }

    @Test
    fun theReminderIntervalIsValidated() {
        assertEquals(5, SpeechSettings(reminderMinutes = 5).reminderMinutes)
        assertFailsWith<IllegalArgumentException> { SpeechSettings(reminderMinutes = -1) }
        assertFailsWith<IllegalArgumentException> { SpeechSettings(reminderMinutes = 61) }
    }

    // --- Reminder ------------------------------------------------------------

    @Test
    fun theReminderGivesTheWholeScore() {
        assertEquals(
            listOf("Score check.", "Love all.", "Team A to serve."),
            ScoreSpeech.reminder(snapshot("")),
        )
        assertEquals(
            listOf(
                "Score check.",
                "Ana and Leo lead 1 set to love.",
                "Mia and Sam lead 2 games to 1.",
                "30 15.",
                "Sam to serve.",
            ),
            ScoreSpeech.reminder(snapshot("A".repeat(24) + "BBBB" + "AAAA" + "BBBB" + "BBA", roster = roster)),
        )
        assertEquals(
            listOf("Game, set and match, Team A.", "6 love, 6 love."),
            ScoreSpeech.reminder(snapshot("A".repeat(48))),
        )
    }
}
