package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.OutputDevice
import dev.trashpanda.ytmp.core.OutputRoom
import dev.trashpanda.ytmp.core.RoomView
import dev.trashpanda.ytmp.protocol.OutputKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    scope: CoroutineScope,
    /** Base URL of this host as seen from a speaker that reached us at [InetAddress]. */
    private val audioBaseUrl: (localAddress: InetAddress) -> String,
) : DeviceDriver(scope, "Sonos") {
    private val known = MutableStateFlow<Map<String, SonosAddress>>(emptyMap())

    /** The speakers as room outputs. */
    override val devices: StateFlow<List<OutputDevice>> = known
        .map { all -> all.values.sortedBy { it.name }.map { OutputDevice(it.id, it.name, OutputKind.SONOS) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun add(device: SonosAddress) {
        known.update { it + (device.id to device) }
    }

    fun remove(id: String) {
        known.update { it - id }
    }

    /** Adds a speaker by address, named after its room ("Woonkamer"). */
    fun addHost(host: String) {
        scope.launch {
            runCatching { SonosPlayer(host).info() }
                .onSuccess { add(SonosAddress("sonos:$host", it.roomName, host)) }
                .onFailure { log.warn("No Sonos at {}: {}", host, it.message) }
        }
    }

    /**
     * Looks for Sonos speakers with SSDP now and every few minutes. Needs UDP multicast replies to
     * get through. A speaker it found that doesn't answer [MISSED_SEARCHES] searches in a row is
     * gone (switched off, moved); the ones in [keep] (the configured ones) stay.
     */
    fun discover(keep: Set<String> = emptySet()) {
        scope.launch(Dispatchers.IO) {
            val missed = HashMap<String, Int>()
            while (true) {
                val found = runCatching { ssdpSearch() }.onFailure { log.debug("Sonos discovery failed: {}", it.message) }.getOrNull()
                if (found != null) {
                    for (host in found) {
                        missed[host] = 0
                        if (known.value.values.none { it.host == host }) addHost(host)
                    }
                    for (host in missed.keys - found - keep) {
                        val count = missed.getValue(host) + 1
                        missed[host] = count
                        if (count < MISSED_SEARCHES) continue
                        missed.remove(host)
                        known.value.values.filter { it.host == host }.forEach {
                            log.info("Sonos {} is gone", it.name)
                            remove(it.id)
                        }
                    }
                }
                delay(DISCOVERY_INTERVAL_MS)
            }
        }
    }

    override fun has(id: String) = id in known.value

    override fun name(id: String) = known.value[id]?.name ?: id

    override fun session(id: String, resuming: Boolean): DeviceSession = Session(known.value.getValue(id))

    /** One room playing on one Sonos speaker. */
    private inner class Session(private val device: SonosAddress) : DeviceSession {
        private val player = SonosPlayer(device.host, device.port)
        private var loadedItemId: String? = null
        private var settledAt = 0L
        private var appliedVolume: Double? = null
        private var lastVolumeCheck = 0L
        /** The current song couldn't play here. */
        private var songFailed = false
        private var failedAt = 0L
        /** Loaded while the room waited for everyone to load the song: it plays when the room starts it. */
        private var waitingToStart = false

        /** It waits a little before trying again. */
        override fun failed(): Boolean {
            failedAt = System.currentTimeMillis()
            return false
        }

        override suspend fun sync(view: RoomView, wantedVolume: Double?, room: OutputRoom, outputId: String) {
            val report: suspend (String) -> Unit = room::notice
            val ready: suspend (String) -> Unit = { room.outputReady(outputId, it) }
            val reportVolume: suspend (Double) -> Unit = { room.reportOutputVolume(outputId, it) }
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
                songFailed = false
                settledAt = now + SETTLE_MS
                val url = audioBaseUrl(player.localAddress) + "/api/audio/" + URLEncoder.encode(item.song.id, "UTF-8")
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
                    waitingToStart = view.starting
                    if (view.playing) player.play()
                    if (expected > SEEK_THRESHOLD_MS) player.seek(expected + if (view.starting) 0 else LOAD_LEAD_MS)
                } catch (e: SonosException) {
                    songFailed = true
                    log.warn("Sonos {} couldn't play {}: {}", device.name, item.song.title, e.message)
                    report("${device.name} couldn't play \"${item.song.title}\": ${e.message}")
                }
                // Loaded or failed: a new song doesn't wait for it any longer.
                if (view.starting) ready(item.itemId)
                return
            }
            if (songFailed) return
            if (waitingToStart) {
                if (!view.playing) return
                // Everyone has the song: it starts now.
                waitingToStart = false
                player.play()
                settledAt = now + SETTLE_MS
                return
            }

            val status = player.status()
            // Playing something that isn't ours (Spotify, the Sonos app, …): someone else took it.
            val uri = status.trackUri
            if (now >= settledAt && !uri.isNullOrEmpty() && "/api/audio/" !in uri) throw OutputTakenOver("another app took it ($uri)")
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

        override suspend fun stop() {
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
        private const val SETTLE_MS = 6_000L
        private const val LOAD_LEAD_MS = 1_000L
        /** Starting a song this far in: seek there after loading. */
        private const val SEEK_THRESHOLD_MS = 3_000L
        private const val MAX_DRIFT_MS = 3_000L
        private const val VOLUME_CHECK_MS = 6_000L
        private const val RETRY_AFTER_MS = 10_000L
        private const val DISCOVERY_INTERVAL_MS = 5 * 60_000L
        /** SSDP answers can get lost: only this many missed searches in a row mean a speaker is gone. */
        private const val MISSED_SEARCHES = 3
    }
}
