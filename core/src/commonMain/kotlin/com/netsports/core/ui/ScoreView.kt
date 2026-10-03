package com.netsports.core.ui

import com.netsports.core.engine.GameKind
import com.netsports.core.engine.MatchState
import com.netsports.core.engine.PointStake
import com.netsports.core.engine.ScoringEngine
import com.netsports.core.engine.ServeSide
import com.netsports.core.engine.SetScore
import com.netsports.core.engine.Team
import com.netsports.core.match.MatchSnapshot
import com.netsports.core.match.Roster

/** The single most important thing to call out about the next point. */
enum class Highlight {
    NONE,
    TIEBREAK,
    DEUCE,

    /** Golden point or star point: the next point wins the game for either team. */
    DECIDING_POINT,
    GAME_POINT,
    BREAK_POINT,
    SET_POINT,
    MATCH_POINT,
    MATCH_WON,
}

/**
 * Everything a scoreboard needs to draw, already formatted.
 *
 * Every app (phone and watch, Android and Apple) renders this same value, so
 * the wording of the score and the choice of what to highlight cannot differ
 * between devices.
 *
 * @property highlightTeam the team the [highlight] belongs to, or `null` when
 * it applies to both or to neither.
 * @property server team serving the next point, or `null` once the match is over.
 * @property setSummary completed sets as text, for example `6-4 6-7(5) [10-8]`.
 * @property nameA team A as the players named it: `Ana & Leo`, or `Team A`.
 * @property playersA team A's player names in serving order; empty if none were given.
 * @property serverName who serves the next point: the player's name when it
 * is known, otherwise the team's name. `null` once the match is over.
 * @property serverPlayerIndex which of the serving team's players serves, 0 or 1.
 * @property serveSide side the next serve is hit from, or `null` once the match is over.
 * @property decidedWinner the team that has won enough sets to win the match.
 * Differs from [winner] only while the remaining sets are still being played.
 * @property changeEnds true at the moments players swap ends.
 */
data class ScoreView(
    val pointsA: String,
    val pointsB: String,
    val gamesA: Int,
    val gamesB: Int,
    val setsA: Int,
    val setsB: Int,
    val completedSets: List<SetScore>,
    val setSummary: String,
    val server: Team?,
    val highlight: Highlight,
    val highlightTeam: Team?,
    val isTiebreak: Boolean,
    val winner: Team?,
    val canUndo: Boolean,
    val nameA: String = Roster.EMPTY.teamName(Team.A),
    val nameB: String = Roster.EMPTY.teamName(Team.B),
    val playersA: List<String> = emptyList(),
    val playersB: List<String> = emptyList(),
    val doubles: Boolean = false,
    val serverName: String? = null,
    val serverPlayerIndex: Int = 0,
    val serveSide: ServeSide? = null,
    val decidedWinner: Team? = null,
    val changeEnds: Boolean = false,
) {
    fun nameOf(team: Team): String = if (team == Team.A) nameA else nameB

    fun playersOf(team: Team): List<String> = if (team == Team.A) playersA else playersB

    fun pointsOf(team: Team): String = if (team == Team.A) pointsA else pointsB

    fun gamesOf(team: Team): Int = if (team == Team.A) gamesA else gamesB

    fun setsOf(team: Team): Int = if (team == Team.A) setsA else setsB

    /** 1-based number of the set being played, or of the last set once the match is over. */
    val setNumber: Int
        get() = if (winner != null) completedSets.size else completedSets.size + 1

    companion object {
        /** The scoreboard for a match as replicated between devices. */
        fun of(snapshot: MatchSnapshot): ScoreView =
            of(snapshot.state, snapshot.roster, snapshot.serveFlipA, snapshot.serveFlipB)

        /**
         * The scoreboard for [state].
         *
         * @param serveFlipA whether team A's serving order is swapped from
         * the order its players are listed in; likewise [serveFlipB].
         */
        fun of(
            state: MatchState,
            roster: Roster = Roster.EMPTY,
            serveFlipA: Boolean = false,
            serveFlipB: Boolean = false,
        ): ScoreView {
            val (highlight, team) = highlightOf(state)
            val server = if (state.isComplete) null else state.server
            val serverIndex = when (server) {
                null -> 0
                Team.A -> state.serverPlayerIndex(serveFlipA)
                Team.B -> state.serverPlayerIndex(serveFlipB)
            }
            return ScoreView(
                pointsA = state.pointLabel(Team.A),
                pointsB = state.pointLabel(Team.B),
                gamesA = state.gamesA,
                gamesB = state.gamesB,
                setsA = state.setsWonBy(Team.A),
                setsB = state.setsWonBy(Team.B),
                completedSets = state.completedSets,
                setSummary = state.completedSets.joinToString(" ") { describe(it) },
                server = server,
                highlight = highlight,
                highlightTeam = team,
                isTiebreak = state.isTiebreak,
                winner = state.winner,
                canUndo = state.totalPointsPlayed > 0,
                nameA = roster.teamName(Team.A),
                nameB = roster.teamName(Team.B),
                playersA = roster.teamA,
                playersB = roster.teamB,
                doubles = state.config.doubles,
                serverName = server?.let { roster.playerName(it, serverIndex) ?: roster.teamName(it) },
                serverPlayerIndex = serverIndex,
                serveSide = if (server == null) null else state.serveSide,
                decidedWinner = state.decidedWinner,
                changeEnds = state.changeEnds,
            )
        }

        private fun highlightOf(state: MatchState): Pair<Highlight, Team?> {
            state.winner?.let { return Highlight.MATCH_WON to it }

            val stakeA = ScoringEngine.stakeFor(state, Team.A)
            val stakeB = ScoringEngine.stakeFor(state, Team.B)

            /** The team holding [stake], or `null` if both or neither hold it. */
            fun holder(stake: PointStake): Team? = when {
                stakeA == stake && stakeB != stake -> Team.A
                stakeB == stake && stakeA != stake -> Team.B
                else -> null
            }

            fun anyone(stake: PointStake) = stakeA == stake || stakeB == stake

            return when {
                anyone(PointStake.MATCH_POINT) -> Highlight.MATCH_POINT to holder(PointStake.MATCH_POINT)
                anyone(PointStake.SET_POINT) -> Highlight.SET_POINT to holder(PointStake.SET_POINT)
                state.isDecidingPoint -> Highlight.DECIDING_POINT to null
                anyone(PointStake.GAME_POINT) -> {
                    val team = holder(PointStake.GAME_POINT)
                    val isBreak = state.gameKind == GameKind.STANDARD && team != null && team != state.server
                    (if (isBreak) Highlight.BREAK_POINT else Highlight.GAME_POINT) to team
                }
                state.isDeuce -> Highlight.DEUCE to null
                state.isTiebreak -> Highlight.TIEBREAK to null
                else -> Highlight.NONE to null
            }
        }

        /** `6-4`, `7-6(5)` with the loser's tiebreak points, or `[10-8]` for a match tiebreak. */
        internal fun describe(set: SetScore): String {
            val pointsA = set.tiebreakPointsA
            val pointsB = set.tiebreakPointsB
            if (pointsA == null || pointsB == null) return "${set.gamesA}-${set.gamesB}"
            if (set.isMatchTiebreak) return "[$pointsA-$pointsB]"
            return "${set.gamesA}-${set.gamesB}(${minOf(pointsA, pointsB)})"
        }
    }
}
