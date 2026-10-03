package com.netsports.core.match

import com.netsports.core.engine.Team

/**
 * The players' names, as typed in at the start of a match. Names are labels
 * only: scoring never depends on them.
 *
 * Each team has up to two players. The order matters in doubles: the
 * first-listed player serves the team's first service game of a set.
 *
 * @throws IllegalArgumentException if a team has more than two players, or a
 * name is blank or longer than [MAX_NAME_BYTES] bytes of UTF-8. Use [of] to
 * clean up raw text from a form instead of validating it yourself.
 */
data class Roster(
    val teamA: List<String> = emptyList(),
    val teamB: List<String> = emptyList(),
) {
    init {
        for (players in listOf(teamA, teamB)) {
            require(players.size <= MAX_PLAYERS) { "a team has at most $MAX_PLAYERS players" }
            for (name in players) {
                require(name.isNotBlank()) { "a player name cannot be blank" }
                require(name.encodeToByteArray().size <= MAX_NAME_BYTES) { "player name too long: $name" }
            }
        }
    }

    fun players(team: Team): List<String> = if (team == Team.A) teamA else teamB

    /** `Ana & Leo`, `Ana`, or `Team A` when no names were given. */
    fun teamName(team: Team): String =
        players(team).joinToString(" & ").ifEmpty { if (team == Team.A) "Team A" else "Team B" }

    /** The name of a team's player at [index], or `null` if it was not given. */
    fun playerName(team: Team, index: Int): String? = players(team).getOrNull(index)

    companion object {
        const val MAX_PLAYERS = 2
        const val MAX_NAME_BYTES = 20

        val EMPTY = Roster()

        /**
         * Builds a roster from raw form fields: trims each name, drops empty
         * ones and shortens any that are too long.
         */
        fun of(teamA: List<String>, teamB: List<String>): Roster {
            fun clean(names: List<String>) = names
                .map { truncate(it.trim()) }
                .filter { it.isNotBlank() }
                .take(MAX_PLAYERS)
            return Roster(clean(teamA), clean(teamB))
        }

        /** Shortens [name] to [MAX_NAME_BYTES] bytes without splitting a character. */
        private fun truncate(name: String): String {
            var end = name.length
            while (end > 0) {
                if (end < name.length && name[end - 1].isHighSurrogate()) end--
                val candidate = name.substring(0, end)
                if (candidate.encodeToByteArray().size <= MAX_NAME_BYTES) return candidate.trim()
                end--
            }
            return ""
        }
    }
}
