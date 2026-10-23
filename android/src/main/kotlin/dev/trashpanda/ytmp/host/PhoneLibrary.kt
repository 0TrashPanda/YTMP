package dev.trashpanda.ytmp.host

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Size
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import dev.trashpanda.ytmp.protocol.LocalLibraryKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * The music stored on this phone (Android's media store) as a [LocalLibrary], for rooms hosted
 * here. The phone serves the files and covers itself (`/api/audio/…`, `/api/local/art/…`), at
 * [baseUrl] (its address on the network, so speakers and friends reach it too).
 */
class PhoneLibrary(private val context: Context, private val baseUrl: () -> String, val folders: PhoneFolders = PhoneFolders(context), private val canRead: () -> Boolean = { true }) : LocalLibrary {
    override val id = ID
    override val name = "On this phone"
    override val kind = LocalLibraryKind.PHONE

    private val mutex = Mutex()
    private var tracks: List<LocalTrack>? = null
    private var generation = -1L
    private var foldersSeen = -1
    private val types = ConcurrentHashMap<String, String>()

    /** The phone's music; read again when the media store changed. */
    override suspend fun tracks(): List<LocalTrack> = mutex.withLock {
        val now = withContext(Dispatchers.IO) { generation() }
        tracks?.let { if (now == generation && !folders.stale(foldersSeen)) return it }
        val seen = folders.version
        val read = withContext(Dispatchers.IO) {
            // The media store needs the permission; the picked folders don't.
            val scanned = if (canRead()) query() else emptyList()
            // A file the media store knows too shows once (its entry stays).
            val seenSongs = scanned.mapTo(HashSet(), ::sameSong)
            scanned + folders.tracks().filter { seenSongs.add(sameSong(it)) }.onEach { track -> folders.mime(track.key)?.let { types[track.key] = it } }
        }
        tracks = read
        generation = now
        foldersSeen = seen
        read
    }

    override fun fileUrl(key: String) = "${baseUrl()}/api/audio/${encode(LocalMusic.songId(id, key))}"

    override fun artUrl(key: String, size: Int) = "${baseUrl()}/api/local/art/${encode(LocalMusic.songId(id, key))}?size=$size"

    override suspend fun respond(call: ApplicationCall, key: String, range: String?) = respond(call, key, range, types[key])

    /** Sends the media store's file [key], with [range], as [type] (e.g. "audio/mp4"; null: not said). */
    suspend fun respond(call: ApplicationCall, key: String, range: String?, type: String?) {
        val uri = uriOf(key) ?: throw SourceException("Not a song on this phone")
        val file = withContext(Dispatchers.IO) { context.contentResolver.openFileDescriptor(uri, "r") } ?: throw SourceException("That song isn't on this phone any more")
        val total = file.statSize
        val (start, requestedEnd) = ByteRange.parse(range)
        val end = minOf(requestedEnd, total - 1)
        if (start > end) {
            file.close()
            call.response.header(HttpHeaders.ContentRange, "bytes */$total")
            call.respondBytes(ByteArray(0), status = HttpStatusCode.RequestedRangeNotSatisfiable)
            return
        }
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        if (range != null) call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$total")
        // Closed after sending, also when sending never starts (closing twice is fine).
        try {
            sendFile(call, file, type, range != null, start, end)
        } finally {
            runCatching { file.close() }
        }
    }

    private suspend fun sendFile(call: ApplicationCall, file: android.os.ParcelFileDescriptor, type: String?, partial: Boolean, start: Long, end: Long) {
        call.respondOutputStream(
            contentType = type?.let { runCatching { ContentType.parse(it) }.getOrNull() },
            status = if (partial) HttpStatusCode.PartialContent else HttpStatusCode.OK,
            contentLength = end - start + 1,
        ) {
            file.use {
                FileInputStream(it.fileDescriptor).use { input ->
                    input.channel.position(start)
                    var left = end - start + 1
                    val buffer = ByteArray(64 * 1024)
                    try {
                        while (left > 0) {
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                            if (read < 0) break
                            write(buffer, 0, read)
                            left -= read
                        }
                    } catch (e: java.io.IOException) {
                        // The listener stopped or skipped.
                    }
                }
            }
        }
    }

    /** The first [bytes] of song [key]'s file, where its tags are (null: can't read it). */
    suspend fun head(key: String, bytes: Int = 128 * 1024): ByteArray? = withContext(Dispatchers.IO) {
        val uri = uriOf(key) ?: return@withContext null
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(bytes)
                var got = 0
                while (got < bytes) {
                    val n = input.read(buffer, got, bytes - got)
                    if (n < 0) break
                    got += n
                }
                buffer.copyOf(got)
            }
        }.getOrNull()
    }

    /** Title, artist, album and length in seconds: the same song in two places. */
    private fun sameSong(t: LocalTrack) = listOf(t.title, t.artist, t.album).joinToString("|") { it.trim().lowercase() } + "|" + t.durationMs / 1000

    /** The scan was switched or the folders changed: read the music again. */
    fun reload() = folders.rescan()

    /** The file of song [key]: the media store's, or one in a picked folder. */
    fun uriOf(key: String): android.net.Uri? =
        if (folders.isFolderKey(key)) folders.uri(key)
        else key.toLongOrNull()?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) }

    override suspend fun respondArt(call: ApplicationCall, key: String, size: Int): Boolean {
        val bytes = withContext(Dispatchers.IO) {
            if (folders.isFolderKey(key)) folders.art(key, size)
            else {
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, key.toLongOrNull() ?: return@withContext null)
                runCatching {
                    val picture = context.contentResolver.loadThumbnail(uri, Size(size, size), null)
                    ByteArrayOutputStream().also { picture.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                }.getOrNull()
            }
        } ?: return false
        call.response.header(HttpHeaders.CacheControl, "max-age=86400")
        call.respondBytes(bytes, ContentType.Image.JPEG)
        return true
    }

    /** Changes when the phone's music does (the sum over its storage volumes). */
    private fun generation(): Long = MediaStore.getExternalVolumeNames(context).sumOf { MediaStore.getGeneration(context, it) }

    private fun query(): List<LocalTrack> {
        val columns = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.GENRE,
            MediaStore.Audio.Media.DISPLAY_NAME,
        )
        val found = ArrayList<LocalTrack>()
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null)?.use { c ->
            while (c.moveToNext()) {
                val key = c.getLong(0).toString()
                fun text(i: Int) = c.getString(i)?.trim()?.takeIf { it.isNotEmpty() && it != MediaStore.UNKNOWN_STRING }
                val artist = text(2) ?: text(3) ?: "Unknown artist"
                val number = if (c.isNull(5)) null else c.getInt(5)
                c.getString(9)?.let { types[key] = it }
                found += LocalTrack(
                    key = key,
                    title = text(1) ?: "Unknown song",
                    artist = artist,
                    albumArtist = text(3) ?: artist,
                    album = text(4).orEmpty(),
                    // Android keeps the disc in the thousands: 2003 is disc 2, track 3.
                    disc = number?.div(1000)?.takeIf { it > 0 },
                    track = number?.rem(1000)?.takeIf { it > 0 },
                    year = if (c.isNull(6)) null else c.getInt(6).takeIf { it > 0 }?.toString(),
                    durationMs = c.getLong(7),
                    folder = c.getString(8).orEmpty(),
                    genre = text(10).orEmpty(),
                    file = c.getString(11).orEmpty(),
                )
            }
        }
        return found
    }

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    companion object {
        const val ID = "phone"
    }
}
