package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.PlaylistItemRef
import dev.trashpanda.ytmp.protocol.EpisodeProgress
import dev.trashpanda.ytmp.protocol.LikeStatus
import dev.trashpanda.ytmp.protocol.Lyrics
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

/**
 * A source refusing for a while, like YouTube's bot check: the song itself is fine, and the
 * next one would fail the same way, so the room waits on it instead of skipping.
 */
interface SourceBlocked

/** Searches the host's sources. */
fun interface SongSearch {
    suspend fun search(query: String, type: SearchType): SearchPage
}

/** Search suggestions while typing, e.g. "daft p" -> "daft punk one more time". */
fun interface SearchSuggestions {
    suspend fun suggestions(query: String): List<String>
}

/** A source's own lyrics for its songs (YTM's), or null when it has none. */
fun interface LyricsSource {
    suspend fun lyrics(song: Song): Lyrics?
}

/** Songs similar to a seed song (YTM's radio), for Start radio and the autoplay queue. */
fun interface RadioSource {
    suspend fun radio(seedSongId: String): List<Song>
}

/** The home page, and artist, album, playlist and podcast pages of a source. */
interface CatalogSource {
    suspend fun artist(id: String): ArtistPage

    suspend fun album(id: String): AlbumPage

    /** The first part of a playlist, or with [more] (its [PlaylistPage.more]) only the next songs. */
    suspend fun playlist(id: String, more: String? = null): PlaylistPage

    suspend fun podcast(id: String): PodcastPage

    /** One song (or episode) by its ID, for a link to it. */
    suspend fun song(id: String): Song = throw UnsupportedOperationException("Songs can't be looked up here")

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

    /** The first part of one of your playlists, or with [more] (its [PlaylistPage.more]) only the next songs. */
    suspend fun playlist(id: String, more: String? = null): PlaylistPage

    /** A podcast page as this account: it says whether you're subscribed ([PodcastPage.saved]); null where that isn't possible. */
    suspend fun podcast(id: String): PodcastPage? = null

    /** Subscribes to a podcast (adds it to this account's library), or unsubscribes. */
    suspend fun setPodcastSaved(id: String, saved: Boolean): Unit = throw UnsupportedOperationException("Not here")

    /** Thumbs up. */
    suspend fun liked(songId: String): Boolean

    suspend fun setLiked(songId: String, liked: Boolean)

    /** Thumbs up or down (or neither). */
    suspend fun rating(songId: String): LikeStatus = LikeStatus(liked(songId))

    suspend fun setRating(songId: String, rating: LikeStatus) = setLiked(songId, rating.liked)

    // Your own playlists ([PlaylistPage.owned]).

    suspend fun removeFromPlaylist(playlistId: String, items: List<PlaylistItemRef>): Unit = throw UnsupportedOperationException("Not here")

    /** [itemId] goes right before [beforeItemId] (null: to the end). */
    suspend fun movePlaylistItem(playlistId: String, itemId: String, beforeItemId: String?): Unit = throw UnsupportedOperationException("Not here")

    suspend fun renamePlaylist(playlistId: String, title: String): Unit = throw UnsupportedOperationException("Not here")

    suspend fun deletePlaylist(playlistId: String): Unit = throw UnsupportedOperationException("Not here")

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

    /** Whether search and radio go through this account (see [search], [radio]). On unless turned off. */
    suspend fun personalize(): Boolean = true

    suspend fun setPersonalize(on: Boolean) {}

    /** Searches as this account, so results fit its taste like in YouTube Music; null where that isn't possible. */
    suspend fun search(query: String, type: SearchType): SearchPage? = null

    /** Suggestions while typing, as this account; null where that isn't possible. */
    suspend fun suggestions(query: String): List<String>? = null

    /** A radio from [seedSongId] as this account (it fits its taste); null where that isn't possible. */
    suspend fun radio(seedSongId: String): List<Song>? = null
}

/** This account, if it's signed in and search and radio should go through it ([PersonalCatalog.personalize]). */
suspend fun PersonalCatalog.personalized(): PersonalCatalog? = takeIf { personalize() && account() != null }

/**
 * Where people are in podcast episodes, kept on their accounts (docs/features/ui.md#podcasts):
 * a room asks it to resume an episode someone added, and tells it when an episode is paused,
 * skipped or ends for everyone listening.
 */
interface EpisodeBookmarks {
    /** Where the account in [accountToken] stopped in [episodeId], or null. */
    suspend fun progress(accountToken: String, episodeId: String): EpisodeProgress?

    /** The account in [accountToken] is at [positionMs] in [episode]. Must not block. */
    fun report(accountToken: String, episode: Song, positionMs: Long)
}

/** A speaker or TV the host found, that rooms can play on. */
data class OutputDevice(val id: String, val name: String, val kind: OutputKind)

/**
 * A room as speaker drivers see it: the host's own [Room], or a room on another host that
 * this device plays on speakers near it (a phone in a server room).
 */
interface OutputRoom {
    val code: String

    suspend fun view(): RoomView

    /** [listener] is called (on any thread, without waiting) after every change. */
    fun addChangeListener(listener: () -> Unit)

    /** Tells the room about a problem, e.g. a speaker that couldn't play a song. */
    suspend fun notice(message: String)

    /** The volume the device actually has. */
    suspend fun reportOutputVolume(outputId: String, volume: Double)

    /** The device plays something else now (another app took it): let go of it. */
    suspend fun outputTakenOver(outputId: String)

    /** The device skipped by itself (its own buttons) from [fromItemId]. */
    suspend fun outputSkipped(fromItemId: String, forward: Boolean)

    /** The device has loaded [itemId] (or couldn't): a [RoomView.starting] song can start for it. */
    suspend fun outputReady(outputId: String, itemId: String)
}

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
    /** Outputs that show the party screen: the guest token its page joins with, by output id. */
    val screens: Map<String, String> = emptyMap(),
    /**
     * [current] is loading on the players: load it paused at [positionMs] and say so
     * ([OutputRoom.outputReady]); [playing] turns true when everyone can start.
     */
    val starting: Boolean = false,
    /** How fast [current] plays (an episode can play faster or slower; music at 1). */
    val speed: Double = 1.0,
) {
    fun positionAt(timeMs: Long): Long = if (playing) positionMs + ((timeMs - hostTimeMs) * speed).toLong() else positionMs
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

    /** SHA-256 as hex. */
    fun sha256(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

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
