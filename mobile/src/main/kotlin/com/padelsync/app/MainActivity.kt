package com.padelsync.app

import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.netsports.core.sync.DeviceKind
import com.padelsync.kit.CourtController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so the system bars need light icons
        // whatever the phone's own theme is.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // The score is called on the media volume, so the volume keys should move that.
        volumeControlStream = AudioManager.STREAM_MUSIC
        val controller = CourtController.get(this, DeviceKind.PHONE)
        applyTestOptions(controller)
        setContent {
            PadelSyncTheme {
                AppRoot(controller)
            }
        }
    }

    /**
     * Options the emulator tests pass on the launch command. They are
     * ignored in a build that is not debuggable, so a released app cannot be
     * put into a test mode by another app.
     */
    private fun applyTestOptions(controller: CourtController) {
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && intent?.getBooleanExtra(EXTRA_SCAN_RECONNECT_ONLY, false) == true) {
            controller.scanReconnectOnly = true
        }
    }

    private companion object {
        const val EXTRA_SCAN_RECONNECT_ONLY = "scan_reconnect_only"
    }
}
