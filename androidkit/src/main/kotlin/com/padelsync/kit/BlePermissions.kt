package com.padelsync.kit

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/** The runtime permissions a court session needs, which differ by Android version. */
object BlePermissions {

    /** Permissions without which hosting or joining cannot work. */
    fun required(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /** [required] plus the notification permission, which is nice to have but optional. */
    fun toRequest(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        required() + Manifest.permission.POST_NOTIFICATIONS
    } else {
        required()
    }

    fun granted(context: Context): Boolean = required().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun bluetoothOn(context: Context): Boolean =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    /**
     * Android 11 and older (which includes Wear OS 3) hand an app no Bluetooth
     * scan results while the device's Location switch is off, whatever
     * permissions it holds, and say nothing about it. From Android 12 the
     * scan permission is declared "never for location" and the switch does
     * not matter.
     */
    val scanNeedsLocationSwitch: Boolean
        get() = Build.VERSION.SDK_INT <= Build.VERSION_CODES.R

    /** True when a scan would find nothing because the device's Location switch is off. */
    fun needsLocationSwitch(context: Context): Boolean {
        if (!scanNeedsLocationSwitch) return false
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return !LocationManagerCompat.isLocationEnabled(manager)
    }

    /**
     * Opens the system screen with the Location switch. Returns false if this
     * device has no such screen, so the caller can say where to look instead.
     */
    fun openLocationSettings(context: Context): Boolean = try {
        context.startActivity(
            Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        // The screen exists but is not open to other apps on this device.
        false
    }
}
