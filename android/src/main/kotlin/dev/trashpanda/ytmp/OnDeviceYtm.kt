package dev.trashpanda.ytmp

import android.content.Context
import com.chaquo.python.PyException
import dev.trashpanda.ytmp.core.PlaybackFirst
import dev.trashpanda.ytmp.core.RadioSource
import dev.trashpanda.ytmp.core.CatalogSource
import dev.trashpanda.ytmp.protocol.AlbumPage
import dev.trashpanda.ytmp.protocol.ArtistPage
import dev.trashpanda.ytmp.protocol.LikeStatus
import dev.trashpanda.ytmp.protocol.Lyrics
import dev.trashpanda.ytmp.protocol.PlaylistItemRef
import dev.trashpanda.ytmp.protocol.PlaylistPage
import dev.trashpanda.ytmp.protocol.HomePage
import dev.trashpanda.ytmp.protocol.YoutubeAccount
import dev.trashpanda.ytmp.protocol.PlaylistSummary
import dev.trashpanda.ytmp.protocol.PodcastPage
import dev.trashpanda.ytmp.protocol.SearchPage
import dev.trashpanda.ytmp.protocol.SearchType
import dev.trashpanda.ytmp.protocol.wireName
import com.chaquo.python.Kwarg
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dev.trashpanda.ytmp.core.SongSearch
import dev.trashpanda.ytmp.core.ResolvedStream
import dev.trashpanda.ytmp.core.StreamResolver
import dev.trashpanda.ytmp.host.SourceBlockedException
import dev.trashpanda.ytmp.host.SourceException
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory

/** Where the Python module's log (yt-dlp included) goes: the app's log (Python logging levels). */
class PythonLog {
    fun log(level: Int, name: String, message: String) {
        val logger = LoggerFactory.getLogger(name)
        when {
            level >= 40 -> logger.error(message)
            level >= 30 -> logger.warn(message)
            level >= 20 -> logger.info(message)
            else -> logger.debug(message)
        }
    }
}

/** The YouTube Music sign-in doesn't work (any more): signed out elsewhere, or the cookies expired. */
class NotSignedInException(message: String) : SourceException(message)

/**
 * The YTM source module running on the phone: the Python code from ytm-module/ (ytmusicapi +
 * yt-dlp), called directly through Chaquopy instead of over HTTP.
 */
class OnDeviceYtm(context: Context, language: String = "en", location: String = "BE") : SongSearch, StreamResolver, RadioSource, CatalogSource {
    /** A resolved stream: the URL plus the headers YouTube expects when fetching it, and its exact length. */
    class Stream(val url: String, val headers: Map<String, String>, val durationMs: Long?)

    private val python: Python
    private val core: PyObject by lazy {
        python.getModule("ytmp_ytm.core").callAttr("YtmCore", language, location, null).also { it.callAttr("warm_up") }
    }
    private val json: PyObject

    /**
     * yt-dlp's JavaScript engine, set up on the first stream lookup: starting it waits for the
     * main thread, so not in here (see [JsEngine]).
     */
    private val jsEngine by lazy {
        JsEngine.create(context)?.also { python.getModule("ytmp_ytm.jsc").callAttr("use_engine", it) }
    }

    /**
     * PO tokens (see [BotGuard]), so YouTube's bot check comes later. Only with the engine
     * above: the player that uses them (mobile web) needs it.
     */
    private val botGuard by lazy {
        jsEngine?.let { BotGuard(context).also { python.getModule("ytmp_ytm.pot").callAttr("use_botguard", it) } }
    }

    init {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        python = Python.getInstance()
        json = python.getModule("json")
        python.getModule("ytmp_ytm.core").callAttr("log_to", PythonLog())
    }

    override suspend fun search(query: String, type: SearchType): SearchPage = python {
        val page = core.callAttr("search", query, type.wireName, 20)
        ProtocolJson.decodeFromString(SearchPage.serializer(), json.callAttr("dumps", page).toString())
    }

    suspend fun suggestions(query: String): List<String> = python {
        core.callAttr("suggestions", query).asList().map { it.toString() }
    }

