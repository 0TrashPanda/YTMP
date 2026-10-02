package dev.trashpanda.ytmp.host

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI

/** What a Sonos speaker is doing, from GetTransportInfo and GetPositionInfo. */
data class SonosStatus(
    /** PLAYING, PAUSED_PLAYBACK, STOPPED, TRANSITIONING or NO_MEDIA_PRESENT. */
    val state: String,
    val positionMs: Long,
    val trackUri: String?,
    val receivedAt: Long,
)

/** A Sonos speaker's own name and model, from its device description. */
data class SonosInfo(val roomName: String, val model: String)

class SonosException(message: String) : Exception(message)

/**
 * Controls one Sonos speaker with UPnP (SOAP over HTTP on port 1400), the same calls the
 * Sonos apps use: set a song URL, play, pause, seek, volume. The speaker fetches the audio
 * itself. See docs/implementation/sonos.md.
 */
class SonosPlayer(val host: String, private val port: Int = 1400) {
    /** This machine's address as the speaker sees it, so it can reach our audio URLs. */
    val localAddress: InetAddress by lazy {
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(host, port))
            socket.localAddress
        }
    }

    suspend fun info(): SonosInfo {
        val xml = get("/xml/device_description.xml")
        return SonosInfo(roomName = tag(xml, "roomName") ?: host, model = tag(xml, "modelName") ?: "Sonos")
    }

    /** Loads a song (doesn't start it). */
    suspend fun setUri(url: String, title: String, artist: String, album: String?, artUrl: String?, durationMs: Long) {
        transport(
            "SetAVTransportURI",
            "InstanceID" to "0",
            "CurrentURI" to url,
            "CurrentURIMetaData" to didl(url, title, artist, album, artUrl, durationMs),
        )
    }

    suspend fun play() = transport("Play", "InstanceID" to "0", "Speed" to "1")

    suspend fun pause() = transport("Pause", "InstanceID" to "0")

    suspend fun stop() = transport("Stop", "InstanceID" to "0")

    suspend fun seek(positionMs: Long) = transport("Seek", "InstanceID" to "0", "Unit" to "REL_TIME", "Target" to time(positionMs))

    suspend fun status(): SonosStatus {
        val transport = transport("GetTransportInfo", "InstanceID" to "0")
        val position = transport("GetPositionInfo", "InstanceID" to "0")
        return SonosStatus(
            state = tag(transport, "CurrentTransportState") ?: "STOPPED",
            positionMs = parseTime(tag(position, "RelTime")),
            trackUri = tag(position, "TrackURI")?.let(::unescape),
            receivedAt = System.currentTimeMillis(),
        )
    }

    /** 0.0–1.0 */
    suspend fun volume(): Double =
        (tag(rendering("GetVolume", "InstanceID" to "0", "Channel" to "Master"), "CurrentVolume")?.toIntOrNull() ?: 0) / 100.0

    suspend fun setVolume(level: Double) {
        rendering("SetVolume", "InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to Math.round(level.coerceIn(0.0, 1.0) * 100).toString())
    }

    private suspend fun transport(action: String, vararg args: Pair<String, String>) =
        soap("/MediaRenderer/AVTransport/Control", "urn:schemas-upnp-org:service:AVTransport:1", action, args)

    private suspend fun rendering(action: String, vararg args: Pair<String, String>) =
        soap("/MediaRenderer/RenderingControl/Control", "urn:schemas-upnp-org:service:RenderingControl:1", action, args)

    private suspend fun soap(path: String, service: String, action: String, args: Array<out Pair<String, String>>): String {
        val body = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""")
            append("""<u:$action xmlns:u="$service">""")
            for ((name, value) in args) append("<$name>").append(escape(value)).append("</$name>")
            append("</u:$action></s:Body></s:Envelope>")
        }
        return withContext(Dispatchers.IO) {
            val connection = URI("http://$host:$port$path").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("SOAPACTION", "\"$service#$action\"")
            try {
                connection.outputStream.use { it.write(body.toByteArray()) }
                val ok = connection.responseCode in 200..299
                val text = (if (ok) connection.inputStream else connection.errorStream)?.bufferedReader()?.readText().orEmpty()
                if (!ok) throw SonosException("$action failed: ${errorText(tag(text, "errorCode"))}")
                text
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val connection = URI("http://$host:$port$path").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        try {
            if (connection.responseCode != 200) throw SonosException("$path answered ${connection.responseCode}")
            connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** UPnP error codes Sonos uses, in words. */
        private fun errorText(code: String?) = when (code) {
            "701" -> "not possible right now"
            "711", "712" -> "can't seek there"
            "714" -> "unsupported audio format"
            "716" -> "the song couldn't be found"
            "800" -> "the speaker is part of a group; play on the group's main speaker"
            null -> "no answer"
            else -> "error $code"
        }

        /** Song details as DIDL-Lite, so the Sonos app shows title, artist and art. */
        internal fun didl(url: String, title: String, artist: String, album: String?, artUrl: String?, durationMs: Long) = buildString {
            append("""<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """)
            append("""xmlns:r="urn:schemas-rinconnetworks-com:metadata-1-0/" xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/">""")
            append("""<item id="ytmp" parentID="-1" restricted="true">""")
            append("<dc:title>").append(escape(title)).append("</dc:title>")
            append("<dc:creator>").append(escape(artist)).append("</dc:creator>")
            if (album != null) append("<upnp:album>").append(escape(album)).append("</upnp:album>")
            if (artUrl != null) append("<upnp:albumArtURI>").append(escape(artUrl)).append("</upnp:albumArtURI>")
            append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
            append("""<res protocolInfo="http-get:*:audio/mp4:*" duration="${time(durationMs)}">""").append(escape(url)).append("</res>")
            append("</item></DIDL-Lite>")
        }

        internal fun escape(text: String) = text
            .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")

        internal fun unescape(text: String) = text
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

        internal fun tag(xml: String, name: String): String? =
            Regex("<(?:\\w+:)?$name(?:\\s[^>]*)?>([^<]*)</(?:\\w+:)?$name>").find(xml)?.groupValues?.get(1)

        /** "H:MM:SS" */
        internal fun time(ms: Long): String {
            val s = maxOf(0, ms) / 1000
            return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
        }

        internal fun parseTime(text: String?): Long {
            val parts = text?.split(":")?.mapNotNull { it.substringBefore('.').toLongOrNull() } ?: return 0
            if (parts.size != 3) return 0
            return ((parts[0] * 60 + parts[1]) * 60 + parts[2]) * 1000
        }
    }
}
