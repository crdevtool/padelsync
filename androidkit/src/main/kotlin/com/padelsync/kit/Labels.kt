package com.padelsync.kit

import com.netsports.core.engine.DeuceRule
import com.netsports.core.engine.FinalSetRule
import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.Sport
import com.netsports.core.engine.Team
import com.netsports.core.sync.JoinRejection
import com.netsports.core.ui.Highlight
import com.netsports.core.ui.ScoreView

/** User-facing wording shared by the phone and watch apps. */
object Labels {

    fun team(team: Team): String = if (team == Team.A) "Team A" else "Team B"

    /** The call-out for the next point, or `null` when there is nothing to call out. */
    fun highlight(score: ScoreView, config: MatchConfig?): String? {
        val who = score.highlightTeam?.let { " · ${team(it).uppercase()}" }.orEmpty()
        return when (score.highlight) {
            Highlight.NONE -> null
            Highlight.TIEBREAK -> "TIEBREAK"
            Highlight.DEUCE -> "DEUCE"
            Highlight.DECIDING_POINT ->
                if (config?.deuceRule == DeuceRule.STAR_POINT) "STAR POINT" else "GOLDEN POINT"
            Highlight.GAME_POINT -> "GAME POINT$who"
            Highlight.BREAK_POINT -> "BREAK POINT$who"
            Highlight.SET_POINT -> "SET POINT$who"
            Highlight.MATCH_POINT -> "MATCH POINT$who"
            Highlight.MATCH_WON -> score.winner?.let { "${team(it).uppercase()} WINS" }
        }
    }

    /** [highlight] shortened to fit a watch: `MATCH POINT A` instead of `MATCH POINT · TEAM A`. */
    fun highlightShort(score: ScoreView, config: MatchConfig?): String? {
        val who = score.highlightTeam?.let { if (it == Team.A) " A" else " B" }.orEmpty()
        return when (score.highlight) {
            Highlight.GAME_POINT -> "GAME POINT$who"
            Highlight.BREAK_POINT -> "BREAK POINT$who"
            Highlight.SET_POINT -> "SET POINT$who"
            Highlight.MATCH_POINT -> "MATCH POINT$who"
            else -> highlight(score, config)
        }
    }

    fun sport(sport: Sport): String = if (sport == Sport.PADEL) "Padel" else "Tennis"

    fun deuceRule(rule: DeuceRule): String = when (rule) {
        DeuceRule.ADVANTAGE -> "Advantage"
        DeuceRule.GOLDEN_POINT -> "Golden point"
        DeuceRule.STAR_POINT -> "Star point"
    }

    fun finalSet(rule: FinalSetRule): String = when (rule) {
        FinalSetRule.SAME_AS_OTHER_SETS -> "Full set"
        FinalSetRule.ADVANTAGE_SET -> "No tiebreak"
        FinalSetRule.MATCH_TIEBREAK -> "Match tiebreak"
    }

    /** One line describing a format, for example `Padel · Best of 3 · Golden point`. */
    fun format(config: MatchConfig): String =
        "${sport(config.sport)} · Best of ${config.bestOf} · ${deuceRule(config.deuceRule)}"

    fun rejection(reason: JoinRejection?): String = when (reason) {
        JoinRejection.BAD_CODE -> "That code is not right. Check the host's screen and try again."
        JoinRejection.SESSION_FULL -> "This court already has the maximum number of devices."
        JoinRejection.UNSUPPORTED_VERSION -> "This court uses a different version of the app. Update both devices."
        null -> "The host did not let this device join."
    }
}
