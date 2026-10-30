package dev.trashpanda.ytmp.host

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A pretend TV with the YouTube app: its DIAL server (`/dd.xml`, `/apps/YouTube`) and, on
 * the same port, YouTube's Lounge API (`/api/lounge/…`) as the TV app answers through it.
 * Records the remote's commands.
 */
class FakeYoutubeTv(private val name: String = "LeBeamski") : AutoCloseable {
    val commands = CopyOnWriteArrayList<String>()
    @Volatile var running = true
    @Volatile var videoId: String? = null
    @Volatile var state = -1
    @Volatile var volume = 30
    @Volatile var autoplay = true
    @Volatile private var positionMs = 0L
    @Volatile private var since = 0L
    private val events = LinkedBlockingQueue<String>()
    private var eventId = 0

    /** Where the TV is in the video now. */
    val position: Long get() = if (state == 1) positionMs + (System.currentTimeMillis() - since) else positionMs

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = java.util.concurrent.Executors.newCachedThreadPool()
        createContext("/dd.xml") { exchange ->
            exchange.responseHeaders.add("Application-URL", "http://127.0.0.1:$port/apps/")
            reply(exchange, "<root><device><friendlyName>$name</friendlyName><manufacturer>Yoton</manufacturer><modelName>Y9</modelName><UDN>uuid:fake-tv</UDN></device></root>")
        }
        createContext("/apps/YouTube") { exchange ->
            if (exchange.requestMethod == "POST") {
                running = true
                exchange.sendResponseHeaders(201, -1)
                exchange.close()
                return@createContext
            }
            val extra = if (running) "<additionalData><screenId>fake-screen</screenId></additionalData>" else ""
            reply(exchange, "<service><name>YouTube</name><state>${if (running) "running" else "stopped"}</state>$extra</service>")
        }
        createContext("/api/lounge/pairing/get_lounge_token_batch") { exchange ->
            reply(exchange, """{"screens":[{"screenId":"fake-screen","loungeToken":"fake-token"}]}""")
        }
        createContext("/api/lounge/bc/bind") { exchange -> bind(exchange) }
        start()
    }

    val port: Int get() = server.address.port

    /** Where the DIAL description is, as SSDP would say. */
    val location: String get() = "http://127.0.0.1:$port/dd.xml"

    /** Someone else (the YouTube app on a phone) plays another video. */
    fun playOther(video: String) {
        videoId = video
        positionMs = 0
        since = System.currentTimeMillis()
        state = 1
        event("nowPlaying", """{"videoId":"$video","currentTime":"0","state":"1"}""")
    }

    private fun bind(exchange: HttpExchange) {
        val query = params(exchange.requestURI.rawQuery)
        when {
            exchange.requestMethod == "GET" -> {
                // The event stream: what happened since, or nothing after a while (the remote asks again).
                val first = events.poll(500, TimeUnit.MILLISECONDS)
                val chunks = listOfNotNull(first) + generateSequence { events.poll() }
                reply(exchange, chunks.joinToString(""))
            }
            "SID" !in query -> {
                val body = chunk(
                    """[0,["c","fake-sid","",8]]""",
                    """[1,["S","fake-gsession"]]""",
                    """[2,["onAutoplayModeChanged",{"autoplayMode":"${if (autoplay) "ENABLED" else "DISABLED"}"}]]""",
                    """[3,["onVolumeChanged",{"volume":"$volume","muted":"false"}]]""",
                )
                eventId = 3
                reply(exchange, body)
            }
            else -> {
                val form = params(exchange.requestBody.readBytes().decodeToString())
                val command = form["req0__sc"].orEmpty()
                commands += command
                when (command) {
                    "setPlaylist" -> {
                        videoId = form["req0_videoId"]
                        positionMs = ((form["req0_currentTime"]?.toDouble() ?: 0.0) * 1000).toLong()
                        since = System.currentTimeMillis()
                        state = 1
                        nowPlaying()
                    }
                    "play" -> changeState(1)
                    "pause" -> changeState(2)
                    "seekTo" -> {
                        positionMs = ((form["req0_newTime"]?.toDouble() ?: 0.0) * 1000).toLong()
                        since = System.currentTimeMillis()
                        stateChange()
                    }
                    "setVolume" -> {
                        volume = form["req0_volume"]!!.toInt()
                        event("onVolumeChanged", """{"volume":"$volume","muted":"false"}""")
                    }
                    "setAutoplayMode" -> autoplay = form["req0_autoplayMode"] == "ENABLED"
                    "stopVideo" -> {
                        videoId = null
                        state = -1
                        event("nowPlaying", "{}")
                    }
                    "getNowPlaying" -> nowPlaying()
                }
                reply(exchange, "")
            }
        }
    }

    private fun changeState(new: Int) {
        positionMs = position
        since = System.currentTimeMillis()
        state = new
        stateChange()
    }

    private fun seconds() = "%.3f".format(java.util.Locale.ROOT, position / 1000.0)

    private fun nowPlaying() = event("nowPlaying", """{"videoId":"$videoId","currentTime":"${seconds()}","state":"$state"}""")

    private fun stateChange() = event("onStateChange", """{"currentTime":"${seconds()}","state":"$state"}""")

    @Synchronized
    private fun event(type: String, args: String) {
        events += chunk("""[${++eventId},["$type",$args]]""")
    }

    private fun chunk(vararg entries: String): String {
        val json = entries.joinToString(",", "[", "]")
        return "${json.length}\n$json"
    }

    private fun params(raw: String?): Map<String, String> =
        raw.orEmpty().split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }

    private fun reply(exchange: HttpExchange, text: String) {
        val body = text.toByteArray()
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    override fun close() = server.stop(0)
}
