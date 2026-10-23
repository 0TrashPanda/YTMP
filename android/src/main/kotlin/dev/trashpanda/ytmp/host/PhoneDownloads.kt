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
import dev.trashpanda.ytmp.protocol.DownloadItem
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

enum class DownloadState(val wire: String) { WAITING("waiting"), DOWNLOADING("downloading"), FAILED("failed") }

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
    /** Waits until a download may start (on Wi-Fi, if the owner wants that), or [now] says it may anyway. */
    private val gate: suspend (now: () -> Boolean) -> Unit = {},
) : DownloadedSongs {
    /** A song being downloaded, waiting to be, or that failed: what Settings → Downloads lists. */
    private class Job(val song: Song) {
        @Volatile var state = DownloadState.WAITING
        @Volatile var error: String? = null
        /** Start even though the owner wants Wi-Fi (this once). */
        @Volatile var now = false
        val listeners = CopyOnWriteArrayList<(String?) -> Unit>()
        var work: kotlinx.coroutines.Job? = null
    }

    private val jobs = LinkedHashMap<String, Job>()
    private val queueFile = File(context.filesDir, "download-queue.json")
    private val queueJson = Json { ignoreUnknownKeys = true }

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
        val job = synchronized(jobs) { jobs.getOrPut(song.id) { Job(song) } }
        job.listeners += done
        start(job)
    }

    private fun start(job: Job) {
        synchronized(job) {
            if (job.work?.isActive == true) return
            job.state = DownloadState.WAITING
            job.error = null
            job.now = false
            job.work = scope.launch {
                try {
                    gate { job.now }
                    job.state = DownloadState.DOWNLOADING
                    saveNow(job.song)
                    synchronized(jobs) { jobs.remove(job.song.id) }
                    finish(job, null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "download ${job.song.id}: ${e.message}", e)
                    job.state = DownloadState.FAILED
                    job.error = e.message ?: "Couldn't download it"
                    finish(job, job.error)
                }
            }
        }
        persist()
    }

    private fun finish(job: Job, error: String?) {
        val listeners = job.listeners.toList()
        job.listeners.clear()
        listeners.forEach { it(error) }
        persist()
    }

    /** The downloads waiting, going or failed, oldest first. */
    fun queue(): List<DownloadItem> = synchronized(jobs) {
        jobs.values.map { DownloadItem(it.song, it.state.wire, it.error) }
    }

    /** Gives up on [songId] (waiting, going or failed). */
    fun cancel(songId: String) {
        val job = synchronized(jobs) { jobs.remove(songId) } ?: return
        job.work?.cancel()
        finish(job, "Cancelled")
    }

    /** Tries a failed download again. */
    fun retry(songId: String) {
        val job = synchronized(jobs) { jobs[songId] } ?: return
        if (job.state == DownloadState.FAILED) start(job)
    }

    /** Starts a waiting download now, also on mobile data. */
    fun now(songId: String) {
        synchronized(jobs) { jobs[songId] }?.now = true
    }

    /** Takes up the queue saved before the app closed. */
    fun restore() {
        val saved = runCatching { queueJson.decodeFromString(ListSerializer(DownloadItem.serializer()), queueFile.readText()) }.getOrNull() ?: return
        for (item in saved) {
            val job = synchronized(jobs) { jobs.getOrPut(item.song.id) { Job(item.song) } }
            if (item.state == DownloadState.FAILED.wire) {
                job.state = DownloadState.FAILED
                job.error = item.error
            } else {
                start(job)
            }
        }
    }

    private fun persist() {
        val items = queue()
        runCatching {
            if (items.isEmpty()) queueFile.delete() else queueFile.writeText(queueJson.encodeToString(ListSerializer(DownloadItem.serializer()), items))
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
