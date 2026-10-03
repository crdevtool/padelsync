package com.padelsync.kit

import android.content.Context
import android.util.Base64
import com.netsports.core.match.MatchSnapshot
import com.netsports.core.sync.HostSession

/**
 * Keeps the match this device is hosting on disk, so it survives the app
 * being closed or the phone restarting mid-match.
 */
internal class MatchStore(context: Context) {
    private val prefs = context.getSharedPreferences("padelsync_match", Context.MODE_PRIVATE)

    fun save(session: HostSession) {
        val encoded = Base64.encodeToString(session.savedState(), Base64.NO_WRAP)
        prefs.edit().putString(KEY, encoded).apply()
    }

    fun load(): MatchSnapshot? {
        val encoded = prefs.getString(KEY, null) ?: return null
        val bytes = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return HostSession.restoreSnapshot(bytes)
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    private companion object {
        const val KEY = "hosted_match"
    }
}
