package com.padelsync.wear

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.padelsync.kit.BlePermissions

/**
 * Runs an action that needs Bluetooth, first asking for permission and for
 * Bluetooth to be switched on if necessary. If the user declines either, the
 * action still runs, and the controller reports the problem in plain words.
 */
@Composable
fun rememberBluetoothGate(): (action: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }

    val enableBluetooth = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        pending?.invoke()
        pending = null
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (BlePermissions.granted(context) && !BlePermissions.bluetoothOn(context)) {
            try {
                enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                return@rememberLauncherForActivityResult
            } catch (e: RuntimeException) {
                // No system screen for this on the device; fall through.
            }
        }
        pending?.invoke()
        pending = null
    }

    return { action ->
        when {
            !BlePermissions.granted(context) -> {
                pending = action
                permissions.launch(BlePermissions.toRequest())
            }
            !BlePermissions.bluetoothOn(context) -> {
                pending = action
                try {
                    enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (e: RuntimeException) {
                    pending = null
                    action()
                }
            }
            else -> action()
        }
    }
}
