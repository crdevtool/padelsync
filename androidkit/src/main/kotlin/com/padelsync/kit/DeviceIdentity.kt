package com.padelsync.kit

import android.content.Context
import android.os.Build
import com.netsports.core.sync.RandomIdSource

/** This device's stable id and display name within court sessions. */
class DeviceIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("padelsync_identity", Context.MODE_PRIVATE)

    /** Random id created on first launch. Not tied to any hardware identifier. */
    val deviceId: Long
        get() {
            val existing = prefs.getLong(KEY_ID, 0L)
            if (existing != 0L) return existing
            val created = RandomIdSource().next()
            prefs.edit().putLong(KEY_ID, created).apply()
            return created
        }

    /** Name shown to other players. Defaults to the device model. */
    var deviceName: String
        get() = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: Build.MODEL.orEmpty().ifBlank { "Player" }
        set(value) = prefs.edit().putString(KEY_NAME, value.trim()).apply()

    private companion object {
        const val KEY_ID = "device_id"
        const val KEY_NAME = "device_name"
    }
}
