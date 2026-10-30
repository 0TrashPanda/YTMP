package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.ConvertItem
import dev.trashpanda.ytmp.protocol.ConvertItemType
import dev.trashpanda.ytmp.protocol.ConvertListKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * A public Spotify playlist or album by its link (docs/implementation/playlist-converter.md#a-spotify-link):
 * read from Spotify's embed page (`open.spotify.com/embed/…`), which anyone can open without an
 * account and which has the list as JSON. It shows at most [EMBED_MAX] songs.
 */
object SpotifyLink {
    const val READER = "spotify-link"

    /** Spotify's embed page shows at most this many songs of a list. */
    const val EMBED_MAX = 100

    private val LINK = Regex("""(?:open\.spotify\.com/(?:intl-[a-z-]+/)?(?:embed/)?|spotify:)(playlist|album)[/:]([A-Za-z0-9]{16,32})""")

    /** The kind (playlist, album) and ID in a Spotify link or URI, or null. */
    fun parse(link: String): Pair<String, String>? = LINK.find(link.trim())?.let { it.groupValues[1] to it.groupValues[2] }

    /** Reads the list at [link]; [download] fetches a page's HTML. */
    fun read(link: String, download: (String) -> String = ::download): ConvertRead {
        val (kind, id) = parse(link) ?: throw ConvertReadException("That isn't a link to a Spotify playlist or album")
        val html = try {
            download("https://open.spotify.com/embed/$kind/$id")
        } catch (e: NotPublic) {
            throw ConvertReadException("Spotify doesn't show that $kind: it's private, or gone")
        } catch (e: IOException) {
            throw ConvertReadException("Couldn't reach Spotify: ${e.message}")
        }
        return parsePage(html, kind)
    }

    internal fun parsePage(html: String, kind: String): ConvertRead {
        val start = html.indexOf(DATA_START).takeIf { it >= 0 }?.plus(DATA_START.length)
        val end = start?.let { html.indexOf("</script>", it) }?.takeIf { it > 0 }
        val data = if (start != null && end != null) runCatching { json.parseToJsonElement(html.substring(start, end)) }.getOrNull() else null
        val entity = data?.at("props", "pageProps", "state", "data", "entity") as? JsonObject
            ?: throw ConvertReadException("Spotify's page for that $kind changed, and YTMP can't read it any more")
        val name = entity.text("name") ?: entity.text("title") ?: "Spotify $kind"
        var skipped = 0
        val items = (entity["trackList"] as? JsonArray).orEmpty().mapNotNull { row ->
            val t = row as? JsonObject
            val title = t?.text("title")
            if (t == null || title == null) {
                skipped++
                return@mapNotNull null
            }
            val by = t.text("subtitle")
            val ms = (t["duration"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 }
            when (t.text("entityType") ?: "track") {
                "track" -> ConvertItem(
                    ConvertItemType.SONG, title, by?.split(", ")?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                    album = name.takeIf { kind == "album" }, durationMs = ms, sourceId = t.text("uri"),
                )
                "episode" -> ConvertItem(ConvertItemType.EPISODE, title, listOfNotNull(by), durationMs = ms, sourceId = t.text("uri"), show = by)
                else -> {
                    skipped++
                    null
                }
            }
        }
        if (items.isEmpty()) throw ConvertReadException("There are no songs in that $kind")
        return ConvertRead(READER, listOf(ConvertList(name, ConvertListKind.PLAYLIST, items, truncated = items.size + skipped >= EMBED_MAX)), skipped)
    }

    /** Spotify answered that there's no such public page. */
    private class NotPublic : IOException()

    /** The HTML of [url], as a browser gets it. */
    fun download(url: String): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36")
            connection.setRequestProperty("Accept-Language", "en")
            return when (val status = connection.responseCode) {
                200 -> connection.inputStream.bufferedReader().use { it.readText() }
                400, 404 -> throw NotPublic()
                else -> throw IOException("Spotify answered $status")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun JsonElement.at(vararg keys: String): JsonElement? = keys.fold(this as JsonElement?) { e, k -> (e as? JsonObject)?.get(k) }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.ifEmpty { null }

    private const val DATA_START = """<script id="__NEXT_DATA__" type="application/json">"""
    private const val TIMEOUT_MS = 10_000
    private val json = Json { ignoreUnknownKeys = true }
}
