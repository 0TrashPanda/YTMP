package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.QueueItem
import dev.trashpanda.ytmp.protocol.RoomSummary
import dev.trashpanda.ytmp.protocol.Song

/**
 * What a room sounds like, for room lists (docs/features/rooms/room-lifecycle.md#the-room-list): the
 * covers of its last songs and the artists it plays most. Follows the music: when the room
 * switches genre, its artists change with it.
 */
object RoomSummaries {
    const val MAX_COVERS = 4
    const val MAX_ARTISTS = 3

    /** Songs that just played that count for the artists. */
    private const val RECENT = 15

    /** Songs up next that count for the artists. */
    private const val UPCOMING = 10

    /** A cover this wide is enough for a small square (44 px, sharp on phones). */
    private const val COVER_WIDTH = 120

    fun of(history: List<QueueItem>, current: QueueItem?, queue: List<QueueItem>, playing: Boolean): RoomSummary {
        // Newest first: the song it's on, then what played before it.
        val latest = (listOfNotNull(current) + history.asReversed()).map { it.song }
        val covers = latest.mapNotNull(::cover).distinct().take(MAX_COVERS)
        val songs = latest.take(RECENT + 1) + queue.take(UPCOMING).map { it.song }
        val last = latest.firstOrNull()
        return RoomSummary(
            covers = covers,
            artists = topArtists(songs),
            song = last?.let { song -> listOfNotNull(song.title, by(song)).joinToString(" · ") },
            playing = playing && current != null,
        )
    }

    /** The names that come up most in [songs]; on a tie, the one heard most recently. */
    private fun topArtists(songs: List<Song>): List<String> {
        val counts = LinkedHashMap<String, Int>()
        for (song in songs) {
            val names = song.podcast?.let { listOf(it.name) } ?: song.artists.map { it.name }
            for (name in names.map(String::trim).filter(String::isNotEmpty).distinct()) counts[name] = (counts[name] ?: 0) + 1
        }
        // LinkedHashMap keeps the first-seen order (most recent first), and sortedByDescending is stable.
        return counts.entries.sortedByDescending { it.value }.take(MAX_ARTISTS).map { it.key }
    }

    private fun by(song: Song): String? =
        song.podcast?.name ?: song.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() }

    private fun cover(song: Song): String? {
        val sorted = song.thumbnails.sortedBy { it.width }
        return (sorted.firstOrNull { it.width >= COVER_WIDTH } ?: sorted.lastOrNull())?.url
    }
}
