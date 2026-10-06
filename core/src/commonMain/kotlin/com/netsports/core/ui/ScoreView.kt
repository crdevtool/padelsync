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

    /** A points match that ended with the scores level. */
    MATCH_DRAWN,

    /** Golden point or star point: the next point wins the game for either team. */
    DECIDING_POINT,
    GAME_POINT,
    BREAK_POINT,
    SET_POINT,
    MATCH_POINT,
    MATCH_WON,
}

/**
 * Who serves the next point and from which side, worded for a watch: `LEO`
 * and `RIGHT`. The two parts are kept apart so a screen can colour them
 * differently.
 *
 * @property who the server: the player's name, `PLAYER 2` when the name was
 * not given, or `SERVE` in singles without names.
 * @property side `LEFT` or `RIGHT`.
 */
data class ServeLine(val who: String, val side: String) {
    /** `LEO · RIGHT` */
    override fun toString(): String = "$who · $side"
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
 * @property drawn true for a finished points match with level scores, which
 * has no [winner].
 * @property pointsMatch true for a points match (Americano): there are no
 * games or sets to show, only [pointsA] and [pointsB].
 * @property pointsTotal points played in all in a points match, or 0 if it
 * has no set end (a timed match) or is not a points match.
 * @property pointsPlayed points played so far in the whole match.
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
    val drawn: Boolean = false,
    val pointsMatch: Boolean = false,
    val pointsTotal: Int = 0,
    val pointsPlayed: Int = 0,
) {
    /** The match is over: it has a [winner], or it was [drawn]. */
    val isOver: Boolean
        get() = winner != null || drawn

    /** A points match with no set end, which is over only when someone ends it. */
    val canBeFinished: Boolean
        get() = pointsMatch && pointsTotal == 0 && pointsPlayed > 0 && !isOver

    /**
     * Where a points match has got to, short enough for a watch: `23 OF 24`
     * for the point about to be played, or `POINT 23` when the match has no
     * set end. `null` for a match of sets, and once the match is over.
     */
    val rallyLine: String?
        get() = when {
            !pointsMatch || isOver -> null
            pointsTotal == 0 -> "POINT ${pointsPlayed + 1}"
            else -> "${pointsPlayed + 1} OF $pointsTotal"
        }

    fun nameOf(team: Team): String = if (team == Team.A) nameA else nameB

    fun playersOf(team: Team): List<String> = if (team == Team.A) playersA else playersB

    fun pointsOf(team: Team): String = if (team == Team.A) pointsA else pointsB

    fun gamesOf(team: Team): Int = if (team == Team.A) gamesA else gamesB

    fun setsOf(team: Team): Int = if (team == Team.A) setsA else setsB

    /**
     * The serve line to show on [team]'s half of a watch, or `null` if that
     * team is not serving the next point.
     *
     * @param compact for a small screen: names are cut shorter and an unnamed
     * doubles player is `P2` instead of `PLAYER 2`.
     */
    fun serveLine(team: Team, compact: Boolean = false): ServeLine? {
        if (server != team) return null
        val side = serveSide ?: return null
        val player = playersOf(team).getOrNull(serverPlayerIndex)
        val who = when {
            player != null -> shorten(player, if (compact) COMPACT_NAME_LENGTH else NAME_LENGTH).uppercase()
            doubles -> if (compact) "P${serverPlayerIndex + 1}" else "PLAYER ${serverPlayerIndex + 1}"
            else -> "SERVE"
        }
        return ServeLine(who, if (side == ServeSide.RIGHT) "RIGHT" else "LEFT")
    }

    /** 1-based number of the set being played, or of the last set once the match is over. */
    val setNumber: Int
        get() = if (isOver) completedSets.size else completedSets.size + 1

    companion object {
        /** As long as `PLAYER 1`, the widest thing a serve line says without a name. */
        private const val NAME_LENGTH = 8

        /** What fits beside the side on a small round watch. */
        private const val COMPACT_NAME_LENGTH = 5

        /** The first [length] characters of [name], without splitting a character in two. */
        private fun shorten(name: String, length: Int): String {
            if (name.length <= length) return name
            val end = if (name[length - 1].isHighSurrogate()) length - 1 else length
            return name.substring(0, end).trim()
        }

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
                setSummary = if (state.config.pointsMatch) {
                    // The result of a points match is its two totals.
                    if (state.isComplete) "${state.pointsA}-${state.pointsB}" else ""
                } else {
                    state.completedSets.joinToString(" ") { describe(it) }
                },
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
                drawn = state.drawn,
                pointsMatch = state.config.pointsMatch,
                pointsTotal = state.config.pointsTotal,
                pointsPlayed = state.totalPointsPlayed,
            )
        }

        private fun highlightOf(state: MatchState): Pair<Highlight, Team?> {
            state.winner?.let { return Highlight.MATCH_WON to it }
            if (state.drawn) return Highlight.MATCH_DRAWN to null

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
