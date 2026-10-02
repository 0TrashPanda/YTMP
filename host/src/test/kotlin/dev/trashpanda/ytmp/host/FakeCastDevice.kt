package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.cast.CastChannel
import dev.trashpanda.ytmp.cast.CastMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.io.File
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/** A pretend Cast device: answers like Google's Default Media Receiver, and records what it was asked. */
class FakeCastDevice : AutoCloseable {
    /** (namespace, type) of every request, in order. */
    val requests = CopyOnWriteArrayList<Pair<String, JsonObject>>()
    @Volatile var playerState = "IDLE"
    @Volatile var currentTime = 0.0
    @Volatile var volume = 0.5
    @Volatile private var appRunning = false
    @Volatile var contentId: String? = null

    /** URLs with this prefix fail to load (like a link the device can't use). */
    @Volatile var rejectUrlsStartingWith: String? = null

    /** While playing a URL with this prefix, the player drops to IDLE/ERROR (a lost stream). */
    @Volatile var dropUrlsStartingWith: String? = null

    /** The device's queue: (item id, media). */
    private val queue = CopyOnWriteArrayList<Pair<Int, JsonObject>>()
    @Volatile private var currentIndex = 0
    private var nextItemId = 1

    /** Tags of the queue (what the sender put in customData), in order, and the current one. */
    val queueTags: List<String?> get() = queue.map { tag(it.second) }
    val currentTag: String? get() = queue.getOrNull(currentIndex)?.let { tag(it.second) }

    private fun tag(media: JsonObject) = media["customData"]?.jsonObject?.get("ytmpItemId")?.jsonPrimitive?.content

    /** Someone pressed next (or previous) on the device or in Google Home. */
    fun press(forward: Boolean) {
        val target = currentIndex + if (forward) 1 else -1
        if (target !in queue.indices) return
        currentIndex = target
        contentId = queue[target].second["contentId"]?.jsonPrimitive?.content
        currentTime = 0.0
        playerState = "PLAYING"
    }

    private fun setQueue(items: List<JsonObject>, start: Int) {
        queue.clear()
        for (media in items) queue += nextItemId++ to media
        currentIndex = start
        contentId = queue.getOrNull(start)?.second?.get("contentId")?.jsonPrimitive?.content
    }

    private fun statusJson(id: String?, state: String = playerState, extra: String = ""): String {
        val current = queue.getOrNull(currentIndex)
        val media = current?.second?.toString() ?: "null"
        return """{"type":"MEDIA_STATUS","requestId":$id,"status":[{"mediaSessionId":1,"playerState":"$state","currentTime":$currentTime,"currentItemId":${current?.first},"media":$media$extra}]}"""
    }

