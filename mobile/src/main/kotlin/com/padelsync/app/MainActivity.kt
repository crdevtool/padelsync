package com.padelsync.app

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
        setContent {
            PadelSyncTheme {
                AppRoot(controller)
            }
        }
    }
}
