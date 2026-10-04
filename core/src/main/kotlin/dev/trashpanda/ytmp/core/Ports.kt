package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.AlbumPage
import dev.trashpanda.ytmp.protocol.ArtistPage
import dev.trashpanda.ytmp.protocol.OutputKind
import dev.trashpanda.ytmp.protocol.PlaylistPage
import dev.trashpanda.ytmp.protocol.HomePage
import dev.trashpanda.ytmp.protocol.YoutubeAccount
import dev.trashpanda.ytmp.protocol.YoutubeHistory
import dev.trashpanda.ytmp.protocol.RoomVisibility
import dev.trashpanda.ytmp.protocol.PlaylistSummary
import dev.trashpanda.ytmp.protocol.PodcastPage
import dev.trashpanda.ytmp.protocol.QueueItem
import dev.trashpanda.ytmp.protocol.SearchPage
import dev.trashpanda.ytmp.protocol.SearchType
import dev.trashpanda.ytmp.protocol.ServerMessage
import dev.trashpanda.ytmp.protocol.Song
import java.security.SecureRandom

/** Resolves a direct, playable stream URL for a song. Implemented by the source modules. */
fun interface StreamResolver {
    suspend fun resolveStream(songId: String): String

    /** The stream with its exact length, when the source knows it (listings round podcast lengths). */
    suspend fun resolve(songId: String): ResolvedStream = ResolvedStream(resolveStream(songId), durationMs = null)
}

data class ResolvedStream(val url: String, val durationMs: Long?)

/** Searches the host's sources. */
fun interface SongSearch {
    suspend fun search(query: String, type: SearchType): SearchPage
}

/** Search suggestions while typing, e.g. "daft p" -> "daft punk one more time". */
fun interface SearchSuggestions {
    suspend fun suggestions(query: String): List<String>
}

/** Songs similar to a seed song (YTM's radio), for Start radio and the autoplay queue. */
fun interface RadioSource {
    suspend fun radio(seedSongId: String): List<Song>
}

/** The home page, and artist, album, playlist and podcast pages of a source. */
interface CatalogSource {
    suspend fun artist(id: String): ArtistPage

    suspend fun album(id: String): AlbumPage

    suspend fun playlist(id: String): PlaylistPage

    suspend fun podcast(id: String): PodcastPage

    suspend fun home(): HomePage

    /** Home, not from a cache: pulled down to refresh. */
    suspend fun freshHome(): HomePage = home()
}

/**
 * The YouTube Music account of a phone host's owner: their own home page, library and
 * private playlists. Only for the host's own app, never for guests.
 */
interface PersonalCatalog {
    /** Null when signed out (or the sign-in expired). */
    suspend fun account(): YoutubeAccount?

    suspend fun home(): HomePage

    /** Home, not from a cache: pulled down to refresh. */
    suspend fun freshHome(): HomePage = home()

    /** "Playlists" (Liked music first) and "Podcasts". */
    suspend fun library(): HomePage

    suspend fun playlist(id: String): PlaylistPage

    /** Thumbs up. */
    suspend fun liked(songId: String): Boolean

    suspend fun setLiked(songId: String, liked: Boolean)

    /** Your own playlists, to save songs to. */
    suspend fun ownPlaylists(): List<PlaylistSummary>

    suspend fun addToPlaylist(playlistId: String, songIds: List<String>)

    /** A new private playlist; returns its ID. */
    suspend fun createPlaylist(title: String, songIds: List<String>): String

    suspend fun signOut()

    /**
     * Signs in with the cookies of a signed-in music.youtube.com session (or something that
     * contains them, like copied request headers). Where that isn't possible (a phone signs in
     * in the app itself), it throws [UnsupportedOperationException].
     */
    suspend fun signIn(cookie: String): YoutubeAccount = throw UnsupportedOperationException("Sign in in the app")

    /** Which plays go into the YouTube Music history (see [addToHistory]). */
    suspend fun historySetting(): YoutubeHistory = YoutubeHistory.SOLO

    suspend fun setHistorySetting(history: YoutubeHistory) {}

    /** Tells YouTube Music you played this song, like its own player does. */
    suspend fun addToHistory(songId: String) {}
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
    val visibility: RoomVisibility = RoomVisibility.PUBLIC,
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
