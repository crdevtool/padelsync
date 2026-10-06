package com.padelsync.kit

import android.content.Context
import android.util.Base64
import com.netsports.core.engine.MatchConfig
import com.netsports.core.match.MatchSnapshot
import com.netsports.core.match.Roster
import com.netsports.core.sync.HostSession
import com.netsports.core.sync.Message
import com.netsports.core.sync.WireCodec
import com.netsports.core.ui.SpeechSettings

/** What a player chose on the setup screen. */
data class MatchSetup(
    val config: MatchConfig,
    val roster: Roster = Roster.EMPTY,
    /** Whether devices that join may change the score. Only matters when hosting. */
    val guestsCanScore: Boolean = true,
)

/**
 * This device's own choices, kept between launches: how it announces the
 * score and the last match that was set up, so the same four friends do not
 * have to type their names every week.
 */
internal class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("padelsync_settings", Context.MODE_PRIVATE)

    /**
     * Voice settings for this device. The voice is off until the player
     * switches it on. The choice is kept apart for hosting and for joining,
     * so a phone that speaks when it hosts stays quiet as a guest and four
     * phones on one court do not all talk at once.
     */
    fun speech(hosting: Boolean): SpeechSettings = SpeechSettings(
        enabled = if (hosting) {
            prefs.getBoolean(KEY_VOICE_HOST, false)
        } else {
            prefs.getBoolean(KEY_VOICE_GUEST, false)
        },
        points = prefs.getBoolean(KEY_VOICE_POINTS, true),
        games = prefs.getBoolean(KEY_VOICE_GAMES, true),
        stakes = prefs.getBoolean(KEY_VOICE_STAKES, true),
        server = prefs.getBoolean(KEY_VOICE_SERVER, true),
        changeEnds = prefs.getBoolean(KEY_VOICE_ENDS, true),
        reminderMinutes = prefs.getInt(KEY_VOICE_REMINDER, 0).coerceIn(0, SpeechSettings.MAX_REMINDER_MINUTES),
    )

    fun saveSpeech(settings: SpeechSettings, hosting: Boolean) {
        prefs.edit()
            .putBoolean(if (hosting) KEY_VOICE_HOST else KEY_VOICE_GUEST, settings.enabled)
            .putBoolean(KEY_VOICE_POINTS, settings.points)
            .putBoolean(KEY_VOICE_GAMES, settings.games)
            .putBoolean(KEY_VOICE_STAKES, settings.stakes)
            .putBoolean(KEY_VOICE_SERVER, settings.server)
            .putBoolean(KEY_VOICE_ENDS, settings.changeEnds)
            .putInt(KEY_VOICE_REMINDER, settings.reminderMinutes)
            .apply()
    }

    /** The last match set up on this device, or `null` on first use. */
    fun lastSetup(): MatchSetup? {
        val snapshot = decoded(prefs.getString(KEY_SETUP, null)) ?: return null
        return MatchSetup(snapshot.config, snapshot.roster, prefs.getBoolean(KEY_GUESTS_SCORE, true))
    }

    fun saveSetup(setup: MatchSetup) {
        prefs.edit()
            .putString(KEY_SETUP, encoded(setup.config, setup.roster))
            .putBoolean(KEY_GUESTS_SCORE, setup.guestsCanScore)
            .apply()
    }

    /**
     * The format the player keeps as their own, or `null` if none was kept.
     * Unlike [lastSetup] it is not replaced by a match played once in
     * another format, or by a court joined as a guest.
     */
    fun myFormat(): MatchConfig? = decoded(prefs.getString(KEY_MY_FORMAT, null))?.config

    fun saveMyFormat(config: MatchConfig) {
        prefs.edit().putString(KEY_MY_FORMAT, encoded(config, Roster.EMPTY)).apply()
    }

    // Stored as an empty match, which reuses the versioned wire format.
    private fun encoded(config: MatchConfig, roster: Roster): String {
        val snapshot = MatchSnapshot(1, 1, 0, config, emptyList(), roster = roster)
        return Base64.encodeToString(WireCodec.encode(Message.State(snapshot, 1)), Base64.NO_WRAP)
    }

    private fun decoded(encoded: String?): MatchSnapshot? {
        val bytes = try {
            Base64.decode(encoded ?: return null, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return HostSession.restoreSnapshot(bytes)
    }

    private companion object {
        const val KEY_VOICE_HOST = "voice_when_hosting"
        const val KEY_VOICE_GUEST = "voice_when_guest"
        const val KEY_VOICE_POINTS = "voice_points"
        const val KEY_VOICE_GAMES = "voice_games"
        const val KEY_VOICE_STAKES = "voice_stakes"
        const val KEY_VOICE_SERVER = "voice_server"
        const val KEY_VOICE_ENDS = "voice_change_ends"
        const val KEY_VOICE_REMINDER = "voice_reminder_minutes"
        const val KEY_SETUP = "last_setup"
        const val KEY_MY_FORMAT = "my_format"
        const val KEY_GUESTS_SCORE = "guests_can_score"
    }
}
