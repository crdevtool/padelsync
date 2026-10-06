package com.netsports.core.engine

/**
 * Immutable description of a match format. Validated on construction, so an
 * instance is always playable. Numeric limits also guarantee that every field
 * fits in one byte on the wire.
 */
data class MatchConfig(
    val sport: Sport,
    /** Maximum number of sets: 1, 3 or 5. */
    val bestOf: Int = 3,
    /**
     * Games needed to win a set (with a two-game margin). A set tiebreak,
     * when enabled, is played at [gamesPerSet]-all.
     */
    val gamesPerSet: Int = 6,
    val deuceRule: DeuceRule = DeuceRule.ADVANTAGE,
    /** Whether sets are settled by a tiebreak. When false, every set is an advantage set. */
    val setTiebreak: Boolean = true,
    /** Points needed to win a set tiebreak (with a two-point margin). */
    val tiebreakPoints: Int = 7,
    val finalSetRule: FinalSetRule = FinalSetRule.SAME_AS_OTHER_SETS,
    /** Points needed to win a match tiebreak (with a two-point margin). */
    val matchTiebreakPoints: Int = 10,
    /** Team serving the first game of the match. */
    val firstServer: Team = Team.A,
    /**
     * Play every set even after one team has won the match, as is common in
     * social padel. The winner is still the team with more sets.
     */
    val playAllSets: Boolean = false,
    /** Two players a side. Only affects which player is shown as serving. */
    val doubles: Boolean = false,
    /**
     * In sets played without a tiebreak ([setTiebreak] off), the number of
     * games that wins a set outright, whatever the margin: with 8, a set that
     * reaches 7-7 is settled by one last game. `0` means no limit, the
     * classic advantage set, which goes on until a team is two games ahead.
     */
    val setGamesCap: Int = 0,
    /**
     * A points match, as in Americano: every rally is one point and there
     * are no games or sets. The settings for sets, deuce and tiebreaks are
     * then unused.
     */
    val pointsMatch: Boolean = false,
    /**
     * In a [pointsMatch], the number of points played in all, after which
     * the match is over: with 24, a result is 14-10 or 12-12. `0` means the
     * match has no set end: a timed match, ended by hand when time is up.
     */
    val pointsTotal: Int = 0,
    /**
     * Play the set tiebreak one game early: at 3-3 in a set to four games,
     * as in Fast4.
     */
    val earlyTiebreak: Boolean = false,
    /**
     * A set tiebreak needs no two-point margin: at one point short of its
     * length for both teams, the next point wins it. As in Fast4.
     */
    val tiebreakSuddenDeath: Boolean = false,
) {
    init {
        require(bestOf == 1 || bestOf == 3 || bestOf == 5) { "bestOf must be 1, 3 or 5, was $bestOf" }
        require(gamesPerSet in 1..MAX_TARGET) { "gamesPerSet must be in 1..$MAX_TARGET, was $gamesPerSet" }
        require(tiebreakPoints in 1..MAX_TARGET) { "tiebreakPoints must be in 1..$MAX_TARGET, was $tiebreakPoints" }
        require(matchTiebreakPoints in 1..MAX_TARGET) {
            "matchTiebreakPoints must be in 1..$MAX_TARGET, was $matchTiebreakPoints"
        }
        require(!(finalSetRule == FinalSetRule.MATCH_TIEBREAK && bestOf == 1)) {
            "a match tiebreak needs a best-of-3 or best-of-5 match"
        }
        require(setGamesCap == 0 || setGamesCap in (gamesPerSet + 1)..MAX_TARGET) {
            "setGamesCap must be 0 or in ${gamesPerSet + 1}..$MAX_TARGET, was $setGamesCap"
        }
        require(setGamesCap == 0 || !setTiebreak) { "a cap on games needs sets without a tiebreak" }
        require(pointsTotal == 0 || pointsTotal in 2..MAX_TARGET) {
            "pointsTotal must be 0 or in 2..$MAX_TARGET, was $pointsTotal"
        }
        require(pointsTotal == 0 || pointsMatch) { "pointsTotal needs a points match" }
        require(!earlyTiebreak || (setTiebreak && gamesPerSet >= 2)) {
            "an early tiebreak needs tiebreak sets of at least two games"
        }
        require(!tiebreakSuddenDeath || setTiebreak) { "a sudden-death tiebreak needs tiebreak sets" }
        require(!(finalSetRule == FinalSetRule.LONG_TIEBREAK && !setTiebreak)) {
            "a long final-set tiebreak needs tiebreak sets"
        }
    }

    val setsToWin: Int
        get() = bestOf / 2 + 1

    /** The games-all score at which a set tiebreak is played. */
    val tiebreakAt: Int
        get() = if (earlyTiebreak) gamesPerSet - 1 else gamesPerSet

    /** A points match with no set end, which is over only when it is ended by hand. */
    val isOpenEnded: Boolean
        get() = pointsMatch && pointsTotal == 0

    /** Fast4's way of settling a set: a short sudden-death tiebreak, one game early. */
    val isFast4Tiebreak: Boolean
        get() = earlyTiebreak && tiebreakSuddenDeath

    companion object {
        const val MAX_TARGET = 99

        /** Club padel: doubles, best of 3, advantage, tiebreak at 6-6. */
        fun padel(): MatchConfig = MatchConfig(sport = Sport.PADEL, doubles = true)

        /** Standard tennis format: best of 3, advantage, tiebreak at 6-6. */
        fun tennis(): MatchConfig = MatchConfig(sport = Sport.TENNIS)
    }
}
