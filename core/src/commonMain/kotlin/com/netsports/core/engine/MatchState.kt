package com.netsports.core.engine

/**
 * Final score of a completed set.
 *
 * A set settled by a tiebreak is recorded as e.g. 7-6 with the tiebreak
 * points; a match tiebreak is recorded as 1-0.
 */
data class SetScore(
    val gamesA: Int,
    val gamesB: Int,
    val tiebreakPointsA: Int? = null,
    val tiebreakPointsB: Int? = null,
    val isMatchTiebreak: Boolean = false,
) {
    val winner: Team
        get() = if (gamesA > gamesB) Team.A else Team.B

    fun gamesOf(team: Team): Int = if (team == Team.A) gamesA else gamesB

    fun tiebreakPointsOf(team: Team): Int? = if (team == Team.A) tiebreakPointsA else tiebreakPointsB
}

/**
 * Immutable snapshot of the score.
 *
 * Points are stored as raw counts (0, 1, 2, 3, 4...) rather than tennis
 * labels. Deuce, advantage and deciding points are all derived from the
 * counts, which keeps the state machine free of special-case states. Use
 * [pointLabel] for display.
 */
data class MatchState(
    val config: MatchConfig,
    /** Sets already finished, oldest first. */
    val completedSets: List<SetScore>,
    /** Games in the set currently being played. */
    val gamesA: Int,
    val gamesB: Int,
    /** Raw point counts in the game currently being played. */
    val pointsA: Int,
    val pointsB: Int,
    val gameKind: GameKind,
    /** Team that served (or will serve) the first point of the current game. */
    val gameFirstServer: Team,
    val totalPointsPlayed: Int,
    /** Winner of the match, or `null` while it is in progress. */
    val winner: Team? = null,
) {
    val isComplete: Boolean
        get() = winner != null

    val isTiebreak: Boolean
        get() = gameKind != GameKind.STANDARD

    /** 1-based number of the set in progress (or of the last set once complete). */
    val currentSetNumber: Int
        get() = if (isComplete) completedSets.size else completedSets.size + 1

    fun gamesOf(team: Team): Int = if (team == Team.A) gamesA else gamesB

    fun pointsOf(team: Team): Int = if (team == Team.A) pointsA else pointsB

    fun setsWonBy(team: Team): Int = completedSets.count { it.winner == team }

    /**
     * Team serving the next point.
     *
     * In a standard game one team serves throughout. In any tiebreak the
     * first server serves one point, then service alternates every two points.
     */
    val server: Team
        get() {
            if (gameKind == GameKind.STANDARD) return gameFirstServer
            val played = pointsA + pointsB
            return if (((played + 1) / 2) % 2 == 0) gameFirstServer else gameFirstServer.opponent
        }

    /** True at 40-40 (and at every later tie) in a standard game. */
    val isDeuce: Boolean
        get() = gameKind == GameKind.STANDARD && pointsA == pointsB && pointsA >= 3

    /**
     * True when the next point wins the game for whoever takes it, under the
     * golden point or star point rule.
     */
    val isDecidingPoint: Boolean
        get() {
            val decidingDeuce = config.deuceRule.decidingDeuce ?: return false
            // The n-th deuce happens at (n + 2) points each: 3-3 is the 1st deuce.
            return isDeuce && pointsA - 2 >= decidingDeuce
        }

    /** Team holding advantage, or `null` if nobody does. */
    val advantage: Team?
        get() {
            if (gameKind != GameKind.STANDARD) return null
            if (pointsA < 3 || pointsB < 3 || pointsA == pointsB) return null
            return if (pointsA > pointsB) Team.A else Team.B
        }

    /**
     * Display label for [team]'s score in the current game: `0`, `15`, `30`,
     * `40`, `AD`, or the raw point count during a tiebreak.
     */
    fun pointLabel(team: Team): String {
        val own = pointsOf(team)
        if (gameKind != GameKind.STANDARD) return own.toString()
        val other = pointsOf(team.opponent)
        if (own >= 3 && other >= 3) return if (own > other) "AD" else "40"
        return STANDARD_LABELS[own]
    }

    private companion object {
        val STANDARD_LABELS = listOf("0", "15", "30", "40")
    }
}
