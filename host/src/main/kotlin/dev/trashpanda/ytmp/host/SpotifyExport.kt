package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.ConvertItem
import dev.trashpanda.ytmp.protocol.ConvertItemType
import dev.trashpanda.ytmp.protocol.ConvertListKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream

/** A list read from an import (a playlist, the liked songs, …), in a reader's own words. */
@Serializable
data class ConvertList(val name: String, val kind: ConvertListKind, val items: List<ConvertItem>)

/** What a reader found: the lists, and how many rows weren't anything to import. */
data class ConvertRead(val reader: String, val lists: List<ConvertList>, val skipped: Int)

/** An import that can't be read, with a message for the person who picked it. */
class ConvertReadException(message: String) : Exception(message)

/**
 * Spotify's *Download your data → Account data* (docs/implementation/playlist-converter.md#spotify-data-export):
 * the zip, or one of its `Playlist1.json` / `YourLibrary.json`. Only those are read; the rest of
 * the zip (user data, streaming history) is skipped and nothing of it is kept.
 */
object SpotifyExport {
    const val READER = "spotify-export"

    fun read(input: InputStream): ConvertRead {
        val stream = BufferedInputStream(input)
        stream.mark(4)
        val first = stream.read()
        stream.reset()
        val files = if (first == '{'.code || first == '['.code) {
            listOf("Playlist1.json" to stream.readBytes())
        } else {
            readZip(stream)
        }
        if (files.isEmpty()) throw ConvertReadException(NOT_AN_EXPORT)
        val playlists = mutableListOf<ConvertList>()
        val library = mutableListOf<ConvertList>()
        var skipped = 0
        // Playlist1, Playlist2, … in order, then the library.
        for ((name, bytes) in files.sortedWith(compareBy({ it.first.endsWith(LIBRARY) }, { number(it.first) }))) {
            val root = runCatching { json.parseToJsonElement(bytes.decodeToString()) }.getOrNull() as? JsonObject
                ?: throw ConvertReadException("$name in the export can't be read: download it again")
            if (name.endsWith(LIBRARY)) {
                skipped += readLibrary(root, library)
            } else if (root.containsKey("playlists")) {
                skipped += readPlaylists(root, playlists)
            } else if (root.containsKey("tracks") || root.containsKey("shows")) {
                // YourLibrary.json picked on its own.
                skipped += readLibrary(root, library)
            }
        }
        val lists = playlists + library
        if (lists.isEmpty()) throw ConvertReadException("There are no playlists or liked songs in this export")
        return ConvertRead(READER, lists, skipped)
    }

