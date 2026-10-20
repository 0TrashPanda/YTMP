package dev.trashpanda.ytmp.host

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import dev.trashpanda.ytmp.Log
import dev.trashpanda.ytmp.protocol.Song
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
 */
class PhoneDownloads(private val context: Context, val files: SongFiles, private val scope: CoroutineScope) {
    private val running = ConcurrentHashMap<String, Deferred<Unit>>()

    /** Saves [song]; [done] hears null when it's there (or already was), else why not. Tapping twice downloads once. */
    fun save(song: Song, done: (error: String?) -> Unit) {
        val job = running.computeIfAbsent(song.id) {
            // Started once it's in [running], so it can't take itself out before it's there.
            scope.async(start = CoroutineStart.LAZY) {
                try {
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
