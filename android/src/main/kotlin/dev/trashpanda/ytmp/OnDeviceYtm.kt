package dev.trashpanda.ytmp

import android.content.Context
import com.chaquo.python.PyException
import dev.trashpanda.ytmp.core.RadioSource
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import dev.trashpanda.ytmp.core.SongSearch
import dev.trashpanda.ytmp.core.StreamResolver
import dev.trashpanda.ytmp.host.SourceException
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/**
 * The YTM source module running on the phone: the Python code from ytm-module/ (ytmusicapi +
 * yt-dlp), called directly through Chaquopy instead of over HTTP.
 */
class OnDeviceYtm(context: Context, language: String = "en", location: String = "BE") : SongSearch, StreamResolver, RadioSource {
    /** A resolved stream: the URL plus the headers YouTube expects when fetching it. */
    class Stream(val url: String, val headers: Map<String, String>)

    private val python: Python
    private val core: PyObject by lazy { python.getModule("ytmp_ytm.core").callAttr("YtmCore", language, location, null) }
    private val json: PyObject

    init {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        python = Python.getInstance()
        json = python.getModule("json")
    }

    override suspend fun search(query: String): List<Song> = python {
        val results = core.callAttr("search", query, 20)
        ProtocolJson.decodeFromString(ListSerializer(Song.serializer()), json.callAttr("dumps", results).toString())
    }

    override suspend fun radio(seedSongId: String): List<Song> = python {
        val results = core.callAttr("radio", seedSongId, 25)
        ProtocolJson.decodeFromString(ListSerializer(Song.serializer()), json.callAttr("dumps", results).toString())
    }

    override suspend fun resolveStream(songId: String): String = stream(songId).url

    suspend fun stream(songId: String, fresh: Boolean = false): Stream = python {
        val info = core.callAttr("stream", songId, fresh)
        val headers = info["http_headers"]!!.asMap().entries.associate { (k, v) -> k.toString() to v.toString() }
        Stream(info["url"].toString(), headers)
    }

    /** Runs Python off the main thread, and turns Python errors into [SourceException]s. */
    private suspend fun <T> python(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: PyException) {
            // e.g. "Unavailable: Video unavailable" -> "Video unavailable"
            throw SourceException(e.message?.substringAfter(": ") ?: "YouTube Music error")
        }
    }
}
