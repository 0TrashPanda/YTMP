package dev.trashpanda.ytmp.host

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import dev.trashpanda.ytmp.MainActivity
import dev.trashpanda.ytmp.R
import dev.trashpanda.ytmp.YtmpApp

/**
 * Keeps the phone hosting while it has public rooms or plays music: a foreground notification
 * so Android doesn't stop the app. Started and stopped by [YtmpApp]. Idle solo rooms don't need it.
 *
 * The locks cost battery, so they're only held while a room plays: a wake lock (songs must
 * move on with the screen off, also when only speakers or friends listen) and a Wi-Fi lock
 * (the next song must load: some phones drop Wi-Fi within a second in deep sleep). An idle
 * public room needs neither: a request from a friend wakes the phone by itself.
 */
class HostService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as YtmpApp).host.stopHosting()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(intent?.getStringExtra(EXTRA_TEXT) ?: "Hosting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        holdWakeLock(intent?.getBooleanExtra(EXTRA_WAKE, false) == true)
        holdWifiLock(intent?.getBooleanExtra(EXTRA_WIFI, false) == true)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        holdWakeLock(false)
        holdWifiLock(false)
        super.onDestroy()
    }

    private fun holdWakeLock(hold: Boolean) {
        if (hold && wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytmp:host").apply { acquire() }
        } else if (!hold) {
            wakeLock?.release()
            wakeLock = null
        }
    }

    private fun holdWifiLock(hold: Boolean) {
        if (hold && wifiLock == null) {
            // High-performance, like ExoPlayer's: the low-latency lock only works with the screen on.
            @Suppress("DEPRECATION")
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ytmp:host").apply { acquire() }
        } else if (!hold) {
            wifiLock?.release()
            wifiLock = null
        }
    }

    private fun notification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Hosting", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, HostService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("YTMP")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop hosting", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "hosting"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_STOP = "dev.trashpanda.ytmp.STOP_HOSTING"
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_WAKE = "wake"
        private const val EXTRA_WIFI = "wifi"

        /** Shows [text] in the notification; [wakeLock] and [wifiLock] say which locks to hold. */
        fun update(context: Context, text: String, wakeLock: Boolean, wifiLock: Boolean) {
            context.startForegroundService(
                Intent(context, HostService::class.java).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_WAKE, wakeLock).putExtra(EXTRA_WIFI, wifiLock),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HostService::class.java))
        }
    }
}
