package dev.trashpanda.ytmp

import android.util.Log
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable

/** What the web app wants the native player to do. Sent over the JS bridge on every change. */
@Serializable
data class PlaybackTarget(
    /** "Play here" is on. */
    val enabled: Boolean,
    val item: TargetItem?,
    /** Direct stream URL from the host, or null while it is being resolved. */
    val streamUrl: String?,
    /** The same audio streamed through the host, for when the direct URL fails. */
    val proxyUrl: String?,
    val playing: Boolean,
    val positionMs: Long,
    val hostTimeMs: Long,
    /** Host clock minus device clock, in ms. */
    val clockOffset: Double,
    /** This device's sync adjustment; positive plays earlier (for speaker/Bluetooth delay). */
    val syncOffsetMs: Long = 0,
    /** Nobody else plays along: only fix big drift (see [SyncCorrection.ALONE_TOLERANCE_MS]). */
    val alone: Boolean = false,
    val volume: Float,
    /** The room this is from, so the app can follow it itself (see [RoomFollower]). */
    val room: RoomLink? = null,
)

/** Enough to attach to the room as the page's participant (`attach` in the protocol). */
@Serializable
data class RoomLink(
    /** e.g. `http://127.0.0.1:8765` */
    val hostUrl: String,
    val code: String,
    val guestToken: String,
)

@Serializable
data class TargetItem(
    val itemId: String,
    val songId: String,
    val title: String,
    val artist: String,
    val artUrl: String?,
    val durationMs: Long,
) {
    companion object {
        /** The same item as the web app makes it (player.svelte.ts). */
        fun of(itemId: String, song: Song) = TargetItem(
            itemId = itemId,
            songId = song.id,
            title = song.title,
            artist = song.artists.joinToString(", ") { it.name },
            // The smallest thumbnail that is at least 544 px, or the largest one.
            artUrl = song.thumbnails.sortedBy { it.width }.let { t -> (t.firstOrNull { it.width >= 544 } ?: t.lastOrNull())?.url },
            durationMs = song.durationMs,
        )
    }
}

/**
 * Connects the WebView (which owns the room connection) and [PlaybackService] (which plays
 * the audio). Both live in the same process.
 */
object PlaybackHub {
    val target = MutableStateFlow<PlaybackTarget?>(null)

    /** Sends a room command (JSON, e.g. `{"kind":"Skip"}`) to the web app. Set by [MainActivity]. */
    @Volatile
    var commandSink: ((String) -> Unit)? = null

    /** Tells the web app about local player changes, e.g. "stopped" when another app takes the audio. */
    @Volatile
    var statusSink: ((String) -> Unit)? = null

    /**
     * Sends a room command over the player's own room connection; false when it isn't
     * connected. Set by [PlaybackService].
     */
    @Volatile
    var followerCommands: ((String) -> Boolean)? = null

    fun command(json: String) {
        // The page may be frozen in the background, so prefer the app's own connection.
        if (followerCommands?.invoke(json) == true) {
            Log.d("YtmpPlayback", "Media control $json (own room connection)")
            return
        }
        val sink = commandSink
        if (sink == null) Log.w("YtmpPlayback", "Media control $json, but the app's page isn't there to run it")
        else Log.d("YtmpPlayback", "Media control $json")
        sink?.invoke(json)
    }

    fun status(status: String) {
        statusSink?.invoke(status)
    }
}
