package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.Log
import dev.trashpanda.ytmp.OnDeviceYtm
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streams audio through the phone: for clients whose direct stream URL doesn't work, and for
 * Cast devices, which always fetch from the host.
 *
 * YouTube answers requests without a range (or with a huge one) very slowly, and Cast devices
 * ask without a range, so YouTube is always asked for [CHUNK_SIZE] pieces with an explicit
 * range (like yt-dlp does), passed on as one stream. Downloads get the whole file the same way.
 */
class OnDeviceAudioProxy(
    private val stream: suspend (songId: String, fresh: Boolean) -> OnDeviceYtm.Stream,
) : AudioProxy, AudioFiles {
    override suspend fun respond(call: ApplicationCall, songId: String, range: String?) {
        val (start, requestedEnd) = ByteRange.parse(range)
        var source = stream(songId, false)
        var first = open(source, start, minOf(requestedEnd, start + CHUNK_SIZE - 1))
        if (first.responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
            // The cached URL went bad before its expiry; get a fresh one and try once more.
            Log.w(TAG, "audio $songId: YouTube answered 403 for the cached link, resolving again")
            first.disconnect()
            source = stream(songId, true)
            first = open(source, start, minOf(requestedEnd, start + CHUNK_SIZE - 1))
        }
        val status = first.responseCode
        if (status >= 400) {
            first.disconnect()
            Log.w(TAG, "audio $songId: YouTube answered $status (bytes $start-)")
            throw SourceException("YouTube answered $status")
        }
        val total = ByteRange.totalSize(first.getHeaderField("Content-Range"))
        val end = if (total != null) minOf(requestedEnd, total - 1) else requestedEnd

        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        if (range != null && total != null) call.response.header(HttpHeaders.ContentRange, "bytes $start-$end/$total")
        call.respondOutputStream(
            contentType = first.contentType?.let(ContentType::parse),
            status = if (range != null) HttpStatusCode.PartialContent else HttpStatusCode.OK,
            contentLength = total?.let { end - start + 1 },
        ) {
            var connection = first
            var position = start
            var pieceStarted = System.nanoTime()
            var refreshed = false
            try {
                while (true) {
                    position += connection.inputStream.use { it.copyTo(this) }
                    connection.disconnect()
                    val took = (System.nanoTime() - pieceStarted) / 1_000_000
                    if (took > SLOW_PIECE_MS) Log.w(TAG, "audio $songId: a 1 MB piece took $took ms (at byte $position)")
                    if (total == null || position > end) break
                    pieceStarted = System.nanoTime()
                    connection = open(source, position, minOf(end, position + CHUNK_SIZE - 1))
                    if (connection.responseCode == HttpURLConnection.HTTP_FORBIDDEN && !refreshed) {
                        // YouTube sometimes stops a link after its first MB: a fresh one may go on.
                        Log.w(TAG, "audio $songId: YouTube answered 403 at byte $position of $total, resolving again")
                        refreshed = true
                        connection.disconnect()
                        source = stream(songId, true)
                        connection = open(source, position, minOf(end, position + CHUNK_SIZE - 1))
                    }
                    if (connection.responseCode >= 400) {
                        Log.w(TAG, "audio $songId: YouTube answered ${connection.responseCode} at byte $position of $total, the stream stops here")
                        break
                    }
                }
            } catch (e: Exception) {
                // Usually the listener stopped or skipped; nothing to report.
                Log.d(TAG, "audio $songId: stopped at $position: $e")
            } finally {
                connection.disconnect()
            }
        }
    }

    override suspend fun download(songId: String, to: File, started: (type: String, size: Long?) -> Unit): String {
        var source = stream(songId, false)
        var refreshed = false
        var position = 0L
        var total: Long? = null
        var type: String? = null
        to.outputStream().use { out ->
            while (total == null || position < total!!) {
                var connection = open(source, position, position + CHUNK_SIZE - 1)
                if (connection.responseCode == HttpURLConnection.HTTP_FORBIDDEN && !refreshed) {
                    // The link went bad (or YouTube stopped it after a piece): a fresh one may go on.
                    Log.w(TAG, "download $songId: YouTube answered 403 at byte $position, resolving again")
                    refreshed = true
                    connection.disconnect()
                    source = stream(songId, true)
                    connection = open(source, position, position + CHUNK_SIZE - 1)
                }
                try {
                    val status = connection.responseCode
                    if (status >= 400) {
                        Log.w(TAG, "download $songId: YouTube answered $status at byte $position of $total")
                        throw SourceException("YouTube answered $status")
                    }
                    if (type == null) {
                        type = connection.contentType ?: SongFiles.MP4
                        total = ByteRange.totalSize(connection.getHeaderField("Content-Range"))
                        started(type!!, total)
                    }
                    val got = withContext(Dispatchers.IO) { connection.inputStream.use { it.copyTo(out) } }
                    position += got
                    // Without a size, the one answer was the whole file.
                    if (total == null || got == 0L) break
                } finally {
                    connection.disconnect()
                }
            }
        }
        total?.let { if (position < it) throw SourceException("The download stopped at ${position * 100 / it}%") }
        Log.i(TAG, "download $songId: $position bytes")
        return type ?: SongFiles.MP4
    }

    private suspend fun open(stream: OnDeviceYtm.Stream, start: Long, end: Long): HttpURLConnection = withContext(Dispatchers.IO) {
        (URL(stream.url).openConnection() as HttpURLConnection).apply {
            stream.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            setRequestProperty("Range", "bytes=$start-$end")
            connect()
        }
    }

    private companion object {
        const val TAG = "YtmpAudio"
        const val CHUNK_SIZE = 1024L * 1024
        /** A piece taking longer than this (ms) is what a listener hears as buffering. */
        const val SLOW_PIECE_MS = 5000L
    }
}
