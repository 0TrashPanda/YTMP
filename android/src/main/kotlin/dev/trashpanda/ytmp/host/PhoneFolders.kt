package dev.trashpanda.ytmp.host

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import dev.trashpanda.ytmp.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** A folder the owner picked as music, for the settings. */
@Serializable
data class MusicFolder(val id: String, val name: String)

/**
 * Music folders the owner picked (Android's folder picker, no storage permission needed), for
 * when the media store doesn't know the music (an SD card it never scanned, a folder with a
 * `.nomedia` file). They're read through the picker's access ([DocumentsContract]) and shown with
 * the phone's other music ([PhoneLibrary]). Tags are read once per file and kept (title, artist,
 * length, …) in [index], which also lets a song in a queue be found again after a restart.
 */
class PhoneFolders(private val context: Context) {
    private val prefs = context.getSharedPreferences("ytmp.local", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val index = File(context.filesDir, "music-folders.json")
    private val files = ConcurrentHashMap<String, Entry>()

    @Volatile
    var version = 0
        private set
    @Volatile
    private var scannedAt = 0L

    @Serializable
    private data class Entry(
        val key: String,
        val uri: String,
        val mime: String,
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
    )

    init {
        runCatching {
            if (index.isFile) json.decodeFromString(ListSerializer(Entry.serializer()), index.readText()).forEach { files[it.key] = it }
        }
    }

    /** The picked folders. */
    fun all(): List<MusicFolder> = uris().map { MusicFolder(it, name(Uri.parse(it))) }

    private fun uris(): List<String> = prefs.getString(FOLDERS, null)?.let { runCatching { json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }.orEmpty()

    private fun save(list: List<String>) = prefs.edit { putString(FOLDERS, json.encodeToString(ListSerializer(String.serializer()), list)) }

    fun isEmpty() = uris().isEmpty()

    /** The owner picked [tree]: keep access to it across restarts. */
    fun add(tree: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (tree.toString() !in uris()) save(uris() + tree.toString())
        changed()
    }

    fun remove(id: String) {
        runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(id), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        save(uris() - id)
        files.entries.removeAll { it.value.folder.startsWith(prefix(id)) }
        changed()
        saveIndex()
    }

    /** Read the folders again at the next look. */
    fun rescan() = changed()

    private fun changed() {
        version++
        scannedAt = 0
    }

    /** True when [tracks] should be read again: something changed, or it's a while ago. */
    fun stale(seenVersion: Int) = seenVersion != version || System.currentTimeMillis() - scannedAt > RESCAN_MS

    /** The file for a song [key] from a folder, or null. */
    fun uri(key: String): Uri? = files[key]?.uri?.let(Uri::parse)

    fun mime(key: String): String? = files[key]?.mime

    fun isFolderKey(key: String) = key.startsWith(KEY_PREFIX)

    /** All the music in the folders; tags of new or changed files are read now. */
    fun tracks(): List<LocalTrack> {
        val seen = HashSet<String>()
        val found = ArrayList<LocalTrack>()
        var read = 0
        for (tree in uris()) {
            val root = Uri.parse(tree)
            val rootId = runCatching { DocumentsContract.getTreeDocumentId(root) }.getOrNull() ?: continue
            walk(root, rootId, prefix(tree).trimEnd('/')) { doc ->
                val key = KEY_PREFIX + sha(doc.uri.toString())
                seen += key
                val old = files[key]
                val entry = if (old != null && old.modified == doc.modified && old.size == doc.size) old else {
                    read++
                    read(key, doc) ?: return@walk
                }
                files[key] = entry
                found += LocalTrack(
                    key = key, title = entry.title, artist = entry.artist, albumArtist = entry.albumArtist, album = entry.album,
                    disc = entry.disc, track = entry.track, year = entry.year, durationMs = entry.durationMs, folder = entry.folder, genre = entry.genre,
                )
            }
        }
        val gone = files.keys.filter { it !in seen }
        gone.forEach(files::remove)
        scannedAt = System.currentTimeMillis()
        if (read > 0 || gone.isNotEmpty()) saveIndex()
        return found
    }

    /** The embedded cover of song [key], [size] px, as JPEG; null when it has none. */
    fun art(key: String, size: Int): ByteArray? {
        val uri = uri(key) ?: return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val bytes = retriever.embeddedPicture ?: return null
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val scaled = if (maxOf(bitmap.width, bitmap.height) > size) Bitmap.createScaledBitmap(bitmap, size, size, true) else bitmap
            ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private class Doc(val uri: Uri, val name: String, val mime: String, val modified: Long, val size: Long, val path: String)

    private fun walk(tree: Uri, docId: String, path: String, onAudio: (Doc) -> Unit) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_SIZE,
        )
        val dirs = ArrayList<Pair<String, String>>()
        val audio = ArrayList<Doc>()
        try {
            context.contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2).orEmpty()
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) dirs += id to name
                    else if (mime.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in EXTENSIONS) {
                        audio += Doc(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, mime, c.getLong(3), c.getLong(4), path)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "couldn't list $path: ${e.message}")
            return
        }
        audio.forEach(onAudio)
        for ((id, name) in dirs) walk(tree, id, "$path/$name", onAudio)
    }

    private fun read(key: String, doc: Doc): Entry? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, doc.uri)
            fun tag(i: Int) = retriever.extractMetadata(i)?.trim()?.takeIf { it.isNotEmpty() }
            val duration = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            val artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: "Unknown artist"
            Entry(
                key = key, uri = doc.uri.toString(), mime = doc.mime, modified = doc.modified, size = doc.size,
                title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: doc.name.substringBeforeLast('.'),
                artist = artist,
                albumArtist = tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) ?: artist,
                album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                // "3/12", "1/2".
                disc = tag(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.substringBefore('/')?.toIntOrNull()?.takeIf { it > 0 },
                track = tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.toIntOrNull()?.takeIf { it > 0 },
                year = tag(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.takeIf { it != "0" },
                durationMs = duration,
                genre = tag(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty(),
                folder = doc.path,
            )
        } catch (e: Exception) {
            Log.w(TAG, "couldn't read ${doc.name}: ${e.message}")
            null
        } finally {
            retriever.release()
        }
    }

    private fun saveIndex() {
        runCatching { index.writeText(json.encodeToString(ListSerializer(Entry.serializer()), files.values.toList())) }
    }

    /** The folder's name as the picker shows it ("Music", "Downloads/Albums"), the start of its songs' [LocalTrack.folder]. */
    private fun prefix(tree: String) = name(Uri.parse(tree)) + "/"

    private fun name(tree: Uri): String =
        runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()?.substringAfter(':')?.trim('/')?.takeIf { it.isNotEmpty() } ?: "Music folder"

    private fun sha(text: String) = MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    companion object {
        const val KEY_PREFIX = "f"
        private const val TAG = "YtmpFolders"
        private const val FOLDERS = "musicFolders"
        private const val RESCAN_MS = 2 * 60_000L
        private val EXTENSIONS = setOf("mp3", "m4a", "flac", "ogg", "opus", "wav", "aac", "wma", "oga")
    }
}
