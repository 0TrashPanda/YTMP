package dev.trashpanda.ytmp.cast

import dev.trashpanda.ytmp.cast.CastChannel.Companion.NS_MEDIA
import dev.trashpanda.ytmp.cast.CastChannel.Companion.NS_RECEIVER
import dev.trashpanda.ytmp.cast.CastChannel.Companion.RECEIVER
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the device reports about itself. */
data class ReceiverStatus(val volume: Double?, val muted: Boolean?, val runningAppIds: List<String>)

/** What the media player on the device reports. */
data class MediaStatus(
    val mediaSessionId: Int?,
    /** IDLE, BUFFERING, PLAYING or PAUSED. */
    val playerState: String,
    val currentTimeMs: Long,
    val contentId: String?,
    /** Why the player is IDLE: FINISHED, CANCELLED, INTERRUPTED or ERROR. */
    val idleReason: String?,
    /** When this status was received (System.currentTimeMillis). */
    val receivedAt: Long,
)

/** Song details shown on the TV or speaker's screen. */
data class CastMetadata(val title: String, val artist: String, val album: String?, val imageUrl: String?)

/**
 * Plays audio on a Cast device with Google's Default Media Receiver (the built-in player that
 * every Cast device has), via an open [CastChannel].
 */
class CastMediaPlayer(private val channel: CastChannel) {
    private var transportId: String? = null
    private var mediaSessionId: Int? = null

    suspend fun receiverStatus(): ReceiverStatus =
        parseReceiver(channel.request(NS_RECEIVER, RECEIVER, payload("GET_STATUS")))

    /**
     * The device's volume whenever it changes, including changes made elsewhere (the device's
     * own buttons, its app, Google Home), rounded to 1%.
     */
    val volumeChanges: Flow<Double> = channel.messages
        .mapNotNull { (namespace, json) ->
            when (namespace) {
                NS_RECEIVER -> json["status"]?.jsonObject?.get("volume")?.jsonObject
                NS_MULTIZONE -> json["device"]?.jsonObject?.get("volume")?.jsonObject
                else -> null
            }?.get("level")?.jsonPrimitive?.doubleOrNull
        }
        .map { Math.round(it * 100) / 100.0 }
        .distinctUntilChanged()

    /** Starts the media player app on the device (or reuses it if it is already running). */
    suspend fun launch() {
        var status = channel.request(NS_RECEIVER, RECEIVER, payload("GET_STATUS"))
        var app = runningMediaApp(status)
        if (app == null) {
            status = channel.request(NS_RECEIVER, RECEIVER, payload("LAUNCH", "appId" to JsonPrimitive(DEFAULT_MEDIA_RECEIVER)))
            app = runningMediaApp(status) ?: error("The Cast device didn't start its media player")
        }
        transportId = app
        channel.connect(app)
    }