    override suspend fun radio(seedSongId: String): List<Song> = python {
        val results = core.callAttr("radio", seedSongId, 25)
        ProtocolJson.decodeFromString(ListSerializer(Song.serializer()), json.callAttr("dumps", results).toString())
    }

    override suspend fun artist(id: String): ArtistPage = python {
        ProtocolJson.decodeFromString(ArtistPage.serializer(), json.callAttr("dumps", core.callAttr("artist", id)).toString())
    }

    override suspend fun album(id: String): AlbumPage = python {
        ProtocolJson.decodeFromString(AlbumPage.serializer(), json.callAttr("dumps", core.callAttr("album", id)).toString())
    }

    override suspend fun playlist(id: String, more: String?): PlaylistPage = python {
        ProtocolJson.decodeFromString(PlaylistPage.serializer(), json.callAttr("dumps", core.callAttr("playlist", id, false, more)).toString())
    }

    override suspend fun podcast(id: String): PodcastPage = python {
        ProtocolJson.decodeFromString(PodcastPage.serializer(), json.callAttr("dumps", core.callAttr("podcast", id)).toString())
    }

    override suspend fun song(id: String): Song = python {
        ProtocolJson.decodeFromString(Song.serializer(), json.callAttr("dumps", core.callAttr("song", id)).toString())
    }

    override suspend fun home(): HomePage = python {
        ProtocolJson.decodeFromString(HomePage.serializer(), json.callAttr("dumps", core.callAttr("home")).toString())
    }

    /** YouTube Music's lyrics for the song, or null when it has none. */
    suspend fun lyrics(songId: String): Lyrics? = python {
        try {
            ProtocolJson.decodeFromString(Lyrics.serializer(), json.callAttr("dumps", core.callAttr("lyrics", songId)).toString())
        } catch (e: PyException) {
            if (e.message.orEmpty().startsWith("NotFound")) null else throw e
        }
    }

    // The owner's YouTube Music account (see PhoneYoutubeAccount).

    /** Signs in with the cookies of a music.youtube.com session; throws [SourceException] when they aren't signed in. */
    suspend fun signIn(cookie: String): YoutubeAccount = python { account(core.callAttr("sign_in", cookie))!! }

    suspend fun signOut() = python { core.callAttr("sign_out"); Unit }

    /** Null when signed out; throws [SourceException] when the sign-in expired. */
    suspend fun account(): YoutubeAccount? = python { account(core.callAttr("account")) }

    suspend fun personalHome(): HomePage = python {
        ProtocolJson.decodeFromString(HomePage.serializer(), json.callAttr("dumps", core.callAttr("home", true)).toString())
    }

    suspend fun personalSearch(query: String, type: SearchType): SearchPage = python {
        val page = core.callAttr("search", query, type.wireName, 20, true)
        ProtocolJson.decodeFromString(SearchPage.serializer(), json.callAttr("dumps", page).toString())
    }

    suspend fun personalRadio(seedSongId: String): List<Song> = python {
        val results = core.callAttr("radio", seedSongId, 25, true)
        ProtocolJson.decodeFromString(ListSerializer(Song.serializer()), json.callAttr("dumps", results).toString())
    }

    suspend fun personalSuggestions(query: String): List<String> = python {
        core.callAttr("suggestions", query, true).asList().map { it.toString() }
    }

    suspend fun library(): HomePage = python {
        ProtocolJson.decodeFromString(HomePage.serializer(), json.callAttr("dumps", core.callAttr("library")).toString())
    }

    suspend fun personalPlaylist(id: String, more: String?): PlaylistPage = python {
        ProtocolJson.decodeFromString(PlaylistPage.serializer(), json.callAttr("dumps", core.callAttr("playlist", id, true, more)).toString())
    }

    suspend fun personalPodcast(id: String): PodcastPage = python {
        ProtocolJson.decodeFromString(PodcastPage.serializer(), json.callAttr("dumps", core.callAttr("podcast", id, true)).toString())
    }

    suspend fun setPodcastSaved(id: String, saved: Boolean) = python {
        core.callAttr("set_podcast_saved", id, saved); Unit
    }

    suspend fun rating(songId: String): LikeStatus = python {
        val rating = core.callAttr("rating", songId).toString()
        LikeStatus(liked = rating == "like", disliked = rating == "dislike")
    }

