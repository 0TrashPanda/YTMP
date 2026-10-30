package dev.trashpanda.ytmp.cast

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI

/** A TV (or projector, or stick) with a DIAL server: apps on it can be started from the network. */
data class DialDevice(
    /** The device's UDN, without `uuid:`. */
    val id: String,
    val name: String,
    val manufacturer: String?,
    val model: String?,
    /** Where its apps are, ending in `/` (`http://10.0.0.5:56789/apps/`). */
    val appsUrl: String,
)

/** The YouTube app on a DIAL device: whether it runs, and the screen id a remote pairs with. */
data class DialYoutube(val running: Boolean, val screenId: String?)

/**
 * DIAL (DIscovery And Launch), the protocol phones use to find a TV's YouTube app and start
 * it: SSDP finds the device, its description gives the apps' address, and `<apps>/YouTube`
 * tells whether the app runs and, while it does, its *screen id* for [YoutubeLounge].
 * Chromecasts speak DIAL too; YTMP casts to those with the Cast protocol instead.
 */
object Dial {
    const val SEARCH_TARGET = "urn:dial-multiscreen-org:service:dial:1"

    /** Origin YouTube's DIAL apps expect: without it some refuse to answer (403). */
    private const val ORIGIN = "https://www.youtube.com"

    /** SSDP M-SEARCH for DIAL servers; returns their description URLs. Needs UDP multicast replies to get through. */
    fun search(timeoutMs: Long = 3_000): Set<String> {
        val found = HashSet<String>()
        MulticastSocket(null as InetSocketAddress?).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = 1_000
            val message = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $SEARCH_TARGET\r\n\r\n".toByteArray()
            // UDP gets lost: ask twice.
            repeat(2) { socket.send(DatagramPacket(message, message.size, InetAddress.getByName("239.255.255.250"), 1900)) }
            val buffer = ByteArray(2048)
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val headers = String(packet.data, 0, packet.length).lines()
                if (headers.none { it.contains(SEARCH_TARGET) }) continue
                headers.firstOrNull { it.startsWith("location:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.let { found += it }
            }
        }
        return found
    }

    /** The device behind a description URL (from [search]), or null if it isn't a DIAL device. */
    suspend fun describe(location: String): DialDevice? = withContext(Dispatchers.IO) {
        val response = request(location)
        if (response.code != 200) return@withContext null
        val appsUrl = response.headers["application-url"]?.trim()?.ifEmpty { null } ?: return@withContext null
        val xml = response.body
        DialDevice(
            id = tag(xml, "UDN")?.removePrefix("uuid:") ?: URI(location).host,
            name = tag(xml, "friendlyName") ?: URI(location).host,
            manufacturer = tag(xml, "manufacturer"),
            model = tag(xml, "modelName"),
            appsUrl = if (appsUrl.endsWith("/")) appsUrl else "$appsUrl/",
        )
    }

    /** The YouTube app on the device, or null if it has none. */
    suspend fun youtube(appsUrl: String): DialYoutube? = withContext(Dispatchers.IO) {
        val response = request(appsUrl + "YouTube")
        if (response.code != 200) return@withContext null
        DialYoutube(running = tag(response.body, "state") == "running", screenId = tag(response.body, "screenId")?.ifEmpty { null })
    }

    /**
     * Starts the YouTube app on the device (or brings it to the front) and waits for its
     * screen id; null if it didn't come up.
     */
    suspend fun launchYoutube(appsUrl: String, waitMs: Long = 20_000): String? {
        val code = withContext(Dispatchers.IO) { request(appsUrl + "YouTube", method = "POST", body = "").code }
        log.info("DIAL: launching YouTube at {}: {}", appsUrl, code)
        val end = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < end) {
            delay(1_000)
            val app = runCatching { youtube(appsUrl) }.getOrNull()
            if (app?.running == true && app.screenId != null) return app.screenId
        }
        return null
    }

    /** Whether [host] is a Cast device (it answers Cast's own setup API): YTMP plays there with the Cast protocol. */
    suspend fun isCastDevice(host: String): Boolean = withContext(Dispatchers.IO) {
        runCatching { request("http://$host:8008/setup/eureka_info", timeoutMs = 2_000).code == 200 }.getOrDefault(false)
    }

    private class Response(val code: Int, val headers: Map<String, String>, val body: String)

    /**
     * Plain HTTP/1.1 over a socket: Java's HttpURLConnection drops the `Origin` header, which
     * DIAL servers check. DIAL is plain `http` on the local network.
     */
    private fun request(url: String, method: String = "GET", body: String? = null, timeoutMs: Int = 5_000): Response {
        val uri = URI(url)
        val port = if (uri.port > 0) uri.port else 80
        Socket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            val bytes = body?.toByteArray() ?: ByteArray(0)
            val head = buildString {
                append("$method ${uri.rawPath.ifEmpty { "/" }}${uri.rawQuery?.let { "?$it" } ?: ""} HTTP/1.1\r\n")
                append("Host: ${uri.host}:$port\r\n")
                append("Origin: $ORIGIN\r\n")
                append("Connection: close\r\n")
                if (body != null) append("Content-Type: text/plain; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n")
                append("\r\n")
            }
            socket.getOutputStream().apply {
                write(head.toByteArray())
                write(bytes)
                flush()
            }
            val raw = socket.getInputStream().readBytes()
            val split = indexOf(raw, "\r\n\r\n".toByteArray()).takeIf { it >= 0 } ?: raw.size
            val lines = String(raw, 0, split).split("\r\n")
            val code = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            var content = raw.copyOfRange(minOf(split + 4, raw.size), raw.size)
            if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) content = unchunk(content)
            return Response(code, headers, content.decodeToString())
        }
    }

    private fun unchunk(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var at = 0
        while (at < data.size) {
            val lineEnd = indexOf(data, "\r\n".toByteArray(), at).takeIf { it >= 0 } ?: break
            val size = String(data, at, lineEnd - at).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size == 0) break
            val start = lineEnd + 2
            out.write(data, start, minOf(size, data.size - start))
            at = start + size + 2
        }
        return out.toByteArray()
    }

    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    internal fun tag(xml: String, name: String): String? =
        Regex("<$name(?:\\s[^>]*)?>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()
            ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")?.replace("&apos;", "'")

    private val log = LoggerFactory.getLogger(Dial::class.java)
}
