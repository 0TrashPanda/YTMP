package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.AlbumPage
import dev.trashpanda.ytmp.protocol.ArtistPage
import dev.trashpanda.ytmp.protocol.OutputKind
import dev.trashpanda.ytmp.protocol.PlaylistPage
import dev.trashpanda.ytmp.protocol.QueueItem
import dev.trashpanda.ytmp.protocol.SearchPage
import dev.trashpanda.ytmp.protocol.SearchType
import dev.trashpanda.ytmp.protocol.ServerMessage
import dev.trashpanda.ytmp.protocol.Song
import java.security.SecureRandom

/** Resolves a direct, playable stream URL for a song. Implemented by the source modules. */
fun interface StreamResolver {
    suspend fun resolveStream(songId: String): String
}

/** Searches the host's sources. */
fun interface SongSearch {
    suspend fun search(query: String, type: SearchType): SearchPage
}

/** Songs similar to a seed song (YTM's radio), for Start radio and the autoplay queue. */
fun interface RadioSource {
    suspend fun radio(seedSongId: String): List<Song>
}

/** Artist, album and playlist pages of a source. */
interface CatalogSource {
    suspend fun artist(id: String): ArtistPage

    suspend fun album(id: String): AlbumPage

    suspend fun playlist(id: String): PlaylistPage
}

/** A speaker or TV the host found, that rooms can play on. */
data class OutputDevice(val id: String, val name: String, val kind: OutputKind)

/** What an output driver needs to follow a room. */
data class RoomView(
    val current: QueueItem?,
    /** Direct stream URL of [current] (set whenever [current] is). */
    val streamUrl: String?,
    val playing: Boolean,
    /** Position at [hostTimeMs]; while playing it moves on from there. */
    val positionMs: Long,
    val hostTimeMs: Long,
    /** Outputs the room plays on, with their wanted volume (null = leave as is). */
    val activeOutputs: Map<String, Double?>,
    /** The last played song and the song after the current one, so a speaker can skip back and forth itself. */
    val previous: QueueItem? = null,
    val next: QueueItem? = null,
) {
    fun positionAt(timeMs: Long): Long = if (playing) positionMs + (timeMs - hostTimeMs) else positionMs
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

/** An account a participant proved with a token from a trusted auth server. */
data class AccountIdentity(
    /** `username@issuer` */
    val id: String,
    val displayName: String,
    /** The account doesn't want to be named in other people's listening history. */
    val hideFromHistory: Boolean = false,
)

/** A song that finished (played to the end or skipped), and who was in the room. Feeds the listening history. */
data class FinishedPlay(
    val item: QueueItem,
    val startedAt: Long,
    val heardMs: Long,
    val skipped: Boolean,
    val roomCode: String,
    val roomName: String,
    /** Everyone online when it finished. */
    val listeners: List<Listener>,
) {
    data class Listener(
        val participantId: String,
        val name: String,
        val accountId: String?,
        /** The host token they joined with: the host reports the play to their auth server with it. */
        val accountToken: String?,
        val hideFromHistory: Boolean,
    )
}
