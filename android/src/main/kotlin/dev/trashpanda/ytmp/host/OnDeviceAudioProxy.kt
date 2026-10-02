package dev.trashpanda.ytmp.host

import android.util.Log
import dev.trashpanda.ytmp.OnDeviceYtm
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respondOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streams audio through the phone: for clients whose direct stream URL doesn't work, and for
 * Cast devices, which always fetch from the host.
 *
 * YouTube answers requests without a range (or with a huge one) very slowly, and Cast devices
 * ask without a range, so YouTube is always asked for [CHUNK_SIZE] pieces with an explicit
 * range (like yt-dlp does), passed on as one stream.
 */
class OnDeviceAudioProxy(
    private val stream: suspend (songId: String, fresh: Boolean) -> OnDeviceYtm.Stream,
) : AudioProxy {
    override suspend fun respond(call: ApplicationCall, songId: String, range: String?) {
        val (start, requestedEnd) = ByteRange.parse(range)
        var source = stream(songId, false)
        var first = open(source, start, minOf(requestedEnd, start + CHUNK_SIZE - 1))
        if (first.responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
            // The cached URL went bad before its expiry; get a fresh one and try once more.
            first.disconnect()
            source = stream(songId, true)
            first = open(source, start, minOf(requestedEnd, start + CHUNK_SIZE - 1))
        }
        val status = first.responseCode
        if (status >= 400) {
            first.disconnect()
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
            try {
                while (true) {
                    position += connection.inputStream.use { it.copyTo(this) }
                    connection.disconnect()
                    if (total == null || position > end) break
                    connection = open(source, position, minOf(end, position + CHUNK_SIZE - 1))
                    if (connection.responseCode >= 400) break
                }
            } catch (e: Exception) {
                // Usually the listener stopped or skipped; nothing to report.
                Log.d(TAG, "audio $songId: stopped at $position: $e")
            } finally {
                connection.disconnect()
            }
        }
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
    }
}

/** HTTP byte ranges, as far as audio streaming needs them. */
object ByteRange {
    const val OPEN_END = Long.MAX_VALUE / 2

    /** "bytes=100-" -> (100, [OPEN_END]); null or unsupported -> the whole file. */
    fun parse(header: String?): Pair<Long, Long> {
        val match = Regex("""bytes=(\d+)-(\d*)""").matchEntire(header?.trim().orEmpty()) ?: return 0L to OPEN_END
        val (start, end) = match.destructured
        return start.toLong() to (end.toLongOrNull() ?: OPEN_END)
    }

    /** "bytes 0-1023/5000" -> 5000. */
    fun totalSize(contentRange: String?): Long? =
        contentRange?.let { Regex("""/(\d+)$""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
}
