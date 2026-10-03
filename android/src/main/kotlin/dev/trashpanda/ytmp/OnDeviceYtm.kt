package dev.trashpanda.ytmp

import android.content.Context
import com.chaquo.python.PyException
import dev.trashpanda.ytmp.core.RadioSource
import dev.trashpanda.ytmp.core.CatalogSource
import dev.trashpanda.ytmp.protocol.AlbumPage
import dev.trashpanda.ytmp.protocol.ArtistPage
import dev.trashpanda.ytmp.protocol.PlaylistPage
import dev.trashpanda.ytmp.protocol.HomePage
import dev.trashpanda.ytmp.protocol.YoutubeAccount
import dev.trashpanda.ytmp.protocol.PlaylistSummary
import dev.trashpanda.ytmp.protocol.PodcastPage
import dev.trashpanda.ytmp.protocol.SearchPage
import dev.trashpanda.ytmp.protocol.SearchType
import dev.trashpanda.ytmp.protocol.wireName
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dev.trashpanda.ytmp.core.SongSearch
import dev.trashpanda.ytmp.core.ResolvedStream
import dev.trashpanda.ytmp.core.StreamResolver
import dev.trashpanda.ytmp.host.SourceException
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

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
    private val core: PyObject by lazy { python.getModule("ytmp_ytm.core").callAttr("YtmCore", language, location, null) }
    private val json: PyObject

    init {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        python = Python.getInstance()
        json = python.getModule("json")
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

    override suspend fun playlist(id: String): PlaylistPage = python {
        ProtocolJson.decodeFromString(PlaylistPage.serializer(), json.callAttr("dumps", core.callAttr("playlist", id)).toString())
    }

    override suspend fun podcast(id: String): PodcastPage = python {
        ProtocolJson.decodeFromString(PodcastPage.serializer(), json.callAttr("dumps", core.callAttr("podcast", id)).toString())
    }

    override suspend fun home(): HomePage = python {
        ProtocolJson.decodeFromString(HomePage.serializer(), json.callAttr("dumps", core.callAttr("home")).toString())
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

    suspend fun library(): HomePage = python {
        ProtocolJson.decodeFromString(HomePage.serializer(), json.callAttr("dumps", core.callAttr("library")).toString())
    }

    suspend fun personalPlaylist(id: String): PlaylistPage = python {
        ProtocolJson.decodeFromString(PlaylistPage.serializer(), json.callAttr("dumps", core.callAttr("playlist", id, true)).toString())
    }

    suspend fun liked(songId: String): Boolean = python { core.callAttr("liked", songId).toBoolean() }

    suspend fun setLiked(songId: String, liked: Boolean) = python { core.callAttr("set_liked", songId, liked); Unit }

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

    suspend fun stream(songId: String, fresh: Boolean = false): Stream = python {
        val info = core.callAttr("stream", songId, fresh)
        val headers = info["http_headers"]!!.asMap().entries.associate { (k, v) -> k.toString() to v.toString() }
        // Python's None arrives as null.
        Stream(info["url"].toString(), headers, info["duration_ms"]?.toLong())
    }

    /** Runs Python off the main thread, and turns Python errors into [SourceException]s. */
    private suspend fun <T> python(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: PyException) {
            // e.g. "Unavailable: Video unavailable" -> "Video unavailable"
            val message = e.message?.substringAfter(": ") ?: "YouTube Music error"
            throw if (e.message.orEmpty().startsWith("NotSignedIn")) NotSignedInException(message) else SourceException(message)
        }
    }
}
