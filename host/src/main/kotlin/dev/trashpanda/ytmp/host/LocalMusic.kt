package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.CatalogSource
import dev.trashpanda.ytmp.core.RadioSource
import dev.trashpanda.ytmp.core.ResolvedStream
import dev.trashpanda.ytmp.core.StreamResolver
import dev.trashpanda.ytmp.protocol.AlbumPage
import dev.trashpanda.ytmp.protocol.AlbumRef
import dev.trashpanda.ytmp.protocol.AlbumSummary
import dev.trashpanda.ytmp.protocol.ArtistPage
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.LocalGroup
import dev.trashpanda.ytmp.protocol.LocalLibraries
import dev.trashpanda.ytmp.protocol.LocalLibraryKind
import dev.trashpanda.ytmp.protocol.LocalLibraryPage
import dev.trashpanda.ytmp.protocol.LocalLibrarySummary
import dev.trashpanda.ytmp.protocol.SearchItem
import dev.trashpanda.ytmp.protocol.Song
import dev.trashpanda.ytmp.protocol.Thumbnail
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Collections
import java.util.IdentityHashMap

/**
 * One song in a [LocalLibrary]. [key]: the library's own name for it (MPD: its path). [folder]:
 * where it is; an album is the songs with the same album name in the same folder. [genre]: ""
 * when it has none. [song]: a song with an ID of its own (the song cache's YouTube Music songs),
 * used as it is but for its covers; null: a song of this library (`local:…`).
 */
data class LocalTrack(
    val key: String,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val disc: Int?,
    val track: Int?,
    val year: String?,
    val durationMs: Long,
    val folder: String = "",
    val genre: String = "",
    val song: Song? = null,
    /** The file's name, when the library knows it (an ID may be in it). */
    val file: String = "",
)

/** Music the host reaches itself: an MPD's music folder ([MpdLibrary]), the music on the hosting phone. */
interface LocalLibrary {
    /** Short and without ":", part of every song's id. */
    val id: String
    val name: String
    val kind: LocalLibraryKind

    /** All its songs (the library may remember them a while). */
    suspend fun tracks(): List<LocalTrack>

    /** A link the devices on the network can play the song from. */
    fun fileUrl(key: String): String

    /** The song's cover, [size] px, or null. */
    fun artUrl(key: String, size: Int): String?

    /** Sends the song through the host (`/api/audio/…`), with [range]. */
    suspend fun respond(call: ApplicationCall, key: String, range: String?)

    /** Sends the song's cover, [size] px (`/api/local/art/…`), for a library whose [artUrl] points there; false when it has none. */
    suspend fun respondArt(call: ApplicationCall, key: String, size: Int): Boolean = false
}

/**
 * The host's local music: its libraries as songs, albums and artists, for the Local tab
 * (`/api/local`), album and artist pages, autoplay, and playing them (stream links and
 * `/api/audio`). Songs are `local:<library>:s:<key>`; the YouTube Music source handles the
 * rest, through the wrappers ([streams], [radio], [catalog], [audio]). See
 * docs/implementation/local-music.md.
 */
class LocalMusic {
    private val libraries = MutableStateFlow<Map<String, LocalLibrary>>(emptyMap())
    val all: StateFlow<Map<String, LocalLibrary>> = libraries

    /** Built from the tracks list, by that list (a library hands out the same list until it changes). */
    private val built = Collections.synchronizedMap(IdentityHashMap<List<LocalTrack>, Built>())

    fun add(library: LocalLibrary) {
        if (libraries.value[library.id] == library) return
        libraries.update { it + (library.id to library) }
        log.info("Local music: {} ({})", library.name, library.id)
    }

    fun remove(id: String) = libraries.update { it - id }

    suspend fun summaries(): LocalLibraries = LocalLibraries(
        libraries.value.values.mapNotNull { library ->
            runCatching { LocalLibrarySummary(library.id, library.name, library.kind, library.tracks().size) }
                .onFailure { log.warn("Local music {}: {}", library.name, it.message) }
                .getOrNull()
                // Nothing cached yet: not worth a place in the Local tab.
                ?.takeUnless { it.kind == LocalLibraryKind.CACHE && it.songCount == 0 }
        }.sortedBy { it.kind },
    )

