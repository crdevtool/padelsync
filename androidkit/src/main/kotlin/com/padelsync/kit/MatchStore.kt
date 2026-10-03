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

    /**
     * Remembers the join code of the hosted court. A match resumed after the
     * app was closed opens under the same code, so guests that are still
     * looking for the court are let back in without typing it again.
     */
    fun saveCode(code: Int?) {
        if (code == null) prefs.edit().remove(KEY_CODE).apply() else prefs.edit().putInt(KEY_CODE, code).apply()
    }

    fun loadCode(): Int? = if (prefs.contains(KEY_CODE)) prefs.getInt(KEY_CODE, 0) else null

    fun clear() = prefs.edit().remove(KEY).remove(KEY_CODE).apply()

    private companion object {
        const val KEY = "hosted_match"
        const val KEY_CODE = "hosted_code"
    }
}
