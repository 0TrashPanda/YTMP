package dev.trashpanda.ytmp

import android.util.Log
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Event
import dev.trashpanda.ytmp.protocol.NowPlaying
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.RoomState
import dev.trashpanda.ytmp.protocol.ServerMessage
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The room as [RoomFollower] sees it. */
data class FollowedRoom(
    val link: RoomLink,
    val participantId: String,
    val state: RoomState,
    /** Host clock minus device clock, in ms. */
    val clockOffset: Double,
) {
    /** Nobody else plays along (like the web app's `listeningAlone`). */
    val alone: Boolean
        get() = state.participants.none { it.id != participantId && it.online && it.listening } && state.outputs.none { it.active }
}

/**
 * The player's own connection to the room, attached to the page's participant.
 *
 * Android freezes the app's web page after a few minutes in the background (screen off),
 * and then room events no longer reach the player through the page: it would stop at the
 * end of the song. This connection keeps following the room natively. It only tracks what
 * playback needs, and sends media button commands.
 */
class RoomFollower {
    private val scope = CoroutineScope(Dispatchers.IO)
    private val client = HttpClient(CIO) { install(WebSockets) { pingIntervalMillis = 20_000 } }

    private val _room = MutableStateFlow<FollowedRoom?>(null)

    /** The followed room, or null while not connected (then the page's target is used). */
    val room: StateFlow<FollowedRoom?> = _room

    private var link: RoomLink? = null
    private var job: Job? = null

    @Volatile
    private var session: DefaultClientWebSocketSession? = null
    private var nextCommandId = 1

    /**
     * Play or Pause pressed while the connection was down (e.g. on the lock screen after a long
     * pause, when the phone had dropped Wi-Fi): sent once it's back, if that's soon enough.
     * Only these two, as sending them twice (the page may send it too) does no harm.
     */
    @Volatile
    private var pending: Pair<String, Long>? = null

    /** Follows the room in [link], or stops following with null. */
    fun follow(link: RoomLink?) {
        if (link == this.link) return
        Log.d(TAG, if (link != null) "follow room ${link.code} at ${link.hostUrl}" else "stop following")
        this.link = link
        job?.cancel()
        session = null
        _room.value = null
        if (link != null) job = scope.launch { run(link) }
    }

    /** Sends a room command (e.g. `{"kind":"Skip"}`). False when not connected. */
    fun command(json: String): Boolean {
        val session = session
        if (session == null || _room.value == null) {
            if (link != null && (json.contains("\"Play\"") || json.contains("\"Pause\""))) pending = json to System.currentTimeMillis()
            return false
        }
        return session.sendCommand(json)
    }

    private fun DefaultClientWebSocketSession.sendCommand(json: String): Boolean {
        val message = buildJsonObject {
            put("type", "command")
            put("id", "n${nextCommandId++}")
            put("command", Json.parseToJsonElement(json))
        }
        return outgoing.trySend(Frame.Text(message.toString())).isSuccess
    }

    fun close() {
        follow(null)
        scope.cancel()
        client.close()
    }

