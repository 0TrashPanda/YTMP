package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import kotlin.math.abs

/**
 * A song's genre (docs/implementation/sources/genres.md). YouTube Music has none, so it's asked
 * elsewhere by artist and title.
 */
fun interface GenreLookup {
    /**
     * The genre, "" when the song wasn't found or has none. Throws [IOException] when it
     * couldn't ask (offline, too many lookups): try again later.
     */
    suspend fun genre(song: Song): String

    companion object {
        /** Apple's music catalog first (a genre per song), Deezer's when Apple doesn't have it (the album's genre). */
        fun online(): GenreLookup = OnlineGenres()
    }
}

/** The server's list of songs' genres, shared by the owner's devices (`POST /api/auth/genres`). */
interface SharedGenres {
    /** What the list has of [songIds] ("": none found); unknown ones are left out. */
    fun knownGenres(songIds: Collection<String>): Map<String, String>

    /** Genres a device looked up; the list keeps the ones it doesn't have yet. */
    fun addGenres(found: Map<String, String>)
}

/**
 * The owner's server's list, for a device (the phone): sends what the device [found] and
 * answers what the list has of [wanted]; null when it can't be asked now.
 */
fun interface GenreShare {
    suspend fun exchange(found: Map<String, String>, wanted: List<String>): Map<String, String>?
}

internal class OnlineGenres(
    private val get: suspend (String) -> String = { url -> withContext(Dispatchers.IO) { fetch(url) } },
) : GenreLookup {
    override suspend fun genre(song: Song): String {
        if (song.podcast != null) return PODCAST
        val artist = song.artists.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: return ""
        val title = song.title.takeIf { it.isNotBlank() } ?: return ""
        return apple(song, artist, title) ?: deezer(song, artist, title) ?: ""
    }

    private suspend fun apple(song: Song, artist: String, title: String): String? {
        val term = enc("$artist ${clean(title)}")
        val results = json(get("https://itunes.apple.com/search?term=$term&media=music&entity=song&limit=15"))
            .obj()?.get("results")?.arr().orEmpty()
        val hit = best(song, artist, title, results.mapNotNull { it.obj() }) { o ->
            Candidate(o.str("trackName"), o.str("artistName"), o["trackTimeMillis"]?.jsonPrimitive?.longOrNull)
        } ?: return null
        return hit.str("primaryGenreName").takeIf { it.isNotBlank() && it !in NOT_GENRES }
    }

    private suspend fun deezer(song: Song, artist: String, title: String): String? {
        val q = enc("$artist ${clean(title)}")
        val root = json(get("https://api.deezer.com/search?q=$q&limit=15")).obj()
        root?.get("error")?.let { throw IOException("Deezer: $it") }
        val results = root?.get("data")?.arr().orEmpty().mapNotNull { it.obj() }
        val hit = best(song, artist, title, results) { o ->
            Candidate(o.str("title_short").ifEmpty { o.str("title") }, o["artist"]?.obj()?.str("name").orEmpty(), o["duration"]?.jsonPrimitive?.longOrNull?.let { it * 1000 })
        } ?: return null
        val albumId = hit["album"]?.obj()?.get("id")?.jsonPrimitive?.longOrNull ?: return null
        val album = json(get("https://api.deezer.com/album/$albumId")).obj() ?: return null
        album["error"]?.let { throw IOException("Deezer: $it") }
        return album["genres"]?.obj()?.get("data")?.arr()?.firstOrNull()?.obj()?.str("name")
            ?.takeIf { it.isNotBlank() && it !in NOT_GENRES }
    }

    private class Candidate(val title: String, val artist: String, val durationMs: Long?)

    /**
     * The result that is this song: the same artist and title; of those the closest in length
     * (another edit of the song, a store's album version, has the same genre).
     */
    private fun best(song: Song, artist: String, title: String, results: List<JsonObject>, read: (JsonObject) -> Candidate): JsonObject? {
        val wantTitle = norm(clean(title)).ifEmpty { return null }
        val wantArtists = song.artists.map { norm(it.name) }.filter { it.isNotEmpty() }.ifEmpty { listOf(norm(artist)) }
        return results.map { it to read(it) }
            .filter { (_, c) ->
                val t = norm(clean(c.title))
                val a = norm(c.artist)
                (t == wantTitle || (wantTitle.length >= 4 && (t.startsWith(wantTitle) || wantTitle.startsWith(t)) && t.isNotEmpty())) &&
                    wantArtists.any { w -> a.contains(w) || w.contains(a) && a.isNotEmpty() }
            }
            .minByOrNull { (_, c) ->
                val off = if (song.durationMs > 0 && c.durationMs != null) abs(c.durationMs - song.durationMs) else LENGTH_UNKNOWN
                (if (norm(clean(c.title)) == wantTitle) 0L else INEXACT) + off
            }
            ?.first
    }

    companion object {
        private val parser = Json { ignoreUnknownKeys = true }
        const val PODCAST = "Podcast"
        /** Not genres: what a store calls everything. */
        private val NOT_GENRES = setOf("Music", "Musique", "Musik")
        private const val LENGTH_UNKNOWN = 10_000L
        private const val INEXACT = 30_000L
        private const val TIMEOUT_MS = 10_000

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** A title without what stores write differently: "(feat. …)", "[Remastered]", " - Radio Edit". */
        internal fun clean(title: String): String =
            title.replace(Regex("""\s*[(\[][^)\]]*[)\]]"""), "")
                .replace(Regex("""\s+-\s+.*$"""), "")
                .replace(Regex("""\s+(feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifEmpty { title.trim() }

        /** Lower case letters and digits only, without accents ("Beyoncé" = "beyonce"). */
        internal fun norm(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
                .replace("&", "and")
                .replace(Regex("[^\\p{L}\\p{N}]|\\p{M}"), "")

        private fun json(text: String): JsonElement = parser.parseToJsonElement(text)
        private fun JsonElement.obj() = this as? JsonObject
        private fun JsonElement.arr() = this as? JsonArray
        private fun JsonObject.str(key: String) = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

        private fun fetch(url: String): String {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "YTMP")
            try {
                val code = connection.responseCode
                if (code != 200) throw IOException("HTTP $code from ${URI(url).host}")
                return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                connection.disconnect()
            }
        }
    }
}
