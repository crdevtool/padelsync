package com.padelsync.kit

import com.netsports.core.engine.DeuceRule
import com.netsports.core.engine.FinalSetRule
import com.netsports.core.engine.MatchConfig
import com.netsports.core.engine.ServeSide
import com.netsports.core.engine.Sport
import com.netsports.core.engine.Team
import com.netsports.core.sync.JoinRejection
import com.netsports.core.sync.TapFeedback
import com.netsports.core.ui.Highlight
import com.netsports.core.ui.ScoreView

/** User-facing wording shared by the phone and watch apps. */
object Labels {

    fun team(team: Team): String = if (team == Team.A) "Team A" else "Team B"

    /** The call-out for the next point, or `null` when there is nothing to call out. */
    fun highlight(score: ScoreView, config: MatchConfig?): String? {
        val who = score.highlightTeam?.let { " · ${score.nameOf(it).uppercase()}" }.orEmpty()
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
            Highlight.MATCH_WON -> score.winner?.let { "${score.nameOf(it).uppercase()} ${wins(score, it).uppercase()}" }
        }
    }

    /**
     * A team in at most three characters, for a watch: `A+L` for Ana and
     * Leo, `ANA` for Ana alone, `A` or `B` when no names were given.
     */
    fun shortName(score: ScoreView, team: Team): String {
        val players = score.playersOf(team)
        return when (players.size) {
            0 -> if (team == Team.A) "A" else "B"
            1 -> players[0].take(3).uppercase()
            else -> players.joinToString("+") { it.take(1).uppercase() }
        }
    }

    /** [highlight] shortened to fit a watch: `MP A+L` instead of `MATCH POINT · ANA & LEO`. */
    fun highlightShort(score: ScoreView, config: MatchConfig?): String? {
        val who = score.highlightTeam?.let { " ${shortName(score, it)}" }.orEmpty()
        // About nine characters fit between the two keys on a small round
        // watch, so the big points use the abbreviations players write on
        // score sheets.
        return when (score.highlight) {
            Highlight.NONE -> null
            Highlight.TIEBREAK -> "TIEBREAK"
            Highlight.DEUCE -> "DEUCE"
            Highlight.DECIDING_POINT ->
                if (config?.deuceRule == DeuceRule.STAR_POINT) "STAR PT" else "GOLDEN PT"
            Highlight.GAME_POINT -> "GP$who"
            Highlight.BREAK_POINT -> "BP$who"
            Highlight.SET_POINT -> "SP$who"
            Highlight.MATCH_POINT -> "MP$who"
            Highlight.MATCH_WON -> score.winner?.let { "${shortName(score, it)} ${wins(score, it).uppercase()}" }
        }
    }

    /** `win` for a pair or an unnamed team, `wins` for one named player. */
    fun wins(score: ScoreView, team: Team): String = if (score.playersOf(team).size == 1) "wins" else "win"

    /** The line over the trophy: `Ana & Leo win!` */
    fun winnerHeadline(score: ScoreView, team: Team): String = "${score.nameOf(team)} ${wins(score, team)}!"

    /** Which side the server stands on, from the server's own point of view. */
    fun serveSide(side: ServeSide): String = if (side == ServeSide.RIGHT) "right side" else "left side"

    /** A duration as `48 min` or `1 h 12 min`. */
    fun duration(millis: Long): String {
        val minutes = (millis / 60_000).toInt()
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    /** A running clock as `7:05` or `1:07:05`. */
    fun clock(millis: Long): String {
        val seconds = (millis.coerceAtLeast(0) / 1000).toInt()
        val minutesPart = (seconds / 60) % 60
        val secondsPart = (seconds % 60).toString().padStart(2, '0')
        val hours = seconds / 3600
        return if (hours > 0) "$hours:${minutesPart.toString().padStart(2, '0')}:$secondsPart" else "$minutesPart:$secondsPart"
    }

    fun tapFeedback(feedback: TapFeedback?): String? = when (feedback) {
        TapFeedback.SUPERSEDED -> "Already scored on another device"
        TapFeedback.MATCH_COMPLETE -> "The match is over"
        TapFeedback.NOT_ALLOWED -> "View only: the host scores this match"
        TapFeedback.ACCEPTED, TapFeedback.NOTHING_TO_UNDO, null -> null
    }

    /**
     * The result as plain text, for sharing in a chat:
     * `Ana & Leo beat Mia & Sam 6-4 3-6 7-5 (Padel, 1 h 12 min)`.
     */
    fun shareText(score: ScoreView, config: MatchConfig?, durationMillis: Long?): String {
        val winner = score.winner ?: score.decidedWinner
        val headline = if (winner == null) {
            "${score.nameA} vs ${score.nameB}"
        } else {
            "${score.nameOf(winner)} beat ${score.nameOf(winner.opponent)}"
        }
        val details = listOfNotNull(
            config?.let { sport(it.sport) },
            durationMillis?.takeIf { it >= 60_000 }?.let { duration(it) },
        ).joinToString(", ")
        return buildString {
            append(headline)
            if (score.setSummary.isNotEmpty()) append(' ').append(score.setSummary)
            if (details.isNotEmpty()) append(" (").append(details).append(')')
            append("\nScored with PadelSync")
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
        "${sport(config.sport)} · ${sets(config)} · ${deuceRule(config.deuceRule)}"

    /** `1 set`, `Best of 3`, or `3 sets` when every set is played. */
    fun sets(config: MatchConfig): String = when {
        config.bestOf == 1 -> "1 set"
        config.playAllSets -> "${config.bestOf} sets"
        else -> "Best of ${config.bestOf}"
    }

    fun rejection(reason: JoinRejection?): String = when (reason) {
        JoinRejection.BAD_CODE -> "That code is not right. Check the host's screen and try again."
        JoinRejection.SESSION_FULL -> "This court already has the maximum number of devices."
        JoinRejection.UNSUPPORTED_VERSION -> "This court uses a different version of the app. Update both devices."
        null -> "The host did not let this device join."
    }

    // Shown on "Join a court" on Android 11 and older while the device's
    // Location switch is off; see BlePermissions.needsLocationSwitch.
    const val LOCATION_OFF_TITLE = "Location is switched off"
    const val LOCATION_OFF_BODY =
        "On this version of Android, Bluetooth can only find nearby courts while Location is switched on. " +
            "PadelSync does not use or store your location."
    const val LOCATION_OFF_BUTTON = "Open location settings"

    /** Shown instead of the button's screen on a device that has no location settings screen to open. */
    const val LOCATION_OFF_NO_SETTINGS = "Open the Settings app and switch on Location, then come back here."
}
