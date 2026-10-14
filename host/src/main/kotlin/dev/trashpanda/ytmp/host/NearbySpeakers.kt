package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.OutputRoom
import dev.trashpanda.ytmp.core.RoomView
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.RepeatMode
import dev.trashpanda.ytmp.protocol.RoomState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToLong

/**
 * A room on another host (a server) played on speakers near this phone, which that host can't
 * reach: Sonos speakers and Chromecasts on the phone's own network. The app follows the room
 * over its own connection ([RoomSource]) and drives the speakers with the same drivers as the
 * phone's own rooms ([CastOutputs.play], [SonosOutputs.play]). The speakers get the audio from
 * this phone (its `/api/audio/…`): the host's stream links only work on the host's own
 * internet connection. The room hears about it ([Command.SetSpeakers]), so it keeps playing
 * while only these speakers play. One room at a time.
 */
class NearbySpeakers(
    private val scope: CoroutineScope,
    private val casts: CastOutputs,
    private val sonos: SonosOutputs,
) {
    private var room: Followed? = null
    private val _playing = MutableStateFlow<NearbyPlaying?>(null)
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** What plays where now, or null. */
    val playing: StateFlow<NearbyPlaying?> = _playing

    /** Problems to show (a speaker that couldn't play a song, or that another app took). */
    val notices: SharedFlow<String> = _notices

    /**
     * Plays the room [roomCode] on the speaker [deviceId] too. [source] connects to the room;
     * it's only called when no speaker plays this room yet (another room's speakers stop).
     */
    @Synchronized
    fun turnOn(roomCode: String, deviceId: String, name: String, source: () -> RoomSource) {
        val current = room?.takeIf { it.code == roomCode } ?: run {
            room?.end()
            Followed(roomCode, source()).also { room = it }
        }
        current.add(deviceId, name)
    }

    @Synchronized
    fun turnOff(deviceId: String) {
        val current = room ?: return
        current.remove(deviceId)
        if (current.isEmpty()) {
            current.end()
            room = null
        }
    }

    fun setVolume(deviceId: String, volume: Double) {
        room?.setVolume(deviceId, volume.coerceIn(0.0, 1.0))
    }

    /** One followed room and the speakers it plays on. */
    private inner class Followed(override val code: String, private val source: RoomSource) : OutputRoom {
        /** Device id → (name, wanted volume). */
        private val speakers = MutableStateFlow<Map<String, Pair<String, Double?>>>(emptyMap())
        private val listeners = CopyOnWriteArrayList<() -> Unit>()
        private val jobs = ArrayList<Job>()

        /** The room's last known state (kept while the connection is briefly down), with host clock minus ours. */
        @Volatile
        private var last: Pair<RoomState, Double>? = null

        init {
            jobs += scope.launch {
                source.room.collect { followed ->
                    if (followed != null) last = followed
                    changed()
                }
            }
            // Tell the room on every (re)connection and every change: it forgets when we're gone.
            jobs += scope.launch {
                combine(source.room.map { it != null }.distinctUntilChanged(), speakers.map { all -> all.values.map { it.first } }.distinctUntilChanged()) { connected, names -> connected to names }
                    .collect { (connected, names) -> if (connected) source.command(Command.SetSpeakers(names)) }
            }
            jobs += casts.play(this)
            jobs += sonos.play(this)
        }

        fun add(deviceId: String, name: String) {
            speakers.update { it + (deviceId to (name to it[deviceId]?.second)) }
            publish()
            changed()
        }

        fun remove(deviceId: String) {
            speakers.update { it - deviceId }
            publish()
            changed()
        }

        fun isEmpty() = speakers.value.isEmpty()

        fun setVolume(deviceId: String, volume: Double) {
            speakers.update { all -> all[deviceId]?.let { all + (deviceId to (it.first to volume)) } ?: all }
            publish()
            changed()
        }

        /** The drivers stop what plays (the speakers are out of the room's view by now), then the connection goes. */
        fun end() {
            speakers.value = emptyMap()
            changed()
            source.command(Command.SetSpeakers(emptyList()))
            _playing.value = null
            scope.launch {
                delay(STOP_GRACE_MS)
                jobs.forEach { it.cancel() }
                source.close()
            }
        }

        private fun publish() {
            if (room !== this && room != null) return
            _playing.value = NearbyPlaying(code, speakers.value.map { (id, it) -> NearbySpeaker(id, it.first, it.second) })
        }

        private fun changed() = listeners.forEach { it() }

        override suspend fun view(): RoomView {
            val active = speakers.value.mapValues { it.value.second }
            val (state, clockOffset) = last ?: return RoomView(null, null, false, 0, System.currentTimeMillis(), active)
            return state.outputView(clockOffset, active)
        }

        override fun addChangeListener(listener: () -> Unit) {
            listeners += listener
        }

        override suspend fun notice(message: String) {
            log.info("Nearby speakers for room {}: {}", code, message)
            _notices.tryEmit(message)
        }

        override suspend fun reportOutputVolume(outputId: String, volume: Double) {
            val before = speakers.value[outputId] ?: return
            if (before.second == volume) return
            speakers.update { all -> all[outputId]?.let { all + (outputId to (it.first to volume)) } ?: all }
            publish()
        }

        override suspend fun outputTakenOver(outputId: String) {
            val name = speakers.value[outputId]?.first ?: return
            notice("$name is playing something else now, so the room stopped playing there")
            turnOff(outputId)
        }

        override suspend fun outputSkipped(fromItemId: String, forward: Boolean) {
            // Only if that's still the current song, so a skip isn't done twice.
            if (last?.first?.nowPlaying?.item?.itemId != fromItemId) return
            source.command(if (forward) Command.Skip else Command.Previous)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(NearbySpeakers::class.java)

        /** Time for the drivers to stop the speakers before their jobs end. */
        private const val STOP_GRACE_MS = 3_000L
    }
}

/** The app's own connection to a room on another host. */
interface RoomSource {
    /** The room's state, with the host's clock minus ours (ms); null while not connected. */
    val room: StateFlow<Pair<RoomState, Double>?>

    /** Sends a command; false when not connected. */
    fun command(command: Command): Boolean

    fun close()
}

/** The speakers near this phone that play [roomCode]. */
@Serializable
data class NearbyPlaying(val roomCode: String, val speakers: List<NearbySpeaker>)

/** [volume]: 0–1, null until the speaker told us. */
@Serializable
data class NearbySpeaker(val id: String, val name: String, val volume: Double?)

/**
 * A room's state (as a client follows it) as speaker drivers see it, in this device's clock
 * ([clockOffset]: the host's clock minus ours). No stream link: the host's links only work on
 * its own internet connection, so the speakers get the audio from this device.
 */
fun RoomState.outputView(clockOffset: Double, activeOutputs: Map<String, Double?>): RoomView {
    // Like the room's own view: a song counts once its stream is ready.
    val current = nowPlaying?.takeIf { it.streamUrl != null }?.item
    return RoomView(
        current = current,
        streamUrl = null,
        playing = playback.playing && current != null,
        positionMs = playback.positionMs,
        hostTimeMs = (playback.hostTimeMs - clockOffset).roundToLong(),
        activeOutputs = activeOutputs,
        previous = history.lastOrNull(),
        next = when {
            repeat == RepeatMode.ONE -> null
            queue.isNotEmpty() -> queue.first()
            repeat == RepeatMode.QUEUE -> null
            else -> autoplay.firstOrNull()
        },
    )
}