    private val server: SSLServerSocket = run {
        val keystore = File.createTempFile("fakecast", ".p12").apply { delete(); deleteOnExit() }
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        ProcessBuilder(
            keytool, "-genkeypair", "-alias", "cast", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
            "-keystore", keystore.path, "-storetype", "PKCS12", "-storepass", "secret", "-keypass", "secret", "-dname", "CN=fake-cast",
        ).redirectErrorStream(true).start().waitFor()
        val store = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, "secret".toCharArray()) } }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "secret".toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }
        context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
    }

    val port: Int get() = server.localPort

    private val connections = CopyOnWriteArrayList<java.io.OutputStream>()

    /** Someone changed the volume on the device itself: tell connected senders, like a real device. */
    fun changeVolumeExternally(level: Double) {
        volume = level
        val apps = if (appRunning) """[{"appId":"CC1AD845","transportId":"web-1","sessionId":"s-1"}]""" else "[]"
        val status = """{"type":"RECEIVER_STATUS","requestId":0,"status":{"volume":{"level":$level,"muted":false},"applications":$apps}}"""
        for (output in connections) {
            synchronized(output) {
                output.write(CastMessage("receiver-0", "*", CastChannel.NS_RECEIVER, status).encode())
                output.flush()
            }
        }
    }

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    val input = DataInputStream(socket.inputStream)
                    val output = socket.outputStream
                    connections += output
                    runCatching {
                        while (true) {
                            val message = CastMessage.read(input)
                            val json = Json.parseToJsonElement(message.payload).jsonObject
                            val reply = answer(message.namespace, json) ?: continue
                            synchronized(output) {
                                output.write(CastMessage(message.destinationId, message.sourceId, message.namespace, reply).encode())
                                output.flush()
                            }
                        }
                    }
                }
            }
        }
    }

    fun types(namespace: String): List<String> =
        requests.filter { it.first == namespace }.mapNotNull { it.second["type"]?.jsonPrimitive?.content }

    private fun answer(namespace: String, request: JsonObject): String? {
        val type = request["type"]?.jsonPrimitive?.content ?: return null
        val id = request["requestId"]?.jsonPrimitive?.content
        if (namespace == CastChannel.NS_HEARTBEAT) return if (type == "PING") """{"type":"PONG"}""" else null
        if (namespace == CastChannel.NS_CONNECTION) return null
        requests += namespace to request
        return when (namespace) {
            CastChannel.NS_RECEIVER -> {
                when (type) {
                    "LAUNCH" -> appRunning = true
                    "STOP" -> appRunning = false
                    "SET_VOLUME" -> request["volume"]!!.jsonObject["level"]!!.jsonPrimitive.doubleOrNull?.let { volume = it }
                }
                val apps = if (appRunning) """[{"appId":"CC1AD845","transportId":"web-1","sessionId":"s-1"}]""" else "[]"
                """{"type":"RECEIVER_STATUS","requestId":$id,"status":{"volume":{"level":$volume,"muted":false},"applications":$apps}}"""
            }
            CastChannel.NS_MEDIA -> {
                when (type) {
                    "LOAD", "QUEUE_LOAD" -> {
                        val items = if (type == "LOAD") listOf(request["media"]!!.jsonObject)
                        else request["items"]!!.jsonArray.map { it.jsonObject["media"]!!.jsonObject }
                        val start = request["startIndex"]?.jsonPrimitive?.intOrNull ?: 0
                        val url = items[start]["contentId"]!!.jsonPrimitive.content
                        if (rejectUrlsStartingWith?.let(url::startsWith) == true) return """{"type":"LOAD_FAILED","requestId":$id}"""
                        setQueue(items, start)
                        currentTime = request["currentTime"]!!.jsonPrimitive.doubleOrNull ?: 0.0
                        playerState = if (request["autoplay"]?.jsonPrimitive?.booleanOrNull == true) "PLAYING" else "PAUSED"
                    }
                    "QUEUE_GET_ITEM_IDS" -> return """{"type":"QUEUE_ITEM_IDS","requestId":$id,"itemIds":${queue.map { it.first }}}"""
                    "QUEUE_REMOVE" -> {
                        val ids = request["itemIds"]!!.jsonArray.map { it.jsonPrimitive.intOrNull }
                        val current = queue[currentIndex].first
                        queue.removeIf { it.first in ids && it.first != current }
                        currentIndex = queue.indexOfFirst { it.first == current }
                    }
                    "QUEUE_INSERT" -> {
                        val items = request["items"]!!.jsonArray.map { nextItemId++ to it.jsonObject["media"]!!.jsonObject }
                        val before = request["insertBefore"]?.jsonPrimitive?.intOrNull
                        val current = queue[currentIndex].first
                        val at = before?.let { b -> queue.indexOfFirst { it.first == b } }?.takeIf { it >= 0 } ?: queue.size
                        queue.addAll(at, items)
                        currentIndex = queue.indexOfFirst { it.first == current }
                    }
                    "PLAY" -> playerState = "PLAYING"
                    "PAUSE" -> playerState = "PAUSED"
                    "SEEK" -> currentTime = request["currentTime"]!!.jsonPrimitive.doubleOrNull ?: 0.0
                }
                // Only later status reports drop the stream, like a device losing it mid-song.
                val dropped = type == "GET_STATUS" && dropUrlsStartingWith?.let { contentId?.startsWith(it) } == true
                if (dropped) statusJson(id, "IDLE", ""","idleReason":"ERROR"""") else statusJson(id)
            }
            else -> null
        }
    }

    override fun close() = server.close()
}
