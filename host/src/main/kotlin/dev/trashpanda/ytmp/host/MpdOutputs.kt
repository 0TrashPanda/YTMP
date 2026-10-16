package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.OutputDevice
import dev.trashpanda.ytmp.core.OutputRoom
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.protocol.OutputKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.net.InetAddress

/** An MPD (Music Player Daemon) on the network. */
data class MpdAddress(val id: String, val name: String, val host: String, val port: Int = MpdPlayer.DEFAULT_PORT)

/**
 * MPD outputs: the MPDs the host knows about (say a Raspberry Pi with a Bluetooth speaker), and
 * one driver per room that keeps the MPDs the room plays on in step with it, like
 * [SonosOutputs]. MPD fetches the audio from the host's `/api/audio/…`, so [audioBaseUrl] must
 * be an address it can reach. See docs/implementation/mpd.md.
 */
class MpdOutputs(
    private val scope: CoroutineScope,
    /** Base URL of this host as seen from an MPD that reached us at [InetAddress]. */
    private val audioBaseUrl: (localAddress: InetAddress) -> String,
) : OutputDriver {
    private val known = MutableStateFlow<Map<String, MpdAddress>>(emptyMap())
    /** MPDs that feed amplifier zones: they're offered as those zones ([ZoneOutputs]), not by themselves. */
    private val hidden = MutableStateFlow<Set<String>>(emptySet())
    private val drivers = HashMap<String, Job>()

    override val devices: StateFlow<List<OutputDevice>> = combine(known, hidden) { all, hide ->
        all.values.filter { it.id !in hide }.sortedBy { it.name }.map { OutputDevice(it.id, it.name, OutputKind.MPD) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun add(device: MpdAddress) {
        known.update { it + (device.id to device) }
    }

    fun remove(id: String) {
        known.update { it - id }
    }

    /** These MPDs (by id) aren't offered by themselves. */
    fun setHidden(ids: Set<String>) {
        hidden.value = ids
    }

    /**
     * Adds the MPD at [host]. [name]: from the config or its mDNS name; without one (or with
     * MPD's default "Music Player @ …") it's named after its audio output ("Bluetooth Speaker").
     * [retry]: an MPD from the config that's off now is tried again every minute.
     */
    fun addHost(host: String, port: Int = MpdPlayer.DEFAULT_PORT, name: String? = null, retry: Boolean = false) {
        val id = idFor(host, port)
        scope.launch {
            while (true) {
                try {
                    val outputs = MpdPlayer(host, port).outputs()
                    add(MpdAddress(id, displayName(name, outputs, host), host, port))
                    return@launch
                } catch (e: MpdException) {
                    log.warn("No MPD at {}:{}: {}", host, port, e.message)
                    if (!retry) return@launch
                }
                delay(RETRY_ADD_MS)
            }
        }
    }

    /** Starts a driver for every room, now and as rooms are created. */
    fun attach(rooms: RoomManager) {
        scope.launch {
            rooms.list.collect { list ->
                val codes = list.map { it.code }.toSet()
                synchronized(drivers) {
                    for (code in drivers.keys - codes) drivers.remove(code)?.cancel()
                    for (code in codes - drivers.keys) rooms[code]?.let { drivers[code] = scope.launch { drive(it) } }
                }
            }
        }
    }

    override fun play(room: OutputRoom): Job = scope.launch { drive(room) }

    private suspend fun drive(room: OutputRoom) {
        val changed = Channel<Unit>(Channel.CONFLATED)
        room.addChangeListener { changed.trySend(Unit) }
        val sessions = HashMap<String, MpdSession>()
        try {
            while (true) {
                withTimeoutOrNull(SYNC_INTERVAL_MS) { changed.receive() }
                val view = room.view()
                for (id in sessions.keys - view.activeOutputs.keys) sessions.remove(id)?.stop()
                for ((id, volume) in view.activeOutputs) {
                    val device = known.value[id]?.takeIf { id !in hidden.value } ?: continue
                    val session = sessions.getOrPut(id) { MpdSession(device.name, device.host, device.port, audioBaseUrl) }
                    try {
                        session.sync(view, volume, report = room::notice, ready = { room.outputReady(id, it) }) { actual -> room.reportOutputVolume(id, actual) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: OutputTakenOver) {
                        // Leave it be: no stop, someone else plays there now.
                        log.info("MPD {}: {}", device.name, e.message)
                        sessions.remove(id)
                        room.outputTakenOver(id)
                    } catch (e: Exception) {
                        log.warn("MPD {} failed: {}", device.name, e.message)
                        session.failedAt = System.currentTimeMillis()
                        // A new song doesn't wait for it.
                        if (view.starting) view.current?.let { room.outputReady(id, it.itemId) }
                    }
                }
            }
        } finally {
            sessions.values.forEach { runCatching { it.stop() } }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(MpdOutputs::class.java)
        private const val SYNC_INTERVAL_MS = 2_000L
        private const val RETRY_ADD_MS = 60_000L

        fun idFor(host: String, port: Int = MpdPlayer.DEFAULT_PORT) = if (port == MpdPlayer.DEFAULT_PORT) "mpd:$host" else "mpd:$host:$port"

        /** MPD's mDNS name unless it's the default one, else its one enabled output, else the machine. */
        internal fun displayName(name: String?, outputs: List<MpdOutput>, host: String): String {
            val default = name?.startsWith(DEFAULT_ZEROCONF_NAME) == true
            if (!name.isNullOrBlank() && !default) return name
            outputs.filter { it.enabled }.singleOrNull()?.let { return it.name }
            return name?.substringAfter("@", "")?.trim()?.ifEmpty { null } ?: host
        }

        /** MPD's `zeroconf_name` is "Music Player @ %h" unless set. */
        private const val DEFAULT_ZEROCONF_NAME = "Music Player"
    }
}