    /** The files to read from a zip, by their name in it (without the folder). */
    private fun readZip(input: InputStream): List<Pair<String, ByteArray>> {
        val found = mutableListOf<Pair<String, ByteArray>>()
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val base = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && (PLAYLIST.matches(base) || base == LIBRARY)) {
                        val bytes = zip.readNBytes(MAX_FILE_BYTES + 1)
                        if (bytes.size > MAX_FILE_BYTES) throw ConvertReadException("$base in the export is too big")
                        found += base to bytes
                    }
                }
            }
        } catch (e: ConvertReadException) {
            throw e
        } catch (e: Exception) {
            if (found.isEmpty()) throw ConvertReadException(NOT_AN_EXPORT)
            // A zip cut short: what was read whole still counts.
        }
        return found
    }

    private fun number(name: String): Int = PLAYLIST.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** `{ playlists: [{ name, items: [{ track, episode, localTrack, … }] }] }`; the rows that were nothing. */
    private fun readPlaylists(root: JsonObject, into: MutableList<ConvertList>): Int {
        var skipped = 0
        for (p in root.array("playlists")) {
            val playlist = p as? JsonObject ?: continue
            val items = mutableListOf<ConvertItem>()
            for (row in playlist.array("items")) {
                val item = (row as? JsonObject)?.let(::playlistRow)
                if (item == null) skipped++ else items += item
            }
            val name = playlist.text("name") ?: "Playlist ${into.size + 1}"
            into += ConvertList(name, ConvertListKind.PLAYLIST, items)
        }
        return skipped
    }

    private fun playlistRow(row: JsonObject): ConvertItem? {
        row.obj("track")?.let { t ->
            val title = t.text("trackName") ?: return null
            return ConvertItem(
                ConvertItemType.SONG, title, listOfNotNull(t.text("artistName")), t.text("albumName"),
                sourceId = t.text("trackUri"),
            )
        }
        row.obj("episode")?.let { e ->
            val title = e.text("episodeName") ?: e.text("name") ?: return null
            val show = e.text("showName") ?: e.text("show")
            return ConvertItem(ConvertItemType.EPISODE, title, listOfNotNull(show), sourceId = e.text("episodeUri") ?: e.text("uri"), show = show)
        }
        row.obj("localTrack")?.text("uri")?.let { return localFile(it) }
        // An audiobook chapter, or a row Spotify exported blank.
        return null
    }

    /** `spotify:local:<artist>:<album>:<title>:<seconds>`, form-encoded: a file from the user's own disk. */
    internal fun localFile(uri: String): ConvertItem? {
        val fields = uri.removePrefix("spotify:local:").takeIf { it != uri }?.split(':') ?: return null
        if (fields.size < 4) return null
        val (artist, album, title) = fields.take(3).map { URLDecoder.decode(it, Charsets.UTF_8).trim() }
        if (title.isEmpty()) return null
        val seconds = fields[3].toLongOrNull()?.takeIf { it > 0 }
        return ConvertItem(ConvertItemType.LOCAL_FILE, title, listOf(artist).filter { it.isNotEmpty() }, album.ifEmpty { null }, seconds?.times(1000), sourceId = uri)
    }

    /**
     * `{ tracks: [{ artist, album, track, uri }], shows, episodes, … }`: *Liked songs*, the
     * followed shows and the saved episodes. Saved albums, followed artists and hidden songs
     * aren't imported (following and saving in bulk is what YouTube's spam filters look for).
     */
    private fun readLibrary(root: JsonObject, into: MutableList<ConvertList>): Int {
        var skipped = 0
        val liked = root.array("tracks").mapNotNull { row ->
            val t = row as? JsonObject
            val title = t?.text("track")
            if (t == null || title == null) {
                skipped++
                return@mapNotNull null
            }
            ConvertItem(ConvertItemType.SONG, title, listOfNotNull(t.text("artist")), t.text("album"), sourceId = t.text("uri"))
        }
        if (liked.isNotEmpty()) into += ConvertList("Liked songs", ConvertListKind.LIKED_SONGS, liked)
        val episodes = root.array("episodes").mapNotNull { row ->
            val e = row as? JsonObject ?: return@mapNotNull null
            val title = e.text("name") ?: e.text("episodeName") ?: return@mapNotNull null
            val show = e.text("show") ?: e.text("showName")
            ConvertItem(ConvertItemType.EPISODE, title, listOfNotNull(show), sourceId = e.text("uri") ?: e.text("episodeUri"), show = show)
        }
        if (episodes.isNotEmpty()) into += ConvertList("Saved episodes", ConvertListKind.SAVED_EPISODES, episodes)
        val shows = root.array("shows").mapNotNull { row ->
            val s = row as? JsonObject ?: return@mapNotNull null
            val name = s.text("name") ?: s.text("showName") ?: return@mapNotNull null
            ConvertItem(ConvertItemType.SHOW, name, listOfNotNull(s.text("publisher")), sourceId = s.text("uri") ?: s.text("showUri"))
        }
        if (shows.isNotEmpty()) into += ConvertList("Followed podcasts", ConvertListKind.FOLLOWED_SHOWS, shows)
        return skipped
    }

    private fun JsonObject.array(key: String): List<JsonElement> = (this[key] as? JsonArray).orEmpty()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.ifEmpty { null }

    private const val LIBRARY = "YourLibrary.json"
    private val PLAYLIST = Regex("Playlist(\\d*)\\.json")
    private const val MAX_FILE_BYTES = 200 * 1024 * 1024
    private const val NOT_AN_EXPORT = "This isn't a Spotify data export: pick the zip Spotify emailed (Account data), or Playlist1.json from it"
    private val json = Json { ignoreUnknownKeys = true }
}
