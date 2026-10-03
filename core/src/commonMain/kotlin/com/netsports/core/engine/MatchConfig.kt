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
    }

    val setsToWin: Int
        get() = bestOf / 2 + 1

    companion object {
        const val MAX_TARGET = 99

        /** Common club padel format: best of 3, golden point, tiebreak at 6-6. */
        fun padel(): MatchConfig = MatchConfig(sport = Sport.PADEL, deuceRule = DeuceRule.GOLDEN_POINT)

        /** Standard tennis format: best of 3, advantage, tiebreak at 6-6. */
        fun tennis(): MatchConfig = MatchConfig(sport = Sport.TENNIS)
    }
}
