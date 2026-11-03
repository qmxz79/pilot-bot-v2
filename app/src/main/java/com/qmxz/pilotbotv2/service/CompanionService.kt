package com.qmxz.pilotbotv2.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.qmxz.pilotbotv2.MainActivity
import com.qmxz.pilotbotv2.R

/**
 * Foreground keep-alive for the driving companion (decision 2026-11-03).
 *
 * Why it exists: once navigation jumps to the Amap app, our Activity
 * sits in the background. Without a foreground service, the system may
 * kill the app's process at any time and the companion's voice simply
 * stops. While this service runs, Android treats the app as foreground,
 * so the voice pipeline keeps running behind Amap.
 *
 * What it does NOT do, stated plainly: it owns no conversation state.
 * The pipeline still lives in the Activity's ViewModel, so this service
 * buys process priority, not immortality. If the Activity itself is
 * destroyed (the user swipes the app away), voice stops until the app
 * is reopened; a service-owned conversation engine is future work.
 *
 * Foreground types: microphone (background mic capture needs it on
 * Android 12+) and location (location updates while Amap is in front).
 * The service is started only from a foreground user tap and stopped
 * only by user action (its notification button or the in-app button),
 * never from a background callback, so it cannot hit the Android 12+
 * background-start restriction.
 */
class CompanionService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_STOP -> {
                // Tell the ViewModel so it clears its keep-alive flag;
                // otherwise auto-scene speech could outlive the service.
                sendBroadcast(Intent(ACTION_COMPANION_STOPPED))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): android.app.Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.companion_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CompanionService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.companion_notification_title))
            .setContentText(getString(R.string.companion_notification_text))
            .setContentIntent(openIntent)
            .addAction(
                0,
                getString(R.string.companion_notification_stop),
                stopIntent
            )
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.qmxz.pilotbotv2.action.START_COMPANION"
        const val ACTION_STOP = "com.qmxz.pilotbotv2.action.STOP_COMPANION"
        const val ACTION_COMPANION_STOPPED =
            "com.qmxz.pilotbotv2.action.COMPANION_STOPPED"
        private const val CHANNEL_ID = "companion_keep_alive"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, CompanionService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, CompanionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
