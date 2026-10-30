package dev.trashpanda.ytmp.cast

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.Reader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** What the YouTube app on a TV is doing, as far as its last messages tell. */
data class LoungeStatus(
    val videoId: String? = null,
    /** -1 nothing, 0 ended, 1 playing, 2 paused, 3 loading, 1081 an ad. */
    val state: Int = -1,
    val positionMs: Long = 0,
    /** When [positionMs] was reported (this machine's clock). */
    val receivedAt: Long = 0,
    /** 0–100. */
    val volume: Int? = null,
    val autoplay: Boolean? = null,
    val adPlaying: Boolean = false,
) {
    companion object {
        const val ENDED = 0
        const val PLAYING = 1
        const val PAUSED = 2
        const val LOADING = 3
        const val AD = 1081
    }
}

/**
 * A remote control for the YouTube app on a TV, like the YouTube app on a phone: the
 * *Lounge* API, a BrowserChannel session on youtube.com that both the remote and the TV
 * (its [screenId], from [Dial.youtube]) are connected to. The TV plays the videos itself,
 * from YouTube. Commands go up as form posts, the TV's state comes down a long-polled stream
 * of `[id, [event, args]]` chunks. Not a public API: it's what YouTube's own apps use.
 */
class YoutubeLounge(
    private val screenId: String,
    /** How the TV lists this remote ("YTMP"). */
    private val name: String = "YTMP",
    private val baseUrl: String = "https://www.youtube.com/api/lounge",
) : AutoCloseable {
    private val _status = MutableStateFlow(LoungeStatus())
    val status: StateFlow<LoungeStatus> = _status

    private val remoteId = UUID.randomUUID().toString()
    private var loungeToken = ""
    private var sid = ""
    private var gsessionId = ""
    @Volatile private var lastEventId = 0
    private val rid = AtomicInteger(1)
    private val offset = AtomicInteger(0)
    private var poller: Job? = null
    @Volatile private var polling: HttpURLConnection? = null
    @Volatile private var open = false

    /** Connected, and the TV app hasn't gone away. */
    val isOpen: Boolean get() = open

    /** Pairs with the TV and follows what it does (in [scope]) until [close]. */
    suspend fun connect(scope: CoroutineScope) {
        withContext(Dispatchers.IO) {
            val tokens = Json.parseToJsonElement(post("$baseUrl/pairing/get_lounge_token_batch", mapOf("screen_ids" to screenId)))
            loungeToken = tokens.jsonObject["screens"]?.jsonArray?.firstOrNull()?.jsonObject?.get("loungeToken")?.jsonPrimitive?.content
                ?: throw IOException("YouTube gave no lounge token for this TV")
            val bound = post(bindUrl("RID" to rid.getAndIncrement().toString(), "CVER" to "1", "auth_failure_option" to "send_error"), mapOf("count" to "0"))
            readChunks(bound.reader())
            if (sid.isEmpty() || gsessionId.isEmpty()) throw IOException("The TV's YouTube session didn't open")
        }
        open = true
        poller = scope.launch(Dispatchers.IO) { poll() }
    }

    /** Plays [videoId] from [positionMs], replacing what the TV plays. */
    suspend fun setPlaylist(videoId: String, positionMs: Long) = command(
        "setPlaylist",
        "videoId" to videoId,
        // Nothing more: with a list or an index the Yoton went back to its old queue's video, or ignored it.
        "currentTime" to seconds(positionMs),
    )

    suspend fun play() = command("play")

    suspend fun pause() = command("pause")

    suspend fun seek(positionMs: Long) = command("seekTo", "newTime" to seconds(positionMs))

    suspend fun setVolume(volume: Int) = command("setVolume", "volume" to volume.coerceIn(0, 100).toString())

    /** Whether the TV plays a related video by itself when a video ends. */
    suspend fun setAutoplay(on: Boolean) = command("setAutoplayMode", "autoplayMode" to if (on) "ENABLED" else "DISABLED")

    suspend fun stopVideo() = command("stopVideo")

    /** Asks the TV where it is: the answer arrives in [status]. */
    suspend fun requestStatus() = command("getNowPlaying")

    private suspend fun command(name: String, vararg params: Pair<String, String>) {
        if (!open) throw IOException("Not connected to the TV")
        withContext(Dispatchers.IO) {
            val body = linkedMapOf("count" to "1", "ofs" to offset.getAndIncrement().toString(), "req0__sc" to name)
            for ((key, value) in params) body["req0_$key"] = value
            post(bindUrl("SID" to sid, "gsessionid" to gsessionId, "RID" to rid.getAndIncrement().toString(), "AID" to lastEventId.toString()), body)
        }
    }

    /** The TV's events, one long request after another, until the session ends. */
    private suspend fun poll() {
        while (open && currentCoroutineContext().isActive) {
            val connection = open(bindUrl("SID" to sid, "gsessionid" to gsessionId, "RID" to "rpc", "CI" to "0", "AID" to lastEventId.toString()), "GET", POLL_TIMEOUT_MS)
            polling = connection
            try {
                val code = connection.responseCode
                if (code != 200) {
                    // 400/404/410: the session is gone (expired, or the TV left).
                    log.info("YouTube lounge: the session ended ({})", code)
                    open = false
                    return
                }
                connection.inputStream.reader().use { readChunks(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!open) return
                log.info("YouTube lounge: lost the TV's events: {}", e.toString())
                open = false
                return
            } finally {
                connection.disconnect()
            }
        }
    }

    /** Reads `length\n[[id,[event,…]],…]` chunks until the stream ends. */
    private fun readChunks(reader: Reader) {
        val buffered = reader.buffered()
        while (true) {
            val line = buffered.readLine() ?: return
            val length = line.trim().toIntOrNull() ?: continue
            val chars = CharArray(length)
            var read = 0
            while (read < length) {
                val n = buffered.read(chars, read, length - read)
                if (n < 0) return
                read += n
            }
            val chunk = runCatching { Json.parseToJsonElement(String(chars)) }.getOrNull() as? JsonArray ?: continue
            for (entry in chunk) handle(entry as? JsonArray ?: continue)
        }
    }

    private fun handle(entry: JsonArray) {
        val id = entry.getOrNull(0)?.jsonPrimitive?.content?.toIntOrNull() ?: return
        if (id > lastEventId) lastEventId = id
        val event = entry.getOrNull(1) as? JsonArray ?: return
        val type = event.getOrNull(0)?.jsonPrimitive?.content ?: return
        val args = event.getOrNull(1)
        fun arg(name: String) = (args as? JsonObject)?.get(name)?.let { (it as? JsonPrimitive)?.content }
        val now = System.currentTimeMillis()
        when (type) {
            "c" -> sid = args?.jsonPrimitive?.content.orEmpty()
            "S" -> gsessionId = args?.jsonPrimitive?.content.orEmpty()
            "nowPlaying" -> _status.update {
                val state = arg("state")?.toIntOrNull()
                it.copy(
                    videoId = arg("videoId")?.ifEmpty { null },
                    state = state ?: -1,
                    positionMs = millis(arg("currentTime")),
                    receivedAt = now,
                    adPlaying = state == LoungeStatus.AD,
                )
            }
            "onStateChange" -> _status.update {
                val state = arg("state")?.toIntOrNull() ?: it.state
                it.copy(state = state, positionMs = millis(arg("currentTime")), receivedAt = now, adPlaying = state == LoungeStatus.AD)
            }
            "onAdStateChange", "adPlaying" -> _status.update { it.copy(adPlaying = true) }
            "onVolumeChanged" -> _status.update { it.copy(volume = arg("volume")?.toIntOrNull() ?: it.volume) }
            "onAutoplayModeChanged" -> _status.update { it.copy(autoplay = arg("autoplayMode") == "ENABLED") }
            "loungeScreenDisconnected" -> {
                log.info("YouTube lounge: the TV app went away")
                open = false
            }
        }
    }

    private fun bindUrl(vararg params: Pair<String, String>): String {
        val all = listOf(
            "VER" to "8",
            "device" to "REMOTE_CONTROL",
            "id" to remoteId,
            "name" to name,
            "app" to "ytmp",
            "mdx-version" to "3",
            "theme" to "cl",
            "capabilities" to "que,mus",
            "ui" to "false",
            "loungeIdToken" to loungeToken,
            "TYPE" to "xmlhttp",
        ) + params
        return "$baseUrl/bc/bind?" + all.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
    }

    private fun post(url: String, form: Map<String, String>): String {
        val connection = open(url, "POST", REQUEST_TIMEOUT_MS)
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = form.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            if (code != 200) {
                if (code in setOf(400, 404, 410)) open = false
                throw IOException("YouTube lounge answered $code")
            }
            return connection.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, method: String, readTimeoutMs: Int) =
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = REQUEST_TIMEOUT_MS
            readTimeout = readTimeoutMs
            requestMethod = method
        }

    override fun close() {
        open = false
        // The stream is a blocking read: closing the connection ends it.
        polling?.disconnect()
        poller?.cancel()
        poller = null
    }

    companion object {
        private val log = LoggerFactory.getLogger(YoutubeLounge::class.java)
        private const val REQUEST_TIMEOUT_MS = 10_000

        /** The TV sends a `noop` about every 30 s; a stream quiet for longer is dead. */
        private const val POLL_TIMEOUT_MS = 70_000

        private fun seconds(ms: Long) = "%.3f".format(java.util.Locale.ROOT, ms / 1000.0)

        private fun millis(seconds: String?) = ((seconds?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
    }
}