    /** Loads and (optionally) starts a song at [positionMs]. */
    suspend fun load(url: String, contentType: String, metadata: CastMetadata, positionMs: Long, autoplay: Boolean): MediaStatus {
        val media = JsonObject(
            mapOf(
                "contentId" to JsonPrimitive(url),
                "contentType" to JsonPrimitive(contentType),
                "streamType" to JsonPrimitive("BUFFERED"),
                "metadata" to JsonObject(
                    buildMap {
                        put("metadataType", JsonPrimitive(3)) // MUSIC_TRACK
                        put("title", JsonPrimitive(metadata.title))
                        put("artist", JsonPrimitive(metadata.artist))
                        metadata.album?.let { put("albumName", JsonPrimitive(it)) }
                        metadata.imageUrl?.let { put("images", JsonArray(listOf(JsonObject(mapOf("url" to JsonPrimitive(it)))))) }
                    },
                ),
            ),
        )
        // The first answer is often just "IDLE"; the real outcome (playing, or failed) follows.
        return coroutineScope {
            val outcome = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(LOAD_TIMEOUT_MS) {
                    channel.messages.first { (namespace, json) -> namespace == NS_MEDIA && loadOutcome(json) != null }.second
                }
            }
            val reply = mediaRequest(
                payload(
                    "LOAD",
                    "media" to media,
                    "currentTime" to JsonPrimitive(positionMs / 1000.0),
                    "autoplay" to JsonPrimitive(autoplay),
                ),
            )
            val result = if (loadOutcome(reply) != null) reply.also { outcome.cancel() } else outcome.await()
            when (loadOutcome(result ?: error("The Cast device didn't start the song in time"))) {
                true -> parseMedia(result)!!
                else -> error("The Cast device couldn't play the song (${result.type ?: "error"})")
            }
        }
    }

    /** true = loaded, false = failed, null = not decided yet. */
    private fun loadOutcome(message: JsonObject): Boolean? {
        if (message.type == "LOAD_FAILED" || message.type == "LOAD_CANCELLED") return false
        val status = message["status"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        return when (status["playerState"]?.jsonPrimitive?.content) {
            "PLAYING", "PAUSED", "BUFFERING" -> true
            "IDLE" -> if (status["idleReason"]?.jsonPrimitive?.content == "ERROR") false else null
            else -> null
        }
    }

    suspend fun play() = control("PLAY")

    suspend fun pause() = control("PAUSE")

    suspend fun seek(positionMs: Long) = control("SEEK", "currentTime" to JsonPrimitive(positionMs / 1000.0))

    suspend fun status(): MediaStatus? = control("GET_STATUS")

    /** Device volume, 0.0–1.0. */
    suspend fun setVolume(level: Double) {
        channel.request(
            NS_RECEIVER, RECEIVER,
            payload("SET_VOLUME", "volume" to JsonObject(mapOf("level" to JsonPrimitive(level.coerceIn(0.0, 1.0))))),
        )
    }

    /** Closes the media player app on the device. */
    suspend fun stop() {
        val app = transportId ?: return
        val status = channel.request(NS_RECEIVER, RECEIVER, payload("GET_STATUS"))
        val sessionId = apps(status).firstOrNull { it["transportId"]?.jsonPrimitive?.content == app }?.get("sessionId")?.jsonPrimitive?.content
        if (sessionId != null) channel.request(NS_RECEIVER, RECEIVER, payload("STOP", "sessionId" to JsonPrimitive(sessionId)))
        transportId = null
        mediaSessionId = null
    }

    private suspend fun control(type: String, vararg extra: Pair<String, JsonElement>): MediaStatus? {
        val fields = buildList {
            addAll(extra)
            if (type != "GET_STATUS") add("mediaSessionId" to JsonPrimitive(mediaSessionId ?: return null))
        }
        return parseMedia(mediaRequest(payload(type, *fields.toTypedArray())))
    }

    private suspend fun mediaRequest(payload: JsonObject): JsonObject {
        val app = transportId ?: error("Call launch() first")
        return channel.request(NS_MEDIA, app, payload)
    }

    private fun runningMediaApp(status: JsonObject): String? =
        apps(status).firstOrNull { it["appId"]?.jsonPrimitive?.content == DEFAULT_MEDIA_RECEIVER }
            ?.get("transportId")?.jsonPrimitive?.content

    private fun apps(status: JsonObject): List<JsonObject> =
        status["status"]?.jsonObject?.get("applications")?.jsonArray?.map { it.jsonObject }.orEmpty()

    private fun parseReceiver(status: JsonObject): ReceiverStatus {
        val volume = status["status"]?.jsonObject?.get("volume")?.jsonObject
        return ReceiverStatus(
            volume = volume?.get("level")?.jsonPrimitive?.doubleOrNull,
            muted = volume?.get("muted")?.jsonPrimitive?.booleanOrNull,
            runningAppIds = apps(status).mapNotNull { it["appId"]?.jsonPrimitive?.content },
        )
    }

    private fun parseMedia(message: JsonObject): MediaStatus? {
        val status = message["status"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val id = status["mediaSessionId"]?.jsonPrimitive?.intOrNull
        if (id != null) mediaSessionId = id
        return MediaStatus(
            mediaSessionId = id,
            playerState = status["playerState"]?.jsonPrimitive?.content ?: "IDLE",
            currentTimeMs = ((status["currentTime"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong(),
            contentId = status["media"]?.jsonObject?.get("contentId")?.jsonPrimitive?.content,
            idleReason = status["idleReason"]?.jsonPrimitive?.content,
            receivedAt = System.currentTimeMillis(),
        )
    }

    private fun payload(type: String, vararg fields: Pair<String, JsonElement>) =
        JsonObject(mapOf("type" to JsonPrimitive(type)) + fields)

    companion object {
        /** Google's built-in media player app, available on every Cast device. */
        const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"
        private const val LOAD_TIMEOUT_MS = 20_000L
        private const val NS_MULTIZONE = "urn:x-cast:com.google.cast.multizone"
    }
}
