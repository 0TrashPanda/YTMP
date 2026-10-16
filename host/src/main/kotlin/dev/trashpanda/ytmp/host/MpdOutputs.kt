package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.OutputDevice
import dev.trashpanda.ytmp.core.OutputRoom
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.core.RoomView
import dev.trashpanda.ytmp.protocol.OutputKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import java.net.InetAddress
import java.net.URLEncoder
import kotlin.math.abs

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
    private val drivers = HashMap<String, Job>()

    override val devices: StateFlow<List<OutputDevice>> = known
        .map { all -> all.values.sortedBy { it.name }.map { OutputDevice(it.id, it.name, OutputKind.MPD) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun add(device: MpdAddress) {
        known.update { it + (device.id to device) }
    }

    fun remove(id: String) {
        known.update { it - id }
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

    /** One room playing on one MPD. */
    private inner class Session(private val device: MpdAddress) {
        private val player = MpdPlayer(device.host, device.port)
        private var loadedItemId: String? = null
        private var settledAt = 0L
        /** 0–100, as last set or seen; null before the first check or without a mixer. */
        private var appliedVolume: Int? = null
        private var checkedVolume = false
        private var failed = false
        var failedAt = 0L
        private var lastAnchor: Pair<Long, Long>? = null
        /** Loaded while the room was paused or waited for everyone to load the song: it plays when the room does. */
        private var waitingToStart = false

        suspend fun sync(
            view: RoomView,
            wantedVolume: Double?,
            report: suspend (String) -> Unit,
            ready: suspend (itemId: String) -> Unit,
            reportVolume: suspend (Double) -> Unit,
        ) {
            val now = System.currentTimeMillis()
            if (now - failedAt < RETRY_AFTER_MS) return
            val status = player.status()
            syncVolume(status, wantedVolume, reportVolume)

            val item = view.current
            if (item == null) {
                if (loadedItemId != null && status.state == "play") player.pause()
                return
            }
            val expected = view.positionAt(now)
            if (item.itemId != loadedItemId) {
                loadedItemId = item.itemId
                failed = false
                settledAt = now + SETTLE_MS
                val url = audioBaseUrl(player.localAddress) + "/api/audio/" + URLEncoder.encode(item.song.id, Charsets.UTF_8)
                try {
                    log.info("MPD {}: loading {}", device.name, item.song.title)
                    player.load(url)
                    waitingToStart = !view.playing
                    if (view.playing) startAt(expected + if (view.starting) 0 else LOAD_LEAD_MS)
                } catch (e: MpdException) {
                    failed = true
                    log.warn("MPD {} couldn't play {}: {}", device.name, item.song.title, e.message)
                    report("${device.name} couldn't play \"${item.song.title}\": ${e.message}")
                }
                // Loaded or failed: a new song doesn't wait for it any longer.
                if (view.starting) ready(item.itemId)
                return
            }
            if (failed) return
            if (waitingToStart) {
                if (!view.playing) return
                // Everyone has the song, or the room plays again: it starts now, where the room is.
                waitingToStart = false
                startAt(expected)
                settledAt = now + SETTLE_MS
                return
            }

            // Playing something that isn't ours (its own library, another MPD app): someone else took it.
            val file = status.file
            if (now >= settledAt && file != null && "/api/audio/" !in file) throw OutputTakenOver("something else plays there ($file)")
            if (now >= settledAt && status.state == "stop" && status.error != null) {
                failed = true
                log.warn("MPD {} couldn't play {}: {}", device.name, item.song.title, status.error)
                report("${device.name} couldn't play \"${item.song.title}\": ${status.error}")
                return
            }
            when {
                view.playing && status.state == "pause" -> player.resume()
                view.playing && status.state == "stop" && now >= settledAt -> {
                    // Stopped mid-song (a hiccup, or its queue was cleared): load it again where
                    // the room is, unless the song is about to end anyway.
                    if (expected < item.song.durationMs - END_MARGIN_MS) loadedItemId = null
                }
                !view.playing && status.state == "play" -> player.pause()
            }
            // The room's own timeline changed (someone seeked or pressed play) since the last check.
            val anchor = view.positionMs to view.hostTimeMs
            val roomMoved = anchor != lastAnchor
            lastAnchor = anchor
            if (!view.playing || status.state != "play") return
            val drift = status.positionMs + (now - status.receivedAt) - expected
            // Right after loading or seeking MPD may still be fetching: only a seek in the room can't wait.
            if (abs(drift) > MAX_DRIFT_MS && (now >= settledAt || roomMoved)) {
                player.seek(expected + LOAD_LEAD_MS)
                settledAt = now + SETTLE_MS
            }
        }

        private suspend fun startAt(positionMs: Long) {
            player.start()
            if (positionMs > SEEK_THRESHOLD_MS) player.seek(positionMs)
        }

        /** The room's slider starts at MPD's own volume and follows changes made elsewhere (another MPD app). */
        private suspend fun syncVolume(status: MpdStatus, wantedVolume: Double?, reportVolume: suspend (Double) -> Unit) {
            val actual = status.volume ?: return // No mixer: the room shows no volume slider.
            val wanted = wantedVolume?.let { Math.round(it.coerceIn(0.0, 1.0) * 100).toInt() }
            when {
                !checkedVolume || (actual != appliedVolume && wanted == appliedVolume) -> {
                    checkedVolume = true
                    appliedVolume = actual
                    reportVolume(actual / 100.0)
                }
                wanted != null && wanted != actual -> {
                    player.setVolume(wanted / 100.0)
                    appliedVolume = wanted
                }
            }
        }

        suspend fun stop() {
            if (loadedItemId != null) runCatching { player.stop() }
            loadedItemId = null
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(MpdOutputs::class.java)
        private const val SYNC_INTERVAL_MS = 2_000L
        private const val SETTLE_MS = 6_000L
        private const val LOAD_LEAD_MS = 1_000L
        /** Starting a song this far in: seek there after starting. */
        private const val SEEK_THRESHOLD_MS = 3_000L
        private const val MAX_DRIFT_MS = 2_000L
        private const val END_MARGIN_MS = 5_000L
        private const val RETRY_AFTER_MS = 10_000L
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