    private suspend fun run(link: RoomLink) {
        var retryDelay = 1_000L
        while (scope.isActive) {
            val rejected = try {
                connect(link) { retryDelay = 1_000L }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "room ${link.code}: connection failed: $e")
                false
            } finally {
                session = null
                _room.value = null
            }
            // Not (or no longer) in the room; the page will tell us when that changes.
            if (rejected) return
            delay(retryDelay)
            retryDelay = minOf(retryDelay * 2, 10_000L)
        }
    }

    /** One connection. Returns true when the host rejected us (don't retry). */
    private suspend fun connect(link: RoomLink, onWelcome: () -> Unit): Boolean {
        val url = link.hostUrl.trimEnd('/').replaceFirst(Regex("^http"), "ws") + "/ws"
        var rejected = false
        client.webSocket(url) {
            session = this
            send(ClientMessage.Attach(PROTOCOL_VERSION, link.code, link.guestToken))

            var participantId: String? = null
            var state: RoomState? = null
            var seq = 0L
            var waitingForSnapshot = false
            var clockOffset: Double? = null
            var bestRtt = Double.MAX_VALUE
            var pinger: Job? = null

            fun publish() {
                val id = participantId ?: return
                val s = state ?: return
                val offset = clockOffset ?: return
                _room.value = FollowedRoom(link, id, s, offset)
            }

            try {
                for (frame in incoming) {
                    val text = (frame as? Frame.Text)?.readText() ?: continue
                    when (val message = ProtocolJson.decodeFromString(ServerMessage.serializer(), text)) {
                        is ServerMessage.Welcome -> {
                            Log.d(TAG, "room ${link.code}: following")
                            onWelcome()
                            pending?.let { (json, at) ->
                                pending = null
                                if (System.currentTimeMillis() - at < PENDING_MAX_AGE_MS) {
                                    Log.d(TAG, "room ${link.code}: sending $json pressed while away")
                                    sendCommand(json)
                                }
                            }
                            participantId = message.participantId
                            state = message.state
                            seq = message.seq
                            waitingForSnapshot = false
                            // A few quick pings for a good first estimate, then one now and then (like the web app).
                            pinger?.cancel()
                            pinger = launch {
                                repeat(5) {
                                    send(ClientMessage.Ping(System.currentTimeMillis()))
                                    delay(300)
                                }
                                while (isActive) {
                                    send(ClientMessage.Ping(System.currentTimeMillis()))
                                    delay(15_000)
                                }
                            }
                        }
                        is ServerMessage.Snapshot -> {
                            state = message.state
                            seq = message.seq
                            waitingForSnapshot = false
                            publish()
                        }
                        is ServerMessage.EventMessage -> {
                            if (waitingForSnapshot) continue
                            if (message.seq != seq + 1) {
                                // Missed something; get the full state again.
                                waitingForSnapshot = true
                                send(ClientMessage.RequestSnapshot)
                                continue
                            }
                            seq = message.seq
                            state = state?.let { apply(it, message.event) }
                            publish()
                        }
                        is ServerMessage.Pong -> {
                            val now = System.currentTimeMillis()
                            val rtt = (now - message.clientTime).toDouble()
                            // The sample with the shortest round trip is the most accurate; let old ones age out.
                            bestRtt = minOf(bestRtt * 1.05, bestRtt + 20)
                            if (rtt <= bestRtt) {
                                bestRtt = rtt
                                clockOffset = message.hostTime + rtt / 2 - now
                                publish()
                            }
                        }
                        is ServerMessage.Result -> message.error?.let { Log.w(TAG, "room ${link.code}: command ${message.id}: ${it.message}") }
                        is ServerMessage.Rejected -> {
                            Log.d(TAG, "room ${link.code}: rejected (${message.reason})")
                            rejected = true
                            break
                        }
                    }
                }
            } finally {
                pinger?.cancel()
            }
        }
        return rejected
    }

    private suspend fun DefaultClientWebSocketSession.send(message: ClientMessage) =
        send(Frame.Text(ProtocolJson.encodeToString(ClientMessage.serializer(), message)))

    companion object {
        private const val TAG = "YtmpFollow"
        private const val PENDING_MAX_AGE_MS = 15_000L

        /** Applies the events playback cares about; the rest only move [seq] along. */
        fun apply(s: RoomState, e: Event): RoomState = when (e) {
            is Event.NowPlayingChanged -> s.copy(nowPlaying = e.item?.let { NowPlaying(it, null) })
            is Event.StreamReady -> {
                val now = s.nowPlaying
                if (now?.item?.itemId == e.itemId) s.copy(nowPlaying = now.copy(streamUrl = e.streamUrl)) else s
            }
            is Event.PlaybackChanged -> s.copy(playback = e.playback)
            is Event.OutputsChanged -> s.copy(outputs = e.outputs)
            is Event.ParticipantJoined, is Event.ParticipantUpdated -> {
                val p = if (e is Event.ParticipantJoined) e.participant else (e as Event.ParticipantUpdated).participant
                val index = s.participants.indexOfFirst { it.id == p.id }
                s.copy(participants = if (index >= 0) s.participants.toMutableList().also { it[index] = p } else s.participants + p)
            }
            is Event.ParticipantLeft -> s.copy(participants = s.participants.filter { it.id != e.participantId })
            else -> s
        }
    }
}
