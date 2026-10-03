package com.netsports.core.ui

import com.netsports.core.engine.DeuceRule
import com.netsports.core.engine.MatchState
import com.netsports.core.engine.SetScore
import com.netsports.core.engine.Team
import com.netsports.core.match.MatchSnapshot
import com.netsports.core.match.Roster

/**
 * What a device says out loud. Each device has its own settings, so one
 * phone at the side of the court can call the score while the watches stay
 * silent.
 *
 * @property points call the score after every point ("30 15", "Deuce").
 * @property games call games, sets and the match as they are won.
 * @property stakes call break, set and match points.
 * @property server say who serves at the start of each game.
 * @property changeEnds say when to change ends.
 * @property reminderMinutes repeat the full score this often, or 0 for never.
 */
data class SpeechSettings(
    val enabled: Boolean = true,
    val points: Boolean = true,
    val games: Boolean = true,
    val stakes: Boolean = true,
    val server: Boolean = true,
    val changeEnds: Boolean = true,
    val reminderMinutes: Int = 0,
) {
    init {
        require(reminderMinutes in 0..MAX_REMINDER_MINUTES) { "reminderMinutes out of range: $reminderMinutes" }
    }

    companion object {
        const val MAX_REMINDER_MINUTES = 60

        val OFF = SpeechSettings(enabled = false)
    }
}

/**
 * Turns changes in the match into sentences for a text-to-speech voice, the
 * way a chair umpire would call them.
 *
 * The wording lives here, with the rules, so every platform says the same
 * thing. The phrases are plain English with digits ("40 30", "3 games to
 * 2"), which every speech engine reads correctly.
 */
object ScoreSpeech {

    /**
     * What to say now that the match moved from [before] to [after].
     *
     * @param before the match as it was last announced, or `null` if nothing
     * has been announced yet on this device.
     * @return sentences to speak in order; empty when there is nothing to say.
     */
    fun announce(before: MatchSnapshot?, after: MatchSnapshot, settings: SpeechSettings): List<String> {
        if (!settings.enabled) return emptyList()

        if (before == null || before.matchId != after.matchId) {
            // A new match is introduced. A match joined part-way through is
            // not: the players already know the score.
            if (after.points.isNotEmpty()) return emptyList()
            return buildList {
                if (after.roster != Roster.EMPTY) add("${name(after, Team.A)} against ${name(after, Team.B)}.")
                if (settings.server) add(toServe(after))
            }
        }
        if (before.epoch == after.epoch && before.version == after.version) return emptyList()

        val old = before.state
        val new = after.state
        return when {
            after.points.size < before.points.size ->
                listOf("Score corrected.") + standing(after)

            after.points.size == before.points.size ->
                // Only the serving order changed.
                if (settings.server && !new.isComplete && serverName(before) != serverName(after)) {
                    listOf(toServe(after))
                } else {
                    emptyList()
                }

            new.isComplete -> if (settings.games) matchWon(after) else emptyList()
            new.completedSets.size > old.completedSets.size -> setWon(old, after, settings)
            new.gamesA != old.gamesA || new.gamesB != old.gamesB -> gameWon(after, settings)
            else -> pointPlayed(before, after, settings)
        }
    }

    /** The whole score in one go, for the periodic reminder. */
    fun reminder(snapshot: MatchSnapshot): List<String> {
        if (snapshot.state.isComplete) return matchWon(snapshot)
        return listOf("Score check.") + standing(snapshot) + toServe(snapshot)
    }

    // --- Sentences ---------------------------------------------------------

    private fun pointPlayed(before: MatchSnapshot, after: MatchSnapshot, settings: SpeechSettings): List<String> {
        val state = after.state
        return buildList {
            if (settings.points) add(pointCall(after))
            if (settings.stakes) stakeCall(after)?.let { add(it) }
            if (state.isTiebreak) {
                if (settings.changeEnds && state.changeEnds) add(CHANGE_ENDS)
                // In a tiebreak the serve moves every two points, which is
                // the part players lose track of.
                if (settings.server && serverName(before) != serverName(after)) add(toServe(after))
            }
        }
    }

    private fun gameWon(after: MatchSnapshot, settings: SpeechSettings): List<String> {
        val state = after.state
        val winner = after.points.last()
        return buildList {
            if (settings.games) {
                add("Game, ${name(after, winner)}.")
                add(gamesStanding(after))
                if (state.isTiebreak) add("Tiebreak.")
            }
            if (settings.stakes) stakeCall(after)?.let { add(it) }
            if (settings.changeEnds && state.changeEnds) add(CHANGE_ENDS)
            if (settings.server) add(toServe(after))
        }
    }

    private fun setWon(old: MatchState, after: MatchSnapshot, settings: SpeechSettings): List<String> {
        val state = after.state
        val set = state.completedSets.last()
        return buildList {
            if (settings.games) {
                add("Game and set, ${name(after, set.winner)}, ${spoken(set, set.winner)}.")
                val decided = state.decidedWinner
                if (decided != null && old.decidedWinner == null) {
                    // Only reachable when every set is played regardless.
                    add("${name(after, decided)} ${verb(after, decided, "win")} the match.")
                    add("Playing set ${state.currentSetNumber}.")
                } else {
                    add(setsStanding(after))
                }
                if (state.isTiebreak) add("Match tiebreak.")
            }
            if (settings.changeEnds && state.changeEnds) add(CHANGE_ENDS)
            if (settings.server) add(toServe(after))
        }
    }

