package com.netsports.core.ui

import com.netsports.core.engine.DeuceRule
import com.netsports.core.engine.FinalSetRule
import com.netsports.core.engine.MatchConfig

/**
 * One line of plain words for each choice on the match setup screen, shown
 * under the choice that is selected. Kept here so that every app explains a
 * format in the same words.
 *
 * The functions take plain values rather than a [MatchConfig]: a setup form
 * holds its choices one by one until the match is started.
 */
object FormatHelp {
    /** Under "Sets". */
    fun sets(bestOf: Int): String = when (bestOf) {
        1 -> "One set decides the match."
        else -> "Best of $bestOf: the first team to win ${bestOf / 2 + 1} sets wins the match."
    }

    /** Under "Set length". */
    fun setLength(games: Int): String = when {
        games < STANDARD_SET -> "Short set: first to $games games, two games clear."
        games == STANDARD_SET -> "Standard set: first to $games games, two games clear."
        else -> "Pro set: first to $games games, two games clear."
    }

    /** Under "At deuce". */
    fun deuce(rule: DeuceRule): String = when (rule) {
        DeuceRule.ADVANTAGE -> "Advantage: from 40-40 a team must win two points in a row."
        DeuceRule.GOLDEN_POINT -> "Golden point: at 40-40 the next point wins the game."
        DeuceRule.STAR_POINT -> "Star point: advantage twice, then the third deuce is one deciding point."
    }

    /** The heading of the choice between a tiebreak and playing on: `At 6-6`. */
    fun gamesAllTitle(games: Int): String = "At $games-$games"

    /** Under [gamesAllTitle]. */
    fun gamesAll(games: Int, tiebreak: Boolean, tiebreakPoints: Int): String = if (tiebreak) {
        "Tiebreak: at $games-$games the set is decided by a tiebreak to $tiebreakPoints points."
    } else {
        "Advantage set: no tiebreak. At $games-$games you keep playing games."
    }

    /** The heading of the choice of where an advantage set stops. */
    fun capTitle(): String = "Advantage set limit"

    /** A choice under [capTitle]: `First to 8`, or `No limit` for 0. */
    fun capLabel(cap: Int): String = if (cap == 0) "No limit" else "First to $cap"

    /** Under [capTitle]. [cap] is 0 for no limit. */
    fun cap(games: Int, cap: Int): String = if (cap == 0) {
        "The set goes on until a team is two games ahead: ${games + 2}-$games, ${games + 3}-${games + 1} and so on."
    } else {
        "The first team to reach $cap games wins the set. At ${cap - 1}-${cap - 1} one last game decides it."
    }

    /** Under "Final set". */
    fun finalSet(rule: FinalSetRule, matchTiebreakPoints: Int): String = when (rule) {
        FinalSetRule.SAME_AS_OTHER_SETS -> "Full set: the last set is played like the others."
        FinalSetRule.ADVANTAGE_SET -> "No tiebreak: the last set must be won by two clear games."
        FinalSetRule.MATCH_TIEBREAK ->
            "Match tiebreak: instead of a last set, a tiebreak to $matchTiebreakPoints points."
    }

    /**
     * What sets [config] apart from the usual format, to add to a one-line
     * description of it: ` · Sets to 4 · Advantage sets to 8`. Empty for a
     * match of six-game sets with tiebreaks.
     */
    fun extras(config: MatchConfig): String {
        val parts = mutableListOf<String>()
        if (config.gamesPerSet != STANDARD_SET) parts += "Sets to ${config.gamesPerSet}"
        if (!config.setTiebreak) {
            parts += if (config.setGamesCap == 0) "Advantage sets" else "Advantage sets to ${config.setGamesCap}"
        }
        return parts.joinToString("") { " · $it" }
    }

    private const val STANDARD_SET = 6
}
