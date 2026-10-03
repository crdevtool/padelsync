package com.netsports.core.ui

import com.netsports.core.engine.GameKind
import com.netsports.core.engine.MatchState
import com.netsports.core.engine.PointStake
import com.netsports.core.engine.ScoringEngine
import com.netsports.core.engine.SetScore
import com.netsports.core.engine.Team

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
) {
    companion object {
        fun of(state: MatchState): ScoreView {
            val (highlight, team) = highlightOf(state)
            return ScoreView(
                pointsA = state.pointLabel(Team.A),
                pointsB = state.pointLabel(Team.B),
                gamesA = state.gamesA,
                gamesB = state.gamesB,
                setsA = state.setsWonBy(Team.A),
                setsB = state.setsWonBy(Team.B),
                completedSets = state.completedSets,
                setSummary = state.completedSets.joinToString(" ") { describe(it) },
                server = if (state.isComplete) null else state.server,
                highlight = highlight,
                highlightTeam = team,
                isTiebreak = state.isTiebreak,
                winner = state.winner,
                canUndo = state.totalPointsPlayed > 0,
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
        private fun describe(set: SetScore): String {
            val pointsA = set.tiebreakPointsA
            val pointsB = set.tiebreakPointsB
            if (pointsA == null || pointsB == null) return "${set.gamesA}-${set.gamesB}"
            if (set.isMatchTiebreak) return "[$pointsA-$pointsB]"
            return "${set.gamesA}-${set.gamesB}(${minOf(pointsA, pointsB)})"
        }
    }
}
