package dev.trashpanda.ytmp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The songs Now Playing recognized lately, newest first, kept on the phone (the search page's
 * Now Playing icon shows them). Only the last [MAX] are kept.
 */
class NowPlayingHistory(context: Context) {
    private val prefs = context.getSharedPreferences("ytmp-now-playing", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(DetectedSong.serializer())

    private val songs = MutableStateFlow(load())
    val recent: StateFlow<List<DetectedSong>> = songs

    /** Adds a song at the top. The same song again right after (Now Playing posts it twice, e.g. when the screen turns on) only moves its time forward. */
    @Synchronized
    fun add(song: DetectedSong) {
        val now = songs.value
        val same = now.firstOrNull()?.let { it.title == song.title && it.artist == song.artist } == true
        val next = if (same) listOf(song) + now.drop(1) else (listOf(song) + now).take(MAX)
        songs.value = next
        prefs.edit().putString(KEY, json.encodeToString(serializer, next)).apply()
    }

    private fun load(): List<DetectedSong> =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

    companion object {
        const val MAX = 20
        private const val KEY = "songs"
    }
}
