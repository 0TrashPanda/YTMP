package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.cast.CastChannel
import dev.trashpanda.ytmp.cast.CastMediaPlayer
import dev.trashpanda.ytmp.cast.CastMetadata
import dev.trashpanda.ytmp.cast.CastQueueItem
import dev.trashpanda.ytmp.core.OutputDevice
import dev.trashpanda.ytmp.core.Room
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.core.RoomView
import dev.trashpanda.ytmp.protocol.OutputKind
import dev.trashpanda.ytmp.protocol.QueueItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URLEncoder
import kotlin.math.abs

/** A Cast device on the network. */
data class CastDeviceAddress(val id: String, val name: String, val host: String, val port: Int = 8009)

/**
 * Chromecast outputs: the Cast devices the host knows about, and one driver per room that
 * keeps every Cast device the room plays on in step with the room.
 *
 * Cast devices fetch the audio themselves: first the direct stream URL (saves the host the
 * traffic), and if a device can't play that, from the host's `/api/audio/…` proxy for the rest
 * of the session. So [audioBaseUrl] must be an address the device can reach.
 */
class CastOutputs(
    private val scope: CoroutineScope,
    /** Base URL of this host as seen from a device that reached us at [InetAddress] (our side of the connection). */
    private val audioBaseUrl: (localAddress: InetAddress) -> String,
) {
    private val known = MutableStateFlow<Map<String, CastDeviceAddress>>(emptyMap())
    private val drivers = HashMap<String, Job>()

    /** The devices as room outputs. Pass to [RoomManager]. */
    val devices: StateFlow<List<OutputDevice>> = known
        .map { all -> all.values.sortedBy { it.name }.map { OutputDevice(it.id, it.name, OutputKind.CHROMECAST) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun add(device: CastDeviceAddress) {
        known.value = known.value + (device.id to device)
    }

    fun remove(id: String) {
        known.value = known.value - id
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
        val sessions = HashMap<String, CastSession>()
        try {
            while (true) {
                // React to changes right away, and check the devices regularly anyway.
                withTimeoutOrNull(SYNC_INTERVAL_MS) { changed.receive() }
                val view = room.view()
                for (id in sessions.keys - view.activeOutputs.keys) sessions.remove(id)?.stop()
                for ((id, volume) in view.activeOutputs) {
                    val device = known.value[id] ?: continue
                    val session = sessions.getOrPut(id) { CastSession(device) }
                    try {
                        session.sync(view, volume, report = room::notice, skipped = room::outputSkipped) { actual -> room.reportOutputVolume(id, actual) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log.warn("Cast device {} failed", device.name, e)
                        session.close()
                        sessions.remove(id) // Reconnect on the next round.
                    }
                }
            }
        } finally {
            sessions.values.forEach { it.close() }
        }
    }

    /** One room playing on one Cast device. */
    private inner class CastSession(private val device: CastDeviceAddress) {
        private var channel: CastChannel? = null
        private var player: CastMediaPlayer? = null
        private var loadedItemId: String? = null
        private var settledAt = 0L
        private var appliedVolume: Double? = null
        private var failed = false

        private var volumeWatcher: Job? = null

        /** The device couldn't play a direct URL, so it gets the host's proxy from now on. */
        private var useProxy = false

        /** The songs before and after the current one in the device's own queue (our item ids). */
        private var neighbors: Pair<String?, String?> = null to null

        suspend fun sync(
            view: RoomView,
            wantedVolume: Double?,
            report: suspend (String) -> Unit,
            skipped: suspend (fromItemId: String, forward: Boolean) -> Unit,
            reportVolume: suspend (Double) -> Unit,
        ) {
            val player = connect(reportVolume)
            if (wantedVolume != null && wantedVolume != appliedVolume) {
                player.setVolume(wantedVolume)
                appliedVolume = wantedVolume
            }

            // Did the device skip by itself (its buttons, its app, Google Home)? Then the room follows.
            val loaded = loadedItemId
            val status = if (loaded != null && !failed) player.status() else null
            val onDevice = status?.tag?.takeIf { it.isNotEmpty() }
            if (loaded != null && onDevice != null && onDevice != loaded && (onDevice == neighbors.first || onDevice == neighbors.second)) {
                log.info("Cast device {} went {}", device.name, if (onDevice == neighbors.second) "to the next song" else "back")
                loadedItemId = onDevice
                settledAt = System.currentTimeMillis() + SETTLE_MS
                skipped(loaded, onDevice == neighbors.second)
                return
            }

            val item = view.current
            if (item == null) {
                if (loadedItemId != null) player.pause()
                return
            }
            val now = System.currentTimeMillis()
            val expected = view.positionAt(now)
            if (item.itemId != loadedItemId) {
                load(player, item, view, expected, report)
                return
            }
            if (failed || status == null) return
            updateNeighbors(player, view, status.currentItemId)
            when {
                view.playing && status.playerState == "PAUSED" -> player.play()
                !view.playing && status.playerState in setOf("PLAYING", "BUFFERING") -> player.pause()
            }
            if (now < settledAt) return
            if (view.playing && status.playerState == "IDLE") {
                // The device stopped: a network hiccup, or it lost the direct URL. Load the song
                // again, through the host if the direct URL failed.
                if (status.idleReason == "ERROR" && !useProxy) {
                    useProxy = true
                    log.info("Cast device {} lost the direct stream; using the host from now on", device.name)
                }
                loadedItemId = null
            } else if (view.playing && status.playerState == "PLAYING") {
                // A Cast device can't be synced closely; only fix clearly audible drift.
                val drift = status.currentTimeMs + (now - status.receivedAt) - expected
                if (abs(drift) > MAX_DRIFT_MS) {
                    player.seek(expected + LOAD_LEAD_MS)
                    settledAt = now + SETTLE_MS
                }
            }
        }

        /** Our songs as items of the device's queue. Neighbors go through the host: their direct URLs aren't resolved yet. */
        private fun castItem(item: QueueItem, url: String? = null) = CastQueueItem(
            url = url ?: proxyUrl(item),
            contentType = "audio/mp4",
            metadata = CastMetadata(
                title = item.song.title,
                artist = item.song.artists.joinToString { it.name },
                album = item.song.album?.name,
                imageUrl = item.song.thumbnails.maxByOrNull { it.width }?.url,
            ),
            tag = item.itemId,
        )

        private fun proxyUrl(item: QueueItem) =
            audioBaseUrl(channel!!.localAddress) + "/api/audio/" + URLEncoder.encode(item.song.id, Charsets.UTF_8)

        /** Keeps the device's previous and next songs in step with the room, without interrupting the current one. */
        private suspend fun updateNeighbors(player: CastMediaPlayer, view: RoomView, currentId: Int?) {
            val wanted = view.previous?.itemId to view.next?.itemId
            if (wanted == neighbors || currentId == null) return
            neighbors = wanted
            try {
                player.queueRemove(player.queueItemIds() - currentId)
                view.previous?.let { player.queueInsert(listOf(castItem(it)), insertBefore = currentId) }
                view.next?.let { player.queueInsert(listOf(castItem(it)), insertBefore = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Only the device's own skip buttons depend on it; playback goes on.
                log.info("Cast device {} didn't take the queue update: {}", device.name, e.message)
            }
        }

        private suspend fun load(player: CastMediaPlayer, item: QueueItem, view: RoomView, positionMs: Long, report: suspend (String) -> Unit) {
            loadedItemId = item.itemId
            settledAt = System.currentTimeMillis() + SETTLE_MS
            failed = false
            val direct = view.streamUrl?.takeIf { !useProxy }
            // The current song with the ones before and after it, so the device's own buttons can skip.
            suspend fun loadWith(url: String?) {
                val items = listOfNotNull(view.previous?.let { castItem(it) }, castItem(item, url), view.next?.let { castItem(it) })
                player.loadQueue(items, startIndex = if (view.previous != null) 1 else 0, positionMs + LOAD_LEAD_MS, autoplay = view.playing)
                neighbors = view.previous?.itemId to view.next?.itemId
            }
            try {
                try {
                    log.info("Cast device {}: loading {} ({})", device.name, item.song.title, if (direct != null) "direct" else "via host")
                    loadWith(direct)
                } catch (e: IllegalStateException) {
                    if (direct == null) throw e
                    // The device can't use the direct URL (e.g. it's tied to the host's IP): use the host.
                    log.info("Cast device {} can't play the direct stream ({}); using the host from now on", device.name, e.message)
                    useProxy = true
                    loadWith(null)
                }
            } catch (e: IllegalStateException) {
                // Don't keep retrying this song; the next song gets a new try.
                failed = true
                log.warn("Cast device {} couldn't play {}: {}", device.name, item.song.title, e.message)
                report("${device.name} couldn't play \"${item.song.title}\"")
            }
        }

        private suspend fun connect(reportVolume: suspend (Double) -> Unit): CastMediaPlayer {
            player?.takeIf { channel?.isOpen == true }?.let { return it }
            close()
            val channel = CastChannel.open(device.host, device.port).also { channel = it }
            val player = CastMediaPlayer(channel).also { player = it }
            // Follow volume changes made elsewhere (the device's buttons or app), so the room's
            // slider shows the real volume.
            volumeWatcher = scope.launch {
                player.volumeChanges.collect { volume ->
                    appliedVolume = volume
                    reportVolume(volume)
                }
            }
            player.launch()
            player.receiverStatus().volume?.let { volume ->
                val rounded = Math.round(volume * 100) / 100.0
                appliedVolume = rounded
                reportVolume(rounded)
            }
            loadedItemId = null
            log.info("Playing on Cast device {} ({})", device.name, device.host)
            return player
        }

        suspend fun stop() {
            runCatching { player?.stop() }
            close()
        }

        fun close() {
            volumeWatcher?.cancel()
            volumeWatcher = null
            channel?.close()
            channel = null
            player = null
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CastOutputs::class.java)
        private const val SYNC_INTERVAL_MS = 2_000L

        /** Cast devices start a little late after loading or seeking; aim ahead. */
        private const val LOAD_LEAD_MS = 1_000L
        private const val SETTLE_MS = 5_000L
        private const val MAX_DRIFT_MS = 2_000L
    }
}
