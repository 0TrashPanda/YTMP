package dev.trashpanda.ytmp

import kotlinx.serialization.Serializable

/** A song the Pixel's Now Playing recognized (see [NowPlayingListener]). [at] is when, in epoch milliseconds. */
@Serializable
data class DetectedSong(val title: String, val artist: String, val at: Long)

/** Reads what Now Playing's notification says: "Title by Artist". */
object NowPlayingParser {
    private const val SEPARATOR = " by "

    /** The song in a notification title, or null when it isn't one (the artist may contain " by ", so the last one splits). */
    fun parse(text: String?, at: Long): DetectedSong? {
        val whole = text?.trim() ?: return null
        val split = whole.lastIndexOf(SEPARATOR)
        if (split <= 0) return null
        val title = whole.substring(0, split).trim()
        val artist = whole.substring(split + SEPARATOR.length).trim()
        if (title.isEmpty() || artist.isEmpty()) return null
        return DetectedSong(title, artist, at)
    }
}
