package dev.trashpanda.ytmp.cast

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A connection to one Cast device: TLS to port 8009, framed [CastMessage]s, heartbeats, and
 * request/response matching by `requestId`.
 */
class CastChannel private constructor(private val socket: SSLSocket) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val output = socket.outputStream
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
    private val nextRequestId = AtomicInteger(1)
    private val _messages = MutableSharedFlow<Pair<String, JsonObject>>(extraBufferCapacity = 64)
    private val closed = CompletableDeferred<Unit>()

    /** Every JSON message from the device, with its namespace. */
    val messages: SharedFlow<Pair<String, JsonObject>> = _messages

    /** This host's address on the network the device is on: the address the device can reach us at. */
    val localAddress: InetAddress get() = socket.localAddress

    val isOpen: Boolean get() = !closed.isCompleted

    private fun start() {
        scope.launch { readLoop() }
        scope.launch { heartbeat() }
        connect(RECEIVER)
    }

    /** Opens a virtual connection to [destination] (the receiver, or a running app's transport ID). */
    fun connect(destination: String) {
        send(NS_CONNECTION, destination, JsonObject(mapOf("type" to JsonPrimitive("CONNECT"))))
    }

    fun send(namespace: String, destination: String, payload: JsonObject) {
        val message = CastMessage(SENDER, destination, namespace, payload.toString())
        synchronized(output) {
            output.write(message.encode())
            output.flush()
        }
    }

    /** Sends a request and waits for the reply with the same `requestId`. */
    suspend fun request(namespace: String, destination: String, payload: JsonObject, timeout: Duration = 10.seconds): JsonObject {
        val id = nextRequestId.getAndIncrement()
        val reply = CompletableDeferred<JsonObject>()
        pending[id] = reply
        try {
            withContext(Dispatchers.IO) { send(namespace, destination, JsonObject(payload + ("requestId" to JsonPrimitive(id)))) }
            return withTimeout(timeout) { reply.await() }
        } finally {
            pending.remove(id)
        }
    }

    suspend fun awaitClosed() = closed.await()

    fun close() {
        if (closed.complete(Unit)) {
            runCatching { send(NS_CONNECTION, RECEIVER, JsonObject(mapOf("type" to JsonPrimitive("CLOSE")))) }
            runCatching { socket.close() }
            pending.values.forEach { it.completeExceptionally(IOException("Cast connection closed")) }
            scope.cancel()
        }
    }

    private fun readLoop() {
        val input = DataInputStream(socket.inputStream)
        try {
            while (true) {
                val message = CastMessage.read(input)
                val json = runCatching { Json.parseToJsonElement(message.payload).jsonObject }.getOrNull() ?: continue
                if (message.namespace == NS_HEARTBEAT && json.type == "PING") {
                    send(NS_HEARTBEAT, message.sourceId, JsonObject(mapOf("type" to JsonPrimitive("PONG"))))
                    continue
                }
                if (message.namespace == NS_CONNECTION && json.type == "CLOSE" && message.sourceId == RECEIVER) break
                json["requestId"]?.jsonPrimitive?.intOrNull?.let { pending[it]?.complete(json) }
                _messages.tryEmit(message.namespace to json)
            }
        } catch (_: IOException) {
            // Connection lost.
        } finally {
            close()
        }
    }

    private suspend fun heartbeat() {
        while (scope.isActive) {
            delay(5.seconds)
            runCatching { send(NS_HEARTBEAT, RECEIVER, JsonObject(mapOf("type" to JsonPrimitive("PING")))) }
                .onFailure { close() }
        }
    }

    companion object {
        const val RECEIVER = "receiver-0"
        private const val SENDER = "sender-ytmp"
        const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
        const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
        const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
        const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

        /** Connects to a Cast device. Cast devices use self-signed certificates, so any certificate is accepted. */
        suspend fun open(host: String, port: Int = 8009, timeout: Duration = 5.seconds): CastChannel = withContext(Dispatchers.IO) {
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(TrustEverything), SecureRandom()) }
            val socket = context.socketFactory.createSocket() as SSLSocket
            socket.connect(InetSocketAddress(host, port), timeout.inWholeMilliseconds.toInt())
            socket.startHandshake()
            CastChannel(socket).also { it.start() }
        }

        private object TrustEverything : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }
}

internal val JsonObject.type: String? get() = (this["type"] as? JsonPrimitive)?.content
