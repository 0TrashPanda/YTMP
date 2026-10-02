package dev.trashpanda.ytmp

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
    val volume: Float,
)

@Serializable
data class TargetItem(
    val itemId: String,
    val songId: String,
    val title: String,
    val artist: String,
    val artUrl: String?,
    val durationMs: Long,
)

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

    fun command(json: String) {
        commandSink?.invoke(json)
    }

    fun status(status: String) {
        statusSink?.invoke(status)
    }
}
