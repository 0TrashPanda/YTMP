package dev.trashpanda.ytmp.host

import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import dev.trashpanda.ytmp.Log
import dev.trashpanda.ytmp.protocol.AlbumRef
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.Song
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Download in the song menu, in the app: the song as a tagged file in the phone's Music/YTMP
 * folder (docs/implementation/downloads.md), where music players find it, and the Local tab
 * too (Music on this phone). Saving there needs no permission.
 *
 * The downloads carry their song ID, so the song cache lists them and plays them from there
 * ([DownloadedSongs], docs/implementation/song-cache.md#downloaded-songs). [media] serves the files.
 */
class PhoneDownloads(
    private val context: Context,
    val files: SongFiles,
    private val scope: CoroutineScope,
    private val media: PhoneLibrary,
    /** Waits until a download may start (on Wi-Fi, if the owner wants that). */
    private val gate: suspend () -> Unit = {},
) : DownloadedSongs {
    private val running = ConcurrentHashMap<String, Deferred<Unit>>()

    /** A download was saved (the song cache drops its own copy). Set by the host. */
    @Volatile
    var onSaved: (Song) -> Unit = {}

    private val scanning = Mutex()
    @Volatile
    private var found: List<DownloadedSong> = emptyList()
    @Volatile
    private var byId: Map<String, DownloadedSong> = emptyMap()
    private var generation = -1L
    /** The song ID in each file, by its media store ID and when it changed (reading one opens the file). */
    private val ids = ConcurrentHashMap<Long, Pair<Long, String?>>()

    override fun find(songId: String): DownloadedSong? = byId[songId]

    override suspend fun all(): List<DownloadedSong> = scanning.withLock {
        val now = withContext(Dispatchers.IO) { runCatching { MediaStore.getExternalVolumeNames(context).sumOf { MediaStore.getGeneration(context, it) } }.getOrDefault(-2L) }
        if (now == generation) return found
        val list = withContext(Dispatchers.IO) { scan() }
        found = list
        byId = list.associateBy { it.song.id }
        generation = now
        list
    }

    // The app's downloads are .m4a files (or kept as YouTube sent them, but then without an ID to find them by).
    override suspend fun respond(call: ApplicationCall, download: DownloadedSong, range: String?) =
        media.respond(call, Uri.parse(download.uri).lastPathSegment!!, range, SongFiles.MP4)

    override suspend fun respondArt(call: ApplicationCall, download: DownloadedSong, size: Int) =
        media.respondArt(call, Uri.parse(download.uri).lastPathSegment!!, size)

    /** The app's files in Music/YTMP that say which song they are. */
    private fun scan(): List<DownloadedSong> {
        val columns = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
        val list = ArrayList<DownloadedSong>()
        val seen = HashSet<Long>()
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, columns, "${MediaStore.Audio.Media.RELATIVE_PATH} = ?", arrayOf(FOLDER), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                seen += id
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                val modified = c.getLong(5)
                val songId = ids[id]?.takeIf { it.first == modified }?.second ?: run {
                    val read = runCatching { context.contentResolver.openInputStream(uri)?.use(M4aTags::readSongId) }.getOrNull()
                    ids[id] = modified to read
                    read
                } ?: continue
                fun text(i: Int) = c.getString(i)?.trim()?.takeIf { it.isNotEmpty() && it != MediaStore.UNKNOWN_STRING }
                val song = Song(
                    id = songId,
                    title = text(1) ?: "Unknown song",
                    artists = listOfNotNull(text(2)?.let { ArtistRef(null, it) }),
                    album = text(3)?.let { AlbumRef(null, it) },
                    durationMs = c.getLong(4),
                    thumbnails = emptyList(),
                )
                list += DownloadedSong(song, uri.toString())
            }
        }
        ids.keys.retainAll(seen)
        return list.distinctBy { it.song.id }
    }

    /** Saves [song]; [done] hears null when it's there (or already was), else why not. Tapping twice downloads once. */
    fun save(song: Song, done: (error: String?) -> Unit) {
        val job = running.computeIfAbsent(song.id) {
            // Started once it's in [running], so it can't take itself out before it's there.
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    gate()
                    saveNow(song)
                } finally {
                    running.remove(song.id)
                }
            }
        }
        job.start()
        scope.launch {
            val error = try {
                job.await()
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download ${song.id}: ${e.message}", e)
                e.message ?: "Couldn't download it"
            }
            done(error)
        }
    }

    private suspend fun saveNow(song: Song) {
        val resolver = context.contentResolver
        if (withContext(Dispatchers.IO) { saved(SongFiles.fileName(song, "m4a")) }) {
            Log.i(TAG, "download ${song.id}: already in $FOLDER")
            return
        }
        val made = files.make(song)
        try {
            withContext(Dispatchers.IO) {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, made.name)
                    put(MediaStore.Audio.Media.MIME_TYPE, made.mimeType)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, FOLDER)
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw SourceException("Couldn't save it in the Music folder")
                try {
                    resolver.openOutputStream(uri)?.use { out -> made.file.inputStream().use { it.copyTo(out) } }
                        ?: throw SourceException("Couldn't save it in the Music folder")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
                Log.i(TAG, "download ${song.id}: saved as $FOLDER${made.name} (${made.file.length() / 1024} KB)")
            }
        } finally {
            made.file.delete()
        }
        onSaved(song)
        all()
    }

    /** Whether this app already saved a file called [name] there (it only sees its own files). */
    private fun saved(name: String): Boolean =
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Audio.Media._ID),
            "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} = ?",
            arrayOf(name, FOLDER),
            null,
        )?.use { it.count > 0 } ?: false

    private companion object {
        const val TAG = "YtmpDownload"
        val FOLDER = "${Environment.DIRECTORY_MUSIC}/YTMP/"
    }
}