    private fun matchWon(snapshot: MatchSnapshot): List<String> {
        val state = snapshot.state
        val winner = state.winner ?: return emptyList()
        val sets = state.completedSets.joinToString(", ") { spoken(it, winner) }
        return listOf("Game, set and match, ${name(snapshot, winner)}.", "$sets.")
    }

    /** Sets, games and the current game, skipping whatever is still at zero. */
    private fun standing(snapshot: MatchSnapshot): List<String> {
        val state = snapshot.state
        return buildList {
            if (state.completedSets.isNotEmpty()) add(setsStanding(snapshot))
            if (state.gamesA + state.gamesB > 0) add(gamesStanding(snapshot))
            if (state.pointsA + state.pointsB > 0) add(pointCall(snapshot))
            if (isEmpty()) add("Love all.")
        }
    }

    /** The score of the game in progress: the server's score first, as an umpire calls it. */
    private fun pointCall(snapshot: MatchSnapshot): String {
        val state = snapshot.state
        val a = state.pointsA
        val b = state.pointsB
        if (state.isTiebreak) {
            return when {
                a == b -> "${count(a)} all."
                a > b -> "$a ${count(b)}, ${name(snapshot, Team.A)}."
                else -> "$b ${count(a)}, ${name(snapshot, Team.B)}."
            }
        }
        state.advantage?.let { return "Advantage, ${name(snapshot, it)}." }
        if (state.isDecidingPoint) {
            return if (state.config.deuceRule == DeuceRule.GOLDEN_POINT) "Golden point." else "Deciding point."
        }
        if (state.isDeuce) return "Deuce."
        val server = state.server
        val serving = label(state.pointLabel(server))
        val receiving = label(state.pointLabel(server.opponent))
        return if (serving == receiving) "$serving all." else "$serving $receiving."
    }

    /** "Break point.", "Set point, Ana and Leo." or "Match point.", when there is one. */
    private fun stakeCall(snapshot: MatchSnapshot): String? {
        val view = ScoreView.of(snapshot)
        val what = when (view.highlight) {
            // "Advantage" to the receiver already says it.
            Highlight.BREAK_POINT -> return if (snapshot.state.advantage == null) "Break point." else null
            Highlight.SET_POINT -> "Set point"
            Highlight.MATCH_POINT -> "Match point"
            else -> return null
        }
        val team = view.highlightTeam ?: return "$what."
        return "$what, ${name(snapshot, team)}."
    }

    private fun gamesStanding(snapshot: MatchSnapshot): String {
        val state = snapshot.state
        return standingOf(snapshot, state.gamesA, state.gamesB, "game")
    }

    private fun setsStanding(snapshot: MatchSnapshot): String {
        val state = snapshot.state
        return standingOf(snapshot, state.setsWonBy(Team.A), state.setsWonBy(Team.B), "set")
    }

    /** "3 games all." or "Ana and Leo lead 3 games to 2." */
    private fun standingOf(snapshot: MatchSnapshot, a: Int, b: Int, unit: String): String {
        if (a == b) return "$a ${plural(unit, a)} all."
        val leader = if (a > b) Team.A else Team.B
        val high = maxOf(a, b)
        val low = minOf(a, b)
        return "${name(snapshot, leader)} ${verb(snapshot, leader, "lead")} $high ${plural(unit, high)} to ${count(low)}."
    }

    private fun toServe(snapshot: MatchSnapshot): String = "${serverName(snapshot)} to serve."

    // --- Words -------------------------------------------------------------

    /** The player serving next, or their team when names were not given. `null` once the match is over. */
    private fun serverName(snapshot: MatchSnapshot): String? {
        val state = snapshot.state
        if (state.isComplete) return null
        val team = state.server
        val index = state.serverPlayerIndex(snapshot.serveFlip(team))
        return snapshot.roster.playerName(team, index) ?: name(snapshot, team)
    }

    /** A team as spoken: "Ana and Leo", "Ana", or "Team A". */
    private fun name(snapshot: MatchSnapshot, team: Team): String =
        snapshot.roster.players(team).joinToString(" and ").ifEmpty { snapshot.roster.teamName(team) }

    /** "leads" for one named player, "lead" for a pair or an unnamed team. */
    private fun verb(snapshot: MatchSnapshot, team: Team, base: String): String =
        if (snapshot.roster.players(team).size == 1) "${base}s" else base

    /** A finished set from [team]'s side: "6 4", "7 6", or "10 8" for a match tiebreak. */
    private fun spoken(set: SetScore, team: Team): String {
        val other = team.opponent
        val ownTiebreak = set.tiebreakPointsOf(team)
        val otherTiebreak = set.tiebreakPointsOf(other)
        if (set.isMatchTiebreak && ownTiebreak != null && otherTiebreak != null) {
            return "${count(ownTiebreak)} ${count(otherTiebreak)}"
        }
        return "${count(set.gamesOf(team))} ${count(set.gamesOf(other))}"
    }

    private fun label(pointLabel: String): String = if (pointLabel == "0") "love" else pointLabel

    private fun count(value: Int): String = if (value == 0) "love" else value.toString()

    private fun plural(unit: String, value: Int): String = if (value == 1) unit else "${unit}s"

    private const val CHANGE_ENDS = "Change ends."
}
