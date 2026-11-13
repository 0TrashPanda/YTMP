package dev.trashpanda.ytmp.desktop

import dev.trashpanda.ytmp.host.LocalLibrary
import dev.trashpanda.ytmp.host.LocalMusic
import dev.trashpanda.ytmp.host.LocalTrack
import dev.trashpanda.ytmp.host.SongCache
import dev.trashpanda.ytmp.protocol.LocalLibraryKind
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The music in the folders picked on this computer (Settings → Local music → Music on this
 * computer), as a [LocalLibrary]: `local:dev-<device>:s:<key>`, the key from the file's path.
 * Tags are read once per file (by its modified time and size) and kept in [indexFile], so the
 * Local tab opens at once; the folders are looked through again in the background now and then.
 */
class ComputerLibrary(
    device: String,
    private val indexFile: File,
    /** Where the window reaches this host (`ytmphost://…`), for the covers' links. */
    private val windowBase: String,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : LocalLibrary {
    override val id = "dev-$device"
    override val name = "On this computer"
    override val kind = LocalLibraryKind.COMPUTER

    @Serializable
    private data class Entry(
        val path: String,
        val modified: Long,
        val size: Long,
        val title: String,
        val artist: String,
        val albumArtist: String,
        val album: String,
        val disc: Int? = null,
        val track: Int? = null,
        val year: String? = null,
        val durationMs: Long,
        val genre: String = "",
        val folder: String,
        val art: Boolean = false,
    )

    @Volatile var folders: List<File> = emptyList()
        private set
    @Volatile private var entries: Map<String, Entry> = emptyMap()
    @Volatile private var tracks: List<LocalTrack> = emptyList()
    @Volatile private var scannedAt = 0L
    private val scanLock = Mutex()
    @Volatile private var scan: Job? = null

    /** Looking through the folders now. */
    val scanning get() = scan?.isActive == true

    init {
        Logger.getLogger("org.jaudiotagger").level = Level.OFF
        val saved = runCatching { json.decodeFromString<List<Entry>>(indexFile.readText()) }.getOrDefault(emptyList())
        entries = saved.associateBy { key(it.path) }
    }

    /** The picked folders changed: only songs in them show, and they're looked through again. */
    fun setFolders(folders: List<File>) {
        this.folders = folders
        publish(entries)
        rescan()
    }

    /** Looks through the folders again (in the background); [wait]: until it's done. */
    suspend fun rescan(wait: Boolean = false) {
        val job = synchronized(this) {
            scan?.takeIf { it.isActive } ?: scope.launch(Dispatchers.IO) { scanNow() }.also { scan = it }
        }
        if (wait) job.join()
    }

    private fun rescan() {
        scope.launch { rescan(wait = false) }
    }

    override suspend fun tracks(): List<LocalTrack> {
        if (clock() - scannedAt > RESCAN_MS) rescan()
        return tracks
    }

    private suspend fun scanNow() = scanLock.withLock {
        val started = System.nanoTime()
        val found = HashMap<String, Entry>()
        var read = 0
        for (root in folders) {
            root.walkTopDown().onEnter { !it.name.startsWith(".") }.filter { it.isFile && it.extension.lowercase() in TYPES }.forEach { file ->
                val path = file.absolutePath
                val old = entries[key(path)]
                if (old != null && old.modified == file.lastModified() && old.size == file.length()) {
                    found[key(path)] = old
                } else {
                    read++
                    found[key(path)] = readTags(file, root)
                }
            }
        }
        publish(found)
        scannedAt = clock()
        withContext(Dispatchers.IO) {
            indexFile.parentFile?.mkdirs()
            indexFile.writeText(json.encodeToString(found.values.toList()))
        }
        log.info("Music on this computer: {} songs in {} folders ({} read) in {} ms", tracks.size, folders.size, read, (System.nanoTime() - started) / 1_000_000)
    }

    /** Only songs inside the picked folders count (a removed folder's songs go at once). */
    private fun publish(all: Map<String, Entry>) {
        entries = all
        val roots = folders.map { it.absolutePath + File.separator }
        tracks = all.filter { (_, e) -> roots.any { e.path.startsWith(it) } }.map { (key, e) ->
            LocalTrack(key, e.title, e.artist, e.albumArtist, e.album, e.disc, e.track, e.year, e.durationMs, e.folder, e.genre, file = File(e.path).name)
        }
    }

    private fun readTags(file: File, root: File): Entry {
        val relative = file.parentFile.relativeTo(root).invariantSeparatorsPath
        val folder = if (relative.isEmpty()) root.name else "${root.name}/$relative"
        val audio = runCatching { AudioFileIO.read(file) }.getOrNull()
        val tag = audio?.tag
        fun field(key: FieldKey) = runCatching { tag?.getFirst(key) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        val artist = field(FieldKey.ARTIST) ?: field(FieldKey.ALBUM_ARTIST) ?: "Unknown artist"
        return Entry(
            path = file.absolutePath,
            modified = file.lastModified(),
            size = file.length(),
            title = field(FieldKey.TITLE) ?: file.nameWithoutExtension,
            artist = artist,
            albumArtist = field(FieldKey.ALBUM_ARTIST) ?: artist,
            album = field(FieldKey.ALBUM).orEmpty(),
            disc = number(field(FieldKey.DISC_NO)),
            track = number(field(FieldKey.TRACK)),
            year = field(FieldKey.YEAR)?.take(4),
            durationMs = ((audio?.audioHeader?.preciseTrackLength ?: 0.0) * 1000).toLong(),
            genre = field(FieldKey.GENRE).orEmpty(),
            folder = folder,
            art = runCatching { tag?.firstArtwork?.binaryData?.isNotEmpty() }.getOrNull() == true || folderCover(file) != null,
        )
    }

    private fun songId(key: String) = LocalMusic.songId(id, key)

    private fun file(key: String): File? = entries[key]?.path?.let(::File)?.takeIf { it.isFile }

    override fun fileUrl(key: String) = "$windowBase/api/audio/${URLEncoder.encode(songId(key), "UTF-8")}"

    override fun artUrl(key: String, size: Int): String? =
        if (entries[key]?.art == true) "$windowBase/api/local/art/${URLEncoder.encode(songId(key), "UTF-8")}?size=$size" else null

    override suspend fun respond(call: ApplicationCall, key: String, range: String?) {
        val file = file(key) ?: throw dev.trashpanda.ytmp.host.SourceException("That file isn't on this computer any more")
        SongCache.respondFile(call, file, TYPES[file.extension.lowercase()] ?: "application/octet-stream", range)
    }

    override suspend fun respondArt(call: ApplicationCall, key: String, size: Int): Boolean {
        val file = file(key) ?: return false
        val (bytes, type) = withContext(Dispatchers.IO) { cover(file) } ?: return false
        call.response.headers.append("Cache-Control", "max-age=86400")
        call.respondBytes(bytes, ContentType.parse(type))
        return true
    }

    /** The file of a song of this library, to send to the server for another player. */
    fun fileOf(songId: String): File? {
        val prefix = LocalMusic.songId(id, "")
        return if (songId.startsWith(prefix)) file(songId.removePrefix(prefix)) else null
    }

    companion object {
        private val log = LoggerFactory.getLogger(ComputerLibrary::class.java)
        private val json = Json { ignoreUnknownKeys = true }
        /** The folders are looked through again after this long, when the Local tab asks. */
        const val RESCAN_MS = 10 * 60_000L

        val TYPES = mapOf(
            "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "mp4" to "audio/mp4", "aac" to "audio/aac", "flac" to "audio/flac",
            "ogg" to "audio/ogg", "oga" to "audio/ogg", "opus" to "audio/ogg", "wav" to "audio/wav", "wma" to "audio/x-ms-wma", "webm" to "audio/webm",
        )
        private val COVER_NAMES = setOf("cover", "folder", "front", "album", "albumart")

        fun key(path: String): String = MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

        private fun number(text: String?): Int? = text?.substringBefore('/')?.trim()?.toIntOrNull()

        /** The picture in the file, else a cover image in its folder. */
        fun cover(file: File): Pair<ByteArray, String>? {
            runCatching { AudioFileIO.read(file).tag?.firstArtwork }.getOrNull()?.let { art ->
                val bytes = art.binaryData
                if (bytes != null && bytes.isNotEmpty()) return bytes to (art.mimeType?.takeIf { it.startsWith("image/") } ?: "image/jpeg")
            }
            val image = folderCover(file) ?: return null
            return image.readBytes() to (if (image.extension.lowercase() == "png") "image/png" else "image/jpeg")
        }

        private fun folderCover(file: File): File? = file.parentFile?.listFiles()?.sortedBy { it.name }?.firstOrNull {
            it.extension.lowercase() in setOf("jpg", "jpeg", "png") && it.nameWithoutExtension.lowercase() in COVER_NAMES
        }
    }
}
