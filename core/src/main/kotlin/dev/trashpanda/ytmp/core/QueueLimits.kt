package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.ErrorInfo
import dev.trashpanda.ytmp.protocol.RoomSettings
import dev.trashpanda.ytmp.protocol.Song

/**
 * A room's queue limits ([RoomSettings]: songs per person, song length, no recent repeats),
 * and when songs last started playing, which the last one needs. Only used with the room locked.
 */
internal class QueueLimits(private val clock: () -> Long) {
    /** When songs last started playing (song id -> time), oldest first. */
    private val playedAt = LinkedHashMap<String, Long>()

    /** [songId] starts playing now. */
    fun started(songId: String) {
        playedAt.remove(songId)
        playedAt[songId] = clock()
        while (playedAt.size > MAX_PLAYED_AT) playedAt.remove(playedAt.keys.first())
    }

    /**
     * The songs of [songs] that may be added, and why the others were left out (null: none were).
     * [queuedByThem]: how many songs they have in the queue now, when the songs stay in the queue
     * (adding), so the songs-per-person limit counts too; null otherwise.
     */
    fun check(settings: RoomSettings, songs: List<Song>, queuedByThem: Int?): Pair<List<Song>, ErrorInfo?> {
        val reasons = ArrayList<Pair<Int, String>>()
        var ok = songs
        settings.maxSongMinutes.takeIf { it > 0 }?.let { minutes ->
            val (tooLong, rest) = ok.partition { it.durationMs > minutes * 60_000L }
            if (tooLong.isNotEmpty()) reasons += tooLong.size to "longer than $minutes min"
            ok = rest
        }
        settings.noRepeatMinutes.takeIf { it > 0 }?.let { minutes ->
            val since = clock() - minutes * 60_000L
            val (recent, rest) = ok.partition { (playedAt[it.id] ?: Long.MIN_VALUE) >= since }
            if (recent.isNotEmpty()) reasons += recent.size to "played in the last $minutes min"
            ok = rest
        }
        if (queuedByThem != null) {
            settings.maxSongsPerPerson.takeIf { it > 0 }?.let { max ->
                val room = maxOf(0, max - queuedByThem)
                if (ok.size > room) reasons += (ok.size - room) to "you can have $max songs in the queue at once"
                ok = ok.take(room)
            }
        }
        if (reasons.isEmpty()) return ok to null
        val why = reasons.joinToString("; ") { (count, reason) -> if (songs.size == 1) reason else "$count $reason" }
        val message = when {
            songs.size == 1 -> "Not added: ${why.replaceFirstChar { it.lowercase() }}"
            ok.isEmpty() -> "None added: $why"
            else -> "Left out ${songs.size - ok.size} of ${songs.size}: $why"
        }
        return ok to ErrorInfo(ErrorCode.INVALID, message)
    }

    private companion object {
        const val MAX_PLAYED_AT = 1000
    }
}
