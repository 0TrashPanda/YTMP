package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.LyricsSource
import dev.trashpanda.ytmp.protocol.LyricLine
import dev.trashpanda.ytmp.protocol.Lyrics
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Lyrics for any song (docs/features/ui.md#lyrics): [LRCLIB](https://lrclib.net) first (free,
 * often timed), looked up by title, artist, album and length; then the song's own source
 * ([fallback], YouTube Music's). Timed lyrics win over plain ones. Kept for a while, also
 * when a song has none.
 */
class LyricsLookup(
    private val fallback: LyricsSource?,
    private val lrclib: LyricsSource = Lrclib(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val cache = object : LinkedHashMap<String, Pair<Long, Lyrics?>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Lyrics?>>) = size > CACHE_SIZE
    }

    /** Null when nobody has lyrics for it (or it's a podcast episode). */
    suspend fun lyrics(song: Song): Lyrics? {
        if (song.podcast != null) return null
        synchronized(cache) { cache[song.id]?.takeIf { clock() - it.first < CACHE_MS } }?.let { return it.second }
        var failed = false
        suspend fun from(source: LyricsSource?): Lyrics? = try {
            source?.lyrics(song)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            log.info("Lyrics for {} \"{}\": {}", song.id, song.title, e.message)
            null
        }
        val fromLrclib = from(lrclib)
        val found = fromLrclib?.takeIf { it.synced != null } ?: from(fallback).let { own -> own?.takeIf { it.synced != null } ?: fromLrclib ?: own }
        // Not found because a lookup failed (no internet): ask again next time.
        if (found != null || !failed) synchronized(cache) { cache[song.id] = clock() to found }
        return found
    }

    companion object {
        private val log = LoggerFactory.getLogger(LyricsLookup::class.java)
        private const val CACHE_SIZE = 300
        private const val CACHE_MS = 6 * 60 * 60 * 1000L
    }
}

/** LRCLIB's API: an exact match by title, artist, album and length, else a search by title and artist. */
class Lrclib(private val baseUrl: String = "https://lrclib.net") : LyricsSource {
    override suspend fun lyrics(song: Song): Lyrics? = withContext(Dispatchers.IO) {
        val artist = song.artists.firstOrNull()?.name ?: return@withContext null
        val seconds = song.durationMs / 1000.0
        val exact = get(
            "/api/get",
            listOfNotNull("track_name" to song.title, "artist_name" to artist, song.album?.let { "album_name" to it.name }, "duration" to seconds.roundToLong().toString()),
        ) as? JsonObject
        exact?.let(::record)?.let { return@withContext it }
        // Titles and albums don't always match exactly ("… (Remastered)", a single's album):
        // search, and take one of about the same length, timed ones first.
        val results = get("/api/search", listOf("track_name" to plainTitle(song.title), "artist_name" to artist)) as? JsonArray ?: return@withContext null
        results.filterIsInstance<JsonObject>()
            .filter { song.durationMs <= 0 || abs((it.number("duration") ?: 0.0) - seconds) <= MAX_LENGTH_DIFFERENCE_S }
            .sortedBy { if (it.text("syncedLyrics") != null) 0 else 1 }
            .firstNotNullOfOrNull(::record)
    }

    private fun record(json: JsonObject): Lyrics? {
        if (json["instrumental"]?.jsonPrimitive?.booleanOrNull == true) return null
        val synced = json.text("syncedLyrics")?.let(::parseLrc)?.takeIf { it.isNotEmpty() }
        val plain = json.text("plainLyrics") ?: synced?.joinToString("\n") { it.text } ?: return null
        return Lyrics(plain = plain, synced = synced, source = "LRCLIB")
    }

    /** The parsed JSON, or null when LRCLIB doesn't have it (404). */
    private fun get(path: String, query: List<Pair<String, String>>): JsonElement? {
        val url = baseUrl + path + "?" + query.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            // LRCLIB asks apps to say who they are.
            connection.setRequestProperty("User-Agent", "YTMP (https://github.com/0TrashPanda/YTMP)")
            return when (val status = connection.responseCode) {
                200 -> ProtocolJson.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() })
                404 -> null
                else -> throw IOException("LRCLIB answered $status")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(key: String) = this[key]?.jsonPrimitive?.doubleOrNull

    companion object {
        private const val TIMEOUT_MS = 8_000
        private const val MAX_LENGTH_DIFFERENCE_S = 3.0
        private val BRACKETS = Regex("""\s*[(\[](?:[^)\]]*?(?:official|video|audio|lyrics?|visuali[sz]er|remaster(?:ed)?|explicit|radio edit|single edit)[^)\]]*)[)\]]""", RegexOption.IGNORE_CASE)
        private val TIME = Regex("""\[(\d+):(\d{1,2}(?:[.,]\d+)?)]""")

        /** "One More Time (Official Video)" -> "One More Time". */
        fun plainTitle(title: String) = title.replace(BRACKETS, "").trim().ifEmpty { title }

        /** LRC: `[mm:ss.xx] text` lines (a line may have several times); other tags are skipped. */
        fun parseLrc(lrc: String): List<LyricLine> = lrc.lineSequence().flatMap { line ->
            val times = TIME.findAll(line).toList()
            if (times.isEmpty() || !line.trimStart().startsWith("[")) return@flatMap emptySequence()
            val text = line.substring(times.last().range.last + 1).trim()
            times.asSequence().map { m ->
                val ms = m.groupValues[1].toLong() * 60_000 + (m.groupValues[2].replace(',', '.').toDouble() * 1000).roundToLong()
                LyricLine(ms, text)
            }
        }.sortedBy { it.timeMs }.toList()
    }
}
