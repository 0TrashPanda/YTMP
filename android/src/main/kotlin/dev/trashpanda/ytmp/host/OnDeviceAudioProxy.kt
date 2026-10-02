package dev.trashpanda.ytmp.host

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

/** Streams audio through the phone, for clients whose direct stream URL doesn't work. */
class OnDeviceAudioProxy(
    private val stream: suspend (songId: String, fresh: Boolean) -> OnDeviceYtm.Stream,
) : AudioProxy {
    override suspend fun respond(call: ApplicationCall, songId: String, range: String?) {
        var connection = open(stream(songId, false), range)
        if (connection.responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
            // The cached URL went bad before its expiry; get a fresh one and try once more.
            connection.disconnect()
            connection = open(stream(songId, true), range)
        }
        val status = connection.responseCode
        if (status >= 400) {
            connection.disconnect()
            throw SourceException("YouTube answered $status")
        }
        connection.getHeaderField("Content-Range")?.let { call.response.header(HttpHeaders.ContentRange, it) }
        call.response.header(HttpHeaders.AcceptRanges, "bytes")
        call.respondOutputStream(
            contentType = connection.contentType?.let(ContentType::parse),
            status = HttpStatusCode.fromValue(status),
            contentLength = connection.contentLengthLong.takeIf { it >= 0 },
        ) {
            connection.inputStream.use { it.copyTo(this) }
        }
    }

    private suspend fun open(stream: OnDeviceYtm.Stream, range: String?): HttpURLConnection = withContext(Dispatchers.IO) {
        (URL(stream.url).openConnection() as HttpURLConnection).apply {
            stream.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (range != null) setRequestProperty("Range", range)
            connect()
        }
    }
}
