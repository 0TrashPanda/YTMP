package dev.trashpanda.ytmp

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads the notification a Pixel posts when Now Playing recognizes a song (Android System
 * Intelligence, `com.google.android.as`) and keeps it in [NowPlayingHistory]. It sees no other
 * app's notifications: everything else is dropped before anything is read. Android only starts
 * it after you allow notification access for YTMP in its settings.
 */
class NowPlayingListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != SOURCE) return
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val song = NowPlayingParser.parse(title, sbn.postTime)
        // The channel and whether it parsed, to see what Android System Intelligence really posts on a phone (its other notifications aren't songs).
        Log.d("NowPlaying", "Notification from $SOURCE, channel ${sbn.notification.channelId}, song: ${song != null}")
        if (song != null) (application as YtmpApp).nowPlaying.add(song)
    }

    private companion object {
        const val SOURCE = "com.google.android.as"
    }
}