    suspend fun setRating(songId: String, rating: LikeStatus) = python {
        core.callAttr("set_rating", songId, if (rating.liked) "like" else if (rating.disliked) "dislike" else "none"); Unit
    }

    suspend fun removeFromPlaylist(playlistId: String, items: List<PlaylistItemRef>) = python {
        val list = python.builtins.callAttr("list")
        for (item in items) list.callAttr("append", python.builtins.callAttr("dict", Kwarg("songId", item.songId), Kwarg("itemId", item.itemId)))
        core.callAttr("remove_from_playlist", playlistId, list); Unit
    }

    suspend fun movePlaylistItem(playlistId: String, itemId: String, beforeItemId: String?) = python {
        core.callAttr("move_in_playlist", playlistId, itemId, beforeItemId); Unit
    }

    suspend fun renamePlaylist(playlistId: String, title: String) = python { core.callAttr("rename_playlist", playlistId, title); Unit }

    suspend fun deletePlaylist(playlistId: String) = python { core.callAttr("delete_playlist", playlistId); Unit }

    suspend fun addToHistory(songId: String) = python { core.callAttr("add_to_history", songId); Unit }

    /** The account's YouTube Music history, newest first: each song and about how many days ago (null: not known). */
    suspend fun history(): List<Pair<Song, Int?>> = python {
        ProtocolJson.decodeFromString(ListSerializer(HistoryEntry.serializer()), json.callAttr("dumps", core.callAttr("history")).toString())
            .map { it.song to it.daysAgo }
    }

    @Serializable
    private class HistoryEntry(val song: Song, val daysAgo: Int? = null)

    suspend fun ownPlaylists(): List<PlaylistSummary> = python {
        ProtocolJson.decodeFromString(ListSerializer(PlaylistSummary.serializer()), json.callAttr("dumps", core.callAttr("own_playlists")).toString())
    }

    suspend fun addToPlaylist(playlistId: String, songIds: List<String>) = python {
        core.callAttr("add_to_playlist", playlistId, python.builtins.callAttr("list", songIds.toTypedArray())); Unit
    }

    suspend fun createPlaylist(title: String, songIds: List<String>): String = python {
        core.callAttr("create_playlist", title, python.builtins.callAttr("list", songIds.toTypedArray())).toString()
    }

    private fun account(value: PyObject?): YoutubeAccount? =
        value?.let { ProtocolJson.decodeFromString(YoutubeAccount.serializer(), json.callAttr("dumps", it).toString()) }

    override suspend fun resolveStream(songId: String): String = stream(songId).url

    override suspend fun resolve(songId: String): ResolvedStream = stream(songId).let { ResolvedStream(it.url, it.durationMs) }

    suspend fun stream(songId: String, fresh: Boolean = false): Stream = python(playback = true) {
        botGuard
        val info = core.callAttr("stream", songId, fresh)
        val headers = info["http_headers"]!!.asMap().entries.associate { (k, v) -> k.toString() to v.toString() }
        // Python's None arrives as null.
        Stream(info["url"].toString(), headers, info["duration_ms"]?.toLong())
    }

    /**
     * Python runs one thing at a time, so a stream lookup goes first: everything else waits while
     * one runs (a song that has to start shouldn't wait for a radio, lyrics or a page).
     */
    private val playbackFirst = PlaybackFirst()

    /**
     * Runs Python off the main thread, and turns Python errors into [SourceException]s.
     * [playback]: a stream lookup, which goes before the rest.
     */
    private suspend fun <T> python(playback: Boolean = false, block: () -> T): T =
        if (playback) playbackFirst.playback { runPython(block) } else playbackFirst.other { runPython(block) }

    private suspend fun <T> runPython(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: PyException) {
            // e.g. "Unavailable: Video unavailable" -> "Video unavailable"
            val message = e.message?.substringAfter(": ") ?: "YouTube Music error"
            throw when {
                e.message.orEmpty().startsWith("NotSignedIn") -> NotSignedInException(message)
                e.message.orEmpty().startsWith("Blocked") -> SourceBlockedException(message)
                else -> SourceException(message)
            }
        }
    }
}
