package com.padelsync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.netsports.core.sync.DeviceKind
import com.padelsync.kit.CourtController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val controller = CourtController.get(this, DeviceKind.PHONE)
        setContent {
            PadelSyncTheme {
                AppRoot(controller)
            }
        }
    }
}
