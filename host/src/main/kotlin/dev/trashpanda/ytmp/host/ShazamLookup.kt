package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.SongRecognizer
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.RecognizeResponse
import io.ktor.http.HttpStatusCode
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("ytmp.recognize")

/**
 * Recognize a song (docs/implementation/sources/module-api.md#recognize-a-song): asks Shazam which song a
 * signature is. The page makes the signature itself (`frontend/src/lib/shazamSignature.ts`), so
 * every host can do this, a phone too. Unofficial, the way shazamio asks (like yt-dlp for
 * YouTube): one try, no retries, since Shazam answers 429 when asked too often and the page tries
 * again with more sound anyway.
 */
class ShazamLookup(private val base: String = "https://amp.shazam.com") : SongRecognizer {
    override suspend fun recognize(signature: String, sampleMs: Long): RecognizeResponse = withContext(Dispatchers.IO) {
        val url = "$base/discovery/v5/en-US/GB/iphone/-/tag/${UUID.randomUUID().toString().uppercase()}/${UUID.randomUUID().toString().uppercase()}" +
            "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&hubv5minorversion=v5.1&hidelb=true&video=v3"
        val now = System.currentTimeMillis()
        val body = buildJsonObject {
            put("timezone", "Europe/Paris")
            putJsonObject("signature") {
                put("uri", signature)
                put("samplems", sampleMs)
            }
            put("timestamp", now)
            putJsonObject("context") {}
            putJsonObject("geolocation") {}
        }
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5000
            connection.readTimeout = 8000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Shazam-Platform", "IPHONE")
            connection.setRequestProperty("X-Shazam-AppVersion", "14.1.0")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Language", "en-US")
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.setRequestProperty("User-Agent", "Dalvik/2.1.0 (Linux; U; Android 5.0.2; VS980 4G Build/LRX22G)")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val status = connection.responseCode
            if (status == 429) throw ApiException(HttpStatusCode.TooManyRequests, ErrorCode.INVALID, "Shazam is busy, try again in a moment")
            if (status !in 200..299) {
                log.warn("Shazam answered {}", status)
                throw ApiException(HttpStatusCode.BadGateway, ErrorCode.INVALID, "Shazam answered $status")
            }
            val stream = connection.inputStream.let { if (connection.contentEncoding == "gzip") GZIPInputStream(it) else it }
            // Read to the end and not disconnected: the connection stays open for the next try (no new TLS handshake).
            parse(stream.bufferedReader().use { it.readText() }).also { log.info("recognized {} - {} in {} ms", it.artist, it.title, System.currentTimeMillis() - now) }
        } catch (e: java.io.IOException) {
            connection.disconnect()
            log.warn("Shazam couldn't be reached: {}", e.message)
            throw ApiException(HttpStatusCode.BadGateway, ErrorCode.INVALID, "Shazam couldn't be reached")
        }
    }

    companion object {
        /** Shazam's answer: the track's title, artist, album, cover and ISRC; all null when it didn't know it. */
        fun parse(answer: String): RecognizeResponse {
            val track = Json.parseToJsonElement(answer).jsonObject["track"] as? JsonObject ?: return RecognizeResponse()
            fun JsonObject.text(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val album = (track["sections"] as? JsonArray)?.asSequence()
                ?.mapNotNull { (it as? JsonObject)?.get("metadata") as? JsonArray }
                ?.flatMap { it.asSequence() }
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.text("title") == "Album" }
                ?.text("text")
            val images = track["images"] as? JsonObject
            return RecognizeResponse(
                title = track.text("title") ?: return RecognizeResponse(),
                artist = track.text("subtitle"),
                album = album,
                art = images?.text("coverarthq") ?: images?.text("coverart"),
                isrc = track.text("isrc"),
            )
        }
    }
}
