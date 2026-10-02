package dev.trashpanda.ytmp.host

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** A pretend Sonos speaker: answers the UPnP calls like a real one and records them. */
class FakeSonos(private val room: String = "Woonkamer") : AutoCloseable {
    val actions = CopyOnWriteArrayList<String>()
    @Volatile var state = "STOPPED"
    @Volatile var uri: String? = null
    @Volatile var title: String? = null
    @Volatile var volume = 40
    @Volatile var positionMs = 0L

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/xml/device_description.xml") { exchange ->
            val body = "<root><device><roomName>$room</roomName><modelName>Sonos One</modelName></device></root>".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        createContext("/MediaRenderer/") { exchange ->
            val action = exchange.requestHeaders.getFirst("SOAPACTION").substringAfter('#').trim('"')
            val request = exchange.requestBody.readBytes().decodeToString()
            actions += action
            val reply = when (action) {
                "SetAVTransportURI" -> {
                    uri = SonosPlayer.unescape(SonosPlayer.tag(request, "CurrentURI")!!)
                    title = SonosPlayer.tag(SonosPlayer.unescape(SonosPlayer.tag(request, "CurrentURIMetaData")!!), "title")
                    state = "STOPPED"
                    positionMs = 0
                    ""
                }
                "Play" -> { state = "PLAYING"; "" }
                "Pause" -> { state = "PAUSED_PLAYBACK"; "" }
                "Stop" -> { state = "STOPPED"; "" }
                "Seek" -> { positionMs = SonosPlayer.parseTime(SonosPlayer.tag(request, "Target")); "" }
                "GetTransportInfo" -> "<CurrentTransportState>$state</CurrentTransportState>"
                "GetPositionInfo" -> "<RelTime>${SonosPlayer.time(positionMs)}</RelTime><TrackURI>${SonosPlayer.escape(uri.orEmpty())}</TrackURI>"
                "GetVolume" -> "<CurrentVolume>$volume</CurrentVolume>"
                "SetVolume" -> { volume = SonosPlayer.tag(request, "DesiredVolume")!!.toInt(); "" }
                else -> ""
            }
            val body = "<s:Envelope><s:Body><u:${action}Response>$reply</u:${action}Response></s:Body></s:Envelope>".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }

    val port: Int get() = server.address.port

    override fun close() = server.stop(0)
}
