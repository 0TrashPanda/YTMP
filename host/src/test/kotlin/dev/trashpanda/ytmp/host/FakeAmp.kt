package dev.trashpanda.ytmp.host

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A pretend multi-room amp with a web API like the AudioSystem 8.8's (`/audio_set?output=N&…`),
 * which also serves a zones file at `/ytmp-zones.json`, like the Raspberry Pi next to it would.
 */
class FakeAmp : AutoCloseable {
    /** [vchan]: the input in the zone's list (1 Digibox, 2 computer, 3 radio). */
    data class ZoneState(val vchan: Int, val muted: Int, val volume: Int)

    val zones = ConcurrentHashMap<Int, ZoneState>()
    /** The changes asked for, e.g. "output=5&vchan=2". */
    val writes = CopyOnWriteArrayList<String>()
    @Volatile var zonesFile = ""

    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
    val port get() = server.address.port

    init {
        server.createContext("/") { exchange ->
            val (code, body) = when (exchange.requestURI.path) {
                "/ytmp-zones.json" -> 200 to zonesFile
                "/audio_set" -> answer(exchange.requestURI.rawQuery.orEmpty())
                else -> 404 to "not found"
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    private fun answer(query: String): Pair<Int, String> {
        val params = query.split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val output = params["output"]?.toIntOrNull() ?: return 400 to "no output"
        val zone = zones[output] ?: return 400 to "no such output"
        if (params["settings"] == "?") {
            val channel = when (zone.vchan) { 1 -> 1; 2 -> 3; else -> 9 }
            return 200 to "/audio_set?output=$output&settings={\"name\":\"Zone%20$output\",\"channel\":$channel,\"vchan\":${zone.vchan},\"volume\":${zone.volume},\"muted\":${zone.muted}}"
        }
        writes += query
        zones[output] = zone.copy(
            vchan = params["vchan"]?.toInt() ?: zone.vchan,
            muted = params["muted"]?.toInt() ?: zone.muted,
            volume = params["volume"]?.toInt() ?: zone.volume,
        )
        return 200 to "/audio_set?$query"
    }

    /** A zones file for this amp and [mpdPort], with an AudioSystem 8.8 setup like docs/implementation/zones.md's. */
    fun zonesFor(mpdPort: Int, names: Map<Int, String>): String = """
        {
          "player": {"type": "mpd", "port": $mpdPort},
          "amp": {
            "base": "http://127.0.0.1:$port/audio_set?output={zone}",
            "state": "{base}&settings=?",
            "stateAfter": "settings=",
            "ours": {"channel": 3, "muted": 0},
            "save": ["vchan", "muted"],
            "on": ["{base}&vchan=2", "{base}&muted=0"],
            "off": ["{base}&muted=1", "{base}&vchan={vchan}", "{base}&muted={muted}"],
            "volume": {"set": "{base}&volume={volume}", "field": "volume", "max": 50}
          },
          "zones": [${names.entries.joinToString { (zone, name) -> "{\"zone\": $zone, \"name\": \"$name\"}" }}]
        }
    """.trimIndent()

    override fun close() = server.stop(0)
}
