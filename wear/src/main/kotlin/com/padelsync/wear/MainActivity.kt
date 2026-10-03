package com.padelsync.wear

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material.MaterialTheme
import com.netsports.core.sync.DeviceKind
import com.padelsync.kit.CourtController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = CourtController.get(this, DeviceKind.WATCH)
        // An emulator-test switch; ignored in a build that is not debuggable.
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && intent?.getBooleanExtra("scan_reconnect_only", false) == true) {
            controller.scanReconnectOnly = true
        }
        setContent {
            MaterialTheme {
                WearRoot(controller)
            }
        }
    }
}