    suspend fun page(id: String): LocalLibraryPage? {
        val library = libraries.value[id] ?: return null
        val b = build(library)
        val songs = b.songs.sortedBy { it.title.lowercase() }
        val place = songs.withIndex().associate { (i, song) -> song.id to i }
        // Genres and folders list their songs in album order, as places in [songs].
        fun groups(of: (LocalTrack) -> String) = b.songs.groupBy { of(b.trackOf.getValue(it.id)) }
            .map { (name, inIt) -> LocalGroup(name, inIt.map { place.getValue(it.id) }) }
        return LocalLibraryPage(
            library.id,
            library.name,
            library.kind,
            albums = b.albums.sortedBy { it.title.lowercase() }.map { SearchItem.AlbumResult(it.summary, listOf(ArtistRef(it.artistId, it.artist))) },
            artists = b.artists.map { SearchItem.ArtistResult(it.id, it.name, it.thumbnails) },
            songs = songs,
            // One genre whatever its spelling ("rock" and "Rock"), named as most of its songs have it.
            genres = groups { it.genre.lowercase() }.filter { it.name.isNotBlank() }
                .map { g -> g.copy(name = g.songs.map { b.trackOf.getValue(songs[it].id).genre }.groupingBy { it }.eachCount().maxBy { it.value }.key) }
                .sortedBy { it.name.lowercase() },
            folders = groups { it.folder.trim('/') }.sortedBy { it.name.lowercase() },
        )
    }

    /** Null when [id] isn't local music. */
    suspend fun album(id: String): AlbumPage? {
        val (library, kind, _) = parse(id) ?: return null
        if (kind != ALBUM) return null
        val b = build(library)
        val album = b.albumsById[id] ?: throw SourceException("That album isn't in ${library.name} any more")
        return AlbumPage(album.id, album.title, "Album", album.year, listOf(ArtistRef(album.artistId, album.artist)), album.summary.thumbnails, album.songs)
    }

    /** Null when [id] isn't local music. */
    suspend fun artist(id: String): ArtistPage? {
        val (library, kind, _) = parse(id) ?: return null
        if (kind != ARTIST) return null
        val artist = build(library).artistsById[id] ?: throw SourceException("That artist isn't in ${library.name} any more")
        return ArtistPage(artist.id, artist.name, artist.thumbnails, null, artist.songs, artist.albums.map { it.summary }, emptyList())
    }

    /**
     * Songs of the library like [seedId], for a radio from local music without YouTube Music or
     * with little in the song graph: the same artist, then the same album, then the same genre.
     * Never just any song: a radio isn't a shuffle. Null when [seedId] isn't local music.
     */
    suspend fun radio(seedId: String): List<Song>? {
        val (library, kind, _) = parse(seedId) ?: return null
        if (kind != SONG) return emptyList()
        val b = build(library)
        val seed = b.trackOf[seedId] ?: return emptyList()
        val others = b.songs.filter { it.id != seedId }
        fun track(s: Song) = b.trackOf.getValue(s.id)
        val sameArtist = others.filter { track(it).artist.equals(seed.artist, ignoreCase = true) }.shuffled().take(RADIO_SAME_ARTIST)
        val sameAlbum = others.filter { track(it).album.equals(seed.album, ignoreCase = true) && track(it).folder == seed.folder && seed.album.isNotBlank() }
        val sameGenre = if (seed.genre.isBlank()) emptyList() else others.filter { track(it).genre.equals(seed.genre, ignoreCase = true) }.shuffled()
        return (sameArtist + sameAlbum.shuffled() + sameGenre).distinctBy { it.id }.take(RADIO_SIZE)
    }

    /** The YouTube Music songs the local music here is (as far as its libraries say, see [ytmIdOf]). */
    suspend fun ytmSongIds(): List<String> = libraries.value.values.flatMap { library ->
        runCatching { library.tracks() }.getOrDefault(emptyList()).mapNotNull { t ->
            t.song?.id?.takeIf { it.startsWith("ytm:") } ?: LocalMatcher.idInName(t.file)?.let { "ytm:$it" }
        }
    }.distinct()

    /** All the songs of library [libraryId] (none when it isn't here). */
    suspend fun songs(libraryId: String): List<Song> = libraries.value[libraryId]?.let { build(it).songs }.orEmpty()

    /** The library's track of local song [songId], or null. */
    suspend fun track(songId: String): LocalTrack? {
        val (library, kind, _) = runCatching { parse(songId) }.getOrNull() ?: return null
        if (kind != SONG) return null
        return build(library).trackOf[songId]
    }

    /** The YouTube Music song local song [songId] is, as far as its library says (a saved song, or an ID in its file name). */
    suspend fun ytmIdOf(songId: String): String? {
        val track = track(songId) ?: return null
        return track.song?.id?.takeIf { it.startsWith("ytm:") } ?: LocalMatcher.idInName(track.file)?.let { "ytm:$it" }
    }

    /** Whether [songId] can play here: not local music, or local music of a library this host has (the song graph knows songs of other hosts' too). */
    fun playable(songId: String): Boolean = !isLocal(songId) || runCatching { parse(songId) }.getOrNull() != null

