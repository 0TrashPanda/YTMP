package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.ServerMessage
import dev.trashpanda.ytmp.protocol.Song
import java.security.SecureRandom

/** Resolves a direct, playable stream URL for a song. Implemented by the source modules. */
fun interface StreamResolver {
    suspend fun resolveStream(songId: String): String
}

/** Searches the host's sources. */
fun interface SongSearch {
    suspend fun search(query: String): List<Song>
}

/** One client connection. [send] must not block: it queues the message for that client. */
fun interface Outbox {
    fun send(message: ServerMessage)
}

internal object Ids {
    private val random = SecureRandom()
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun short() = random(10)

    fun token() = random(32)

    private fun random(length: Int) = buildString(length) {
        repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }
}
