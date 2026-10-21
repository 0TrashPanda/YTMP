package dev.trashpanda.ytmp

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads the notification a Pixel posts when Now Playing recognizes a song and keeps it in
 * [NowPlayingHistory]. Now Playing started inside Android System Intelligence and has its own
 * app now, so both are read. It sees no other app's notifications: everything else is dropped
 * before anything is read. Android only starts it after you allow notification access for YTMP
 * in its settings.
 */
class NowPlayingListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in SOURCES) return
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val song = NowPlayingParser.parse(title, sbn.postTime)
        // The app, the channel and whether it parsed, to see what Now Playing really posts on a phone (its other notifications, like the history syncing, aren't songs).
        Log.d("NowPlaying", "Notification from ${sbn.packageName}, channel ${sbn.notification.channelId}, song: ${song != null}")
        if (song != null) (application as YtmpApp).nowPlaying.add(song)
    }

    private companion object {
        val SOURCES = setOf(
            "com.google.android.as", // Android System Intelligence, where Now Playing used to live
            "com.google.android.apps.pixel.nowplaying" // the Now Playing app
        )
    }
}