    /** The stream link of a local song, or null when [songId] isn't local music. */
    suspend fun stream(songId: String): ResolvedStream? {
        val (library, kind, key) = parse(songId) ?: return null
        if (kind != SONG) throw SourceException("Not a song")
        return ResolvedStream(library.fileUrl(key), durationMs = null)
    }

    /** Sends a local song through the host; false when [songId] isn't local music. */
    suspend fun respond(call: ApplicationCall, songId: String, range: String?): Boolean {
        val (library, kind, key) = parse(songId) ?: return false
        if (kind != SONG) throw SourceException("Not a song")
        library.respond(call, key, range)
        return true
    }

    /** Sends a local song's cover; false when it has none here. */
    suspend fun respondArt(call: ApplicationCall, songId: String, size: Int): Boolean {
        val (library, kind, key) = parse(songId) ?: return false
        return kind == SONG && library.respondArt(call, key, size.coerceIn(32, 1024))
    }

    // --- in front of the YouTube Music source ---

    fun streams(inner: StreamResolver): StreamResolver = object : StreamResolver {
        override suspend fun resolveStream(songId: String) = stream(songId)?.url ?: inner.resolveStream(songId)

        override suspend fun resolve(songId: String) = stream(songId) ?: inner.resolve(songId)
    }

    fun radio(inner: RadioSource): RadioSource = RadioSource { seed -> radio(seed) ?: inner.radio(seed) }

    fun catalog(inner: CatalogSource): CatalogSource = object : CatalogSource by inner {
        override suspend fun album(id: String): AlbumPage = this@LocalMusic.album(id) ?: inner.album(id)

        override suspend fun artist(id: String): ArtistPage = this@LocalMusic.artist(id) ?: inner.artist(id)
    }

    fun audio(inner: AudioProxy?): AudioProxy = AudioProxy { call, songId, range ->
        if (!respond(call, songId, range)) inner?.respond(call, songId, range) ?: call.respond(HttpStatusCode.NotFound)
    }

    // --- songs, albums and artists from the tracks ---

    private class Album(val id: String, val title: String, val artist: String, val artistId: String, val year: String?, val songs: List<Song>, val summary: AlbumSummary)

    private class Artist(val id: String, val name: String, val thumbnails: List<Thumbnail>, val albums: List<Album>, val songs: List<Song>)

    private class Built(
        val songs: List<Song>,
        val trackOf: Map<String, LocalTrack>,
        val albums: List<Album>,
        val albumsById: Map<String, Album>,
        val artists: List<Artist>,
        val artistsById: Map<String, Artist>,
    )

    private suspend fun build(library: LocalLibrary): Built {
        val tracks = library.tracks()
        built[tracks]?.let { return it }
        val lib = library.id
        val ordered = withoutCopies(tracks).sortedWith(compareBy<LocalTrack>({ it.albumArtist.lowercase() }, { it.album.lowercase() }, { it.disc ?: 0 }, { it.track ?: 0 }, { it.title.lowercase() }))
        val trackOf = HashMap<String, LocalTrack>()
        val songOf = HashMap<LocalTrack, Song>()
        // The song cache's songs are in folders that say what they are (Downloaded, Cached), not where.
        val folderOf = { t: LocalTrack -> if (library.kind == LocalLibraryKind.CACHE) "" else t.folder }
        for (t in ordered) {
            val song = t.song?.copy(thumbnails = art(library, t.key)) ?: Song(
                id = songId(lib, t.key),
                title = t.title,
                artists = listOf(ArtistRef(artistId(lib, t.artist), t.artist)),
                album = t.album.takeIf { it.isNotEmpty() }?.let { AlbumRef(albumId(lib, t.folder, it), it) },
                durationMs = t.durationMs,
                thumbnails = art(library, t.key),
            )
            val id = song.id
            trackOf[id] = t
            songOf[t] = song
        }
        val albums = ordered.filter { it.album.isNotEmpty() }.groupBy { folderOf(it) to it.album.lowercase() }.values.map { group ->
            val sorted = group.sortedWith(compareBy<LocalTrack>({ it.disc ?: 0 }, { it.track ?: 0 }, { it.key }))
            val first = sorted.first()
            val id = albumId(lib, folderOf(first), first.album)
            // The album artist; songs by several artists without one: a compilation.
            val artist = sorted.map { it.albumArtist }.distinctBy { it.lowercase() }.singleOrNull() ?: VARIOUS_ARTISTS
            val year = sorted.firstNotNullOfOrNull { it.year }
            Album(id, first.album, artist, artistId(lib, artist), year, sorted.map { songOf.getValue(it) }, AlbumSummary(id, first.album, "Album", year, art(library, first.key)))
        }
        val names = (ordered.map { it.albumArtist } + ordered.map { it.artist }).filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        val artists = names.sortedBy { it.lowercase() }.map { name ->
            val own = albums.filter { it.artist.equals(name, ignoreCase = true) }
            val songs = ordered.filter { it.artist.equals(name, ignoreCase = true) || it.albumArtist.equals(name, ignoreCase = true) }.map { songOf.getValue(it) }
            Artist(artistId(lib, name), name, own.firstOrNull()?.summary?.thumbnails ?: songs.firstOrNull()?.thumbnails.orEmpty(), own, songs)
        }.filter { it.songs.isNotEmpty() }
        val b = Built(ordered.map { songOf.getValue(it) }, trackOf, albums, albums.associateBy { it.id }, artists, artists.associateBy { it.id })
        synchronized(built) {
            // Only the latest list of each library is worth keeping.
            if (built.size > 8) built.clear()
            built[tracks] = b
        }
        return b
    }

