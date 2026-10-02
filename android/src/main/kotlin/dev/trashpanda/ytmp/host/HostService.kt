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
 * Keeps the phone hosting while it has rooms: a foreground notification so Android doesn't
 * stop the app, plus Wi-Fi and wake locks so friends' devices keep getting answers with the
 * screen off. Started and stopped by [YtmpApp] as rooms come and go.
 */
class HostService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as YtmpApp).host.closeAllRooms()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(intent?.getStringExtra(EXTRA_TEXT) ?: "Hosting"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        if (wifiLock == null) {
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "ytmp:host").apply { acquire() }
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ytmp:host").apply { acquire() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wifiLock?.release()
        wakeLock?.release()
        super.onDestroy()
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

        fun update(context: Context, text: String) {
            context.startForegroundService(Intent(context, HostService::class.java).putExtra(EXTRA_TEXT, text))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HostService::class.java))
        }
    }
}
