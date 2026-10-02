package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.OutputDevice
import dev.trashpanda.ytmp.core.Room
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.core.RoomView
import dev.trashpanda.ytmp.protocol.OutputKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import kotlin.math.abs

/** A Sonos speaker on the network. */
data class SonosAddress(val id: String, val name: String, val host: String, val port: Int = 1400)

/**
 * Sonos outputs: the speakers the host knows about, and one driver per room that keeps the
 * Sonos speakers the room plays on in step with it. The room is in charge: song changes,
 * play/pause, seeking when it's clearly off, and volume. A speaker fetches the audio from the
 * host's `/api/audio/…`, so [audioBaseUrl] must be an address it can reach.
 * See docs/implementation/sonos.md.
 */
class SonosOutputs(
    private val scope: CoroutineScope,
    /** Base URL of this host as seen from a speaker that reached us at [InetAddress]. */
    private val audioBaseUrl: (localAddress: InetAddress) -> String,
) {
    private val known = MutableStateFlow<Map<String, SonosAddress>>(emptyMap())
    private val drivers = HashMap<String, Job>()

    /** The speakers as room outputs. */
    val devices: StateFlow<List<OutputDevice>> = known
        .map { all -> all.values.sortedBy { it.name }.map { OutputDevice(it.id, it.name, OutputKind.SONOS) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun add(device: SonosAddress) {
        known.update { it + (device.id to device) }
    }

    /** Adds a speaker by address, named after its room ("Woonkamer"). */
    fun addHost(host: String) {
        scope.launch {
            runCatching { SonosPlayer(host).info() }
                .onSuccess { add(SonosAddress("sonos:$host", it.roomName, host)) }
                .onFailure { log.warn("No Sonos at {}: {}", host, it.message) }
        }
    }

    /** Looks for Sonos speakers with SSDP now and every few minutes. Needs UDP multicast replies to get through. */
    fun discover() {
        scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { ssdpSearch() }.onFailure { log.debug("Sonos discovery failed: {}", it.message) }.getOrNull()
                    ?.forEach { host -> if (known.value.values.none { it.host == host }) addHost(host) }
                delay(DISCOVERY_INTERVAL_MS)
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

    private suspend fun drive(room: Room) {
        val changed = Channel<Unit>(Channel.CONFLATED)
        room.addChangeListener { changed.trySend(Unit) }
        val sessions = HashMap<String, Session>()
        try {
            while (true) {
                withTimeoutOrNull(SYNC_INTERVAL_MS) { changed.receive() }
                val view = room.view()
                for (id in sessions.keys - view.activeOutputs.keys) sessions.remove(id)?.stop()
                for ((id, volume) in view.activeOutputs) {
                    val device = known.value[id] ?: continue
                    val session = sessions.getOrPut(id) { Session(device) }
                    try {
                        session.sync(view, volume, report = room::notice) { actual -> room.reportOutputVolume(id, actual) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("Sonos {} failed: {}", device.name, e.message)
                        session.failedAt = System.currentTimeMillis()
                    }
                }
            }
        } finally {
            sessions.values.forEach { runCatching { it.stop() } }
        }
    }

    /** One room playing on one Sonos speaker. */
    private inner class Session(private val device: SonosAddress) {
        private val player = SonosPlayer(device.host, device.port)
        private var loadedItemId: String? = null
        private var settledAt = 0L
        private var appliedVolume: Double? = null
        private var lastVolumeCheck = 0L
        private var failed = false
        var failedAt = 0L

        suspend fun sync(view: RoomView, wantedVolume: Double?, report: suspend (String) -> Unit, reportVolume: suspend (Double) -> Unit) {
            val now = System.currentTimeMillis()
            if (now - failedAt < RETRY_AFTER_MS) return
            if (appliedVolume == null) {
                // First contact: the room's slider starts at the speaker's own volume.
                appliedVolume = player.volume().also { reportVolume(it) }
            }
            if (wantedVolume != null && wantedVolume != appliedVolume) {
                player.setVolume(wantedVolume)
                appliedVolume = wantedVolume
            } else if (now - lastVolumeCheck > VOLUME_CHECK_MS) {
                // Follow changes made in the Sonos app or on the speaker.
                lastVolumeCheck = now
                val actual = player.volume()
                if (actual != appliedVolume) {
                    appliedVolume = actual
                    reportVolume(actual)
                }
            }

            val item = view.current
            if (item == null) {
                if (loadedItemId != null) runCatching { player.pause() }
                return
            }
            val expected = view.positionAt(now)
            if (item.itemId != loadedItemId) {
                loadedItemId = item.itemId
                failed = false
                settledAt = now + SETTLE_MS
                val url = audioBaseUrl(player.localAddress) + "/api/audio/" + URLEncoder.encode(item.song.id, Charsets.UTF_8)
                try {
                    log.info("Sonos {}: loading {}", device.name, item.song.title)
                    player.setUri(
                        url,
                        title = item.song.title,
                        artist = item.song.artists.joinToString { it.name },
                        album = item.song.album?.name,
                        artUrl = item.song.thumbnails.maxByOrNull { it.width }?.url,
                        durationMs = item.song.durationMs,
                    )
                    if (view.playing) player.play()
                    if (expected > SEEK_THRESHOLD_MS) player.seek(expected + LOAD_LEAD_MS)
                } catch (e: SonosException) {
                    failed = true
                    log.warn("Sonos {} couldn't play {}: {}", device.name, item.song.title, e.message)
                    report("${device.name} couldn't play \"${item.song.title}\": ${e.message}")
                }
                return
            }
            if (failed) return

            val status = player.status()
            when {
                view.playing && status.state in setOf("PAUSED_PLAYBACK", "STOPPED") && now >= settledAt -> {
                    // Stopped mid-song (a hiccup, or someone pressed pause on the speaker): the room says play.
                    if (status.state == "STOPPED") loadedItemId = null else player.play()
                }
                !view.playing && status.state in setOf("PLAYING", "TRANSITIONING") -> player.pause()
            }
            if (now < settledAt || !view.playing || status.state != "PLAYING") return
            // Sonos reports whole seconds: only fix clearly audible drift.
            val drift = status.positionMs + (now - status.receivedAt) - expected
            if (abs(drift) > MAX_DRIFT_MS) {
                player.seek(expected + LOAD_LEAD_MS)
                settledAt = now + SETTLE_MS
            }
        }

        suspend fun stop() {
            if (loadedItemId != null) runCatching { player.stop() }
            loadedItemId = null
        }
    }

    /** SSDP M-SEARCH for Sonos ZonePlayers; returns their IP addresses. */
    private fun ssdpSearch(): Set<String> {
        val found = HashSet<String>()
        MulticastSocket(null as InetSocketAddress?).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(0))
            socket.soTimeout = 2_000
            val message = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n".toByteArray()
            socket.send(DatagramPacket(message, message.size, InetAddress.getByName("239.255.255.250"), 1900))
            val buffer = ByteArray(2048)
            val end = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < end) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    break
                }
                if ("ZonePlayer" in String(packet.data, 0, packet.length)) found += packet.address.hostAddress
            }
        }
        return found
    }

    companion object {
        private val log = LoggerFactory.getLogger(SonosOutputs::class.java)
        private const val SYNC_INTERVAL_MS = 2_000L
        private const val SETTLE_MS = 6_000L
        private const val LOAD_LEAD_MS = 1_000L
        /** Starting a song this far in: seek there after loading. */
        private const val SEEK_THRESHOLD_MS = 3_000L
        private const val MAX_DRIFT_MS = 3_000L
        private const val VOLUME_CHECK_MS = 6_000L
        private const val RETRY_AFTER_MS = 10_000L
        private const val DISCOVERY_INTERVAL_MS = 5 * 60_000L
    }
}
