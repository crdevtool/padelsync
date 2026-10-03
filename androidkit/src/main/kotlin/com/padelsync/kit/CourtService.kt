package com.padelsync.kit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that exists only to keep the app alive while a court
 * session is running, so the Bluetooth links survive the screen turning off
 * or the phone going into a bag. All logic lives in [CourtController].
 */
class CourtService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Court session", NotificationManager.IMPORTANCE_LOW),
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_court_notification)
            .setContentTitle("PadelSync")
            .setContentText("Court session in progress")
            .setOngoing(true)
            .setContentIntent(launchIntent())
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: RuntimeException) {
            // The system can refuse (for example if a permission was revoked
            // a moment ago). The session still works while the app is open.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun launchIntent(): android.app.PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val CHANNEL = "court_session"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, CourtService::class.java))
            } catch (e: RuntimeException) {
                // Not allowed right now (app in the background). Harmless.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CourtService::class.java))
        }
    }
}
