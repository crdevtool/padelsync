package com.padelsync.wear

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
        setContent {
            MaterialTheme {
                WearRoot(controller)
            }
        }
    }
}
