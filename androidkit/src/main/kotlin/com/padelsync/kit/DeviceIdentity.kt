package com.padelsync.kit

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.netsports.core.sync.RandomIdSource

/** This device's stable id and display name within court sessions. */
class DeviceIdentity(private val context: Context) {
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

    /**
     * Name shown to other players. Defaults to the name the owner gave the
     * device in system settings, falling back to the model.
     */
    var deviceName: String
        get() = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }
            ?: systemDeviceName()
            ?: Build.MODEL.orEmpty().ifBlank { "Player" }
        set(value) = prefs.edit().putString(KEY_NAME, value.trim()).apply()

    private fun systemDeviceName(): String? = try {
        Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
    } catch (e: RuntimeException) {
        null
    }

    private companion object {
        const val KEY_ID = "device_id"
        const val KEY_NAME = "device_name"
    }
}
