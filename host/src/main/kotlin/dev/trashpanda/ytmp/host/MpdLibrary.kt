package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.LocalLibraryKind
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An MPD's music as a [LocalLibrary]: the songs from MPD's database, the files from a web server
 * that shares its music folder ([filesBase], e.g. `http://pi:8766/music/`, with ranges), the
 * covers from [artBase] (`…/art/`). See deploy/zones/ytmp-share.py.
 */
class MpdLibrary(
    override val id: String,
    override val name: String,
    private val host: String,
    private val port: Int,
    private val filesBase: String,
    private val artBase: String?,
) : LocalLibrary {
    override val kind = LocalLibraryKind.MPD

    private val mutex = Mutex()
    private var tracks: List<LocalTrack>? = null
    private var databaseVersion: String? = null
    private var checkedAt = 0L

    /** MPD's songs; read again when its database changed (checked at most every [CHECK_MS]). */
    override suspend fun tracks(): List<LocalTrack> = mutex.withLock {
        val now = System.currentTimeMillis()
        tracks?.let { if (now - checkedAt < CHECK_MS) return it }
        val player = MpdPlayer(host, port)
        val version = player.stats()["db_update"]
        val known = tracks
        if (known != null && version == databaseVersion) {
            checkedAt = now
            return known
        }
        val read = player.songs().mapNotNull(::track)
        tracks = read
        databaseVersion = version
        checkedAt = now
        read
    }

    override fun fileUrl(key: String) = filesBase + path(key)

    override fun artUrl(key: String, size: Int) = artBase?.let { it + path(key) + "?size=$size" }

    override suspend fun respond(call: ApplicationCall, key: String, range: String?) = proxyHttp(call, fileUrl(key), range)

    override fun equals(other: Any?) = other is MpdLibrary && other.id == id && other.name == name && other.filesBase == filesBase && other.artBase == artBase

    override fun hashCode() = id.hashCode()

    companion object {
        private const val CHECK_MS = 60_000L

        private fun path(key: String) = key.split('/').joinToString("/") { LocalMusic.encode(it).replace("+", "%20") }

        /** A song from MPD's tags; the file name when it has no title. */
        internal fun track(tags: Map<String, String>): LocalTrack? {
            val file = tags["file"] ?: return null
            // Streams (http://…) saved in MPD's database aren't music files.
            if ("://" in file) return null
            val artist = tags["Artist"]?.trim().orEmpty().ifEmpty { tags["AlbumArtist"]?.trim().orEmpty() }.ifEmpty { "Unknown artist" }
            return LocalTrack(
                key = file,
                title = tags["Title"]?.trim()?.ifEmpty { null } ?: file.substringAfterLast('/').substringBeforeLast('.'),
                artist = artist,
                albumArtist = tags["AlbumArtist"]?.trim()?.ifEmpty { null } ?: artist,
                album = tags["Album"]?.trim().orEmpty(),
                disc = tags["Disc"]?.substringBefore('/')?.trim()?.toIntOrNull(),
                track = tags["Track"]?.substringBefore('/')?.trim()?.toIntOrNull(),
                year = tags["Date"]?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) },
                durationMs = (tags["duration"]?.toDoubleOrNull() ?: tags["Time"]?.toDoubleOrNull() ?: 0.0).let { Math.round(it * 1000) },
                folder = file.substringBeforeLast('/', ""),
            )
        }
    }
}
