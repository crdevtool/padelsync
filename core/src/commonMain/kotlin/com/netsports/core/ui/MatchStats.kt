package com.netsports.core.ui

import com.netsports.core.engine.GameKind
import com.netsports.core.engine.ScoringEngine
import com.netsports.core.engine.Team
import com.netsports.core.match.MatchSnapshot

/** One team's numbers for a match so far. */
data class TeamStats(
    /** Points won, in every game and tiebreak. */
    val points: Int,
    /** Games won across all sets. A tiebreak counts as one game. */
    val games: Int,
    /** Games won against the other team's serve. Tiebreaks are not counted. */
    val breaks: Int,
    /** Most points won in a row. */
    val longestStreak: Int,
)

/**
 * Figures derived from the list of points, for the end-of-match screen and
 * the "on a roll" cue during play. Like the score itself they are computed
 * by replaying the points, so every device shows the same numbers.
 *
 * @property streakTeam the team that won the last point, or `null` before
 * the first point. [streak] is how many it has now won in a row.
 */
data class MatchStats(
    val teamA: TeamStats,
    val teamB: TeamStats,
    val streakTeam: Team?,
    val streak: Int,
) {
    fun of(team: Team): TeamStats = if (team == Team.A) teamA else teamB

    val totalPoints: Int
        get() = teamA.points + teamB.points

    companion object {
        fun of(snapshot: MatchSnapshot): MatchStats {
            val points = IntArray(2)
            val games = IntArray(2)
            val breaks = IntArray(2)
            val longest = IntArray(2)
            var streakTeam: Team? = null
            var streak = 0

            var state = ScoringEngine.start(snapshot.config)
            for (team in snapshot.points) {
                val next = ScoringEngine.pointWonBy(state, team)
                val index = team.ordinal
                points[index]++

                streak = if (team == streakTeam) streak + 1 else 1
                streakTeam = team
                longest[index] = maxOf(longest[index], streak)

                // The point ended a game if the game score moved or a set was
                // completed. Whoever won the point won that game.
                val gameEnded = next.completedSets.size != state.completedSets.size ||
                    next.gamesA != state.gamesA || next.gamesB != state.gamesB
                if (gameEnded) {
                    games[index]++
                    if (state.gameKind == GameKind.STANDARD && state.server != team) breaks[index]++
                }
                state = next
            }
            return MatchStats(
                teamA = TeamStats(points[0], games[0], breaks[0], longest[0]),
                teamB = TeamStats(points[1], games[1], breaks[1], longest[1]),
                streakTeam = streakTeam,
                streak = streak,
            )
        }
    }
}