    /**
     * One of each song: a music folder often has copies (the same album in two places). A copy
     * has the same artist, album, disc, track and title and about the same length; the one with
     * the shortest path stays.
     */
    private fun withoutCopies(tracks: List<LocalTrack>): List<LocalTrack> =
        tracks.sortedWith(compareBy({ it.key.length }, { it.key }))
            .distinctBy { listOf(it.albumArtist.lowercase(), it.album.lowercase(), it.disc, it.track, it.title.lowercase(), (it.durationMs + 1_000) / 2_000) }

    /** The cover in a few sizes (rows, cards, the player), so pages load small ones where they can. */
    private fun art(library: LocalLibrary, key: String): List<Thumbnail> =
        ART_SIZES.mapNotNull { size -> library.artUrl(key, size)?.let { Thumbnail(it, size, size) } }

    private fun parse(id: String): Triple<LocalLibrary, String, String>? {
        if (!id.startsWith(PREFIX)) return null
        val parts = id.removePrefix(PREFIX).split(':', limit = 3)
        if (parts.size < 3) throw SourceException("Not local music: $id")
        val library = libraries.value[parts[0]] ?: throw SourceException("That music isn't on the network now")
        return Triple(library, parts[1], parts[2])
    }

    companion object {
        private val log = LoggerFactory.getLogger(LocalMusic::class.java)
        const val PREFIX = "local:"
        private const val SONG = "s"
        private const val ALBUM = "a"
        private const val ARTIST = "r"
        private val ART_SIZES = listOf(120, 400, 544)
        private const val VARIOUS_ARTISTS = "Various Artists"
        private const val RADIO_SIZE = 50
        private const val RADIO_SAME_ARTIST = 15

        fun isLocal(id: String) = id.startsWith(PREFIX)

        fun songId(library: String, key: String) = "$PREFIX$library:$SONG:$key"

        fun albumId(library: String, folder: String, album: String) = "$PREFIX$library:$ALBUM:${encode(folder)}/${encode(album.lowercase())}"

        fun artistId(library: String, name: String) = "$PREFIX$library:$ARTIST:${encode(name.lowercase())}"

        /** A library id from anything (a host and port): letters, digits and "-". */
        fun libraryId(vararg parts: Any) = parts.joinToString("-") { it.toString() }.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')

        internal fun encode(text: String) = URLEncoder.encode(text, "UTF-8")

        internal fun decode(text: String) = URLDecoder.decode(text, "UTF-8")
    }
}

/** Passes a file from [url] (a web server on the network, with ranges) on to [call]. */
internal suspend fun proxyHttp(call: ApplicationCall, url: String, range: String?) {
    val connection = withContext(Dispatchers.IO) {
        (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 30_000
            if (range != null) setRequestProperty("Range", range)
            connect()
        }
    }
    val code = withContext(Dispatchers.IO) { connection.responseCode }
    if (code !in 200..299) {
        connection.disconnect()
        throw SourceException("The music server answered $code")
    }
    call.response.header(HttpHeaders.AcceptRanges, "bytes")
    connection.getHeaderField("Content-Range")?.let { call.response.header(HttpHeaders.ContentRange, it) }
    call.respondOutputStream(
        contentType = connection.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() },
        status = HttpStatusCode.fromValue(code),
        contentLength = connection.contentLengthLong.takeIf { it >= 0 },
    ) {
        try {
            connection.inputStream.use { it.copyTo(this) }
        } catch (e: java.io.IOException) {
            // Usually the listener stopped or skipped.
        } finally {
            connection.disconnect()
        }
    }
}
