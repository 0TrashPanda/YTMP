package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.cast.CastChannel
import dev.trashpanda.ytmp.cast.CastMediaPlayer
import dev.trashpanda.ytmp.cast.CastMetadata
import dev.trashpanda.ytmp.cast.CastQueueItem
import dev.trashpanda.ytmp.cast.CastWebPage
import dev.trashpanda.ytmp.core.OutputDevice
import dev.trashpanda.ytmp.core.OutputRoom
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.core.RoomView
import dev.trashpanda.ytmp.protocol.OutputKind
import dev.trashpanda.ytmp.protocol.QueueItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
 *
 * A TV or display can show the party screen instead ([RoomView.screens]): DashCast opens the
 * host's `/tv` page there, which plays the music itself (docs/features/ui.md#party-screen).
 */
class CastOutputs(
    scope: CoroutineScope,
    /**
     * Where a TV opens the party screen page; null: [audioBaseUrl]. DashCast is an `https`
     * page, which can only show `https` pages: a server's public address works best.
     */
    private val pageBaseUrl: ((localAddress: InetAddress) -> String)? = null,
    /** Base URL of this host as seen from a device that reached us at [InetAddress] (our side of the connection). */
    private val audioBaseUrl: (localAddress: InetAddress) -> String,
) : DeviceDriver(scope, "Cast device") {
    private val known = MutableStateFlow<Map<String, CastDeviceAddress>>(emptyMap())

    /** The devices as room outputs. Pass to [RoomManager]. */
    override val devices: StateFlow<List<OutputDevice>> = known
        .map { all -> all.values.sortedBy { it.name }.map { OutputDevice(it.id, it.name, OutputKind.CHROMECAST) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun add(device: CastDeviceAddress) {
        known.update { it + (device.id to device) }
    }

    fun remove(id: String) {
        known.update { it - id }
    }

    /** Where the device with [id] is, if it was found. */
    fun address(id: String): CastDeviceAddress? = known.value[id]

    override fun has(id: String) = id in known.value

    override fun name(id: String) = known.value[id]?.name ?: id

    override fun session(id: String, resuming: Boolean): DeviceSession = CastSession(known.value.getValue(id), resuming)

    /** One room playing on one Cast device. */
    private inner class CastSession(
        private val device: CastDeviceAddress,
        /** This room played here before (the connection dropped): don't take it from another app. */
        private val resuming: Boolean = false,
    ) : DeviceSession {
        override var loadedSomething = false
            private set

        override suspend fun sync(view: RoomView, wantedVolume: Double?, room: OutputRoom, outputId: String) =
            sync(view, wantedVolume, room.code, report = room::notice, skipped = room::outputSkipped, ready = { room.outputReady(outputId, it) }) { actual ->
                room.reportOutputVolume(outputId, actual)
            }

        override fun takenOver() = close()

        /** Reconnect on the next round. */
        override fun failed(): Boolean {
            close()
            return true
        }

        override suspend fun end() = close()

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

        private suspend fun sync(
            view: RoomView,
            wantedVolume: Double?,
            roomCode: String,
            report: suspend (String) -> Unit,
            skipped: suspend (fromItemId: String, forward: Boolean) -> Unit,
            ready: suspend (itemId: String) -> Unit,
            reportVolume: suspend (Double) -> Unit,
        ) {
            val screen = view.screens[device.id]
            if (screen != null) return showScreen(roomCode, screen, wantedVolume, reportVolume)
            if (shownUrl != null) {
                // Back from the party screen to only the music: Google's player starts again.
                shownUrl = null
                close()
            }
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
                loadedSomething = true
                // Loaded (paused) or failed: a new song doesn't wait for it any longer.
                if (view.starting) ready(item.itemId)
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
            audioBaseUrl(channel!!.localAddress) + "/api/audio/" + URLEncoder.encode(item.song.id, "UTF-8")

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
                // A new song waits for everyone to load it (paused, exactly at its start); one
                // that's already playing moves on while the device loads it.
                val start = if (view.starting) positionMs else positionMs + LOAD_LEAD_MS
                player.loadQueue(items, startIndex = if (view.previous != null) 1 else 0, start, autoplay = view.playing)
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

        /** The page the device shows with DashCast (party screen), or null while it plays music with Google's player. */
        private var shownUrl: String? = null
        private var pageCheckedAt = 0L

        /** The party screen on the device: DashCast with the host's `/tv` page, signed in as the room's screen participant. */
        private suspend fun showScreen(roomCode: String, guestToken: String, wantedVolume: Double?, reportVolume: suspend (Double) -> Unit) {
            val (channel, player) = open(reportVolume)
            if (wantedVolume != null && wantedVolume != appliedVolume) {
                player.setVolume(wantedVolume)
                appliedVolume = wantedVolume
            }
            val page = CastWebPage(channel)
            val url = (pageBaseUrl ?: audioBaseUrl)(channel.localAddress) + "/tv#cast=" + URLEncoder.encode(roomCode, "UTF-8") + "." + URLEncoder.encode(guestToken, "UTF-8")
            val now = System.currentTimeMillis()
            if (shownUrl != url) {
                log.info("Cast device {}: showing the party screen of {}", device.name, roomCode)
                page.show(url)
                shownUrl = url
                loadedSomething = true
                pageCheckedAt = now + SETTLE_MS
            } else if (now >= pageCheckedAt) {
                pageCheckedAt = now + PAGE_CHECK_MS
                if (!page.isShowing()) {
                    // Someone cast something else, or the TV closed it: theirs now, or show it again.
                    val other = player.receiverStatus().runningAppIds.firstOrNull { it !in OUR_APPS }
                    if (other != null) throw OutputTakenOver("another app took it ($other)")
                    shownUrl = null
                }
            }
        }

        /** The connection to the device, with its volume followed, without starting an app. */
        private suspend fun open(reportVolume: suspend (Double) -> Unit): Pair<CastChannel, CastMediaPlayer> {
            val open = channel?.takeIf { it.isOpen }
            val current = player
            if (open != null && current != null) return open to current
            close()
            val channel = CastChannel.open(device.host, device.port).also { channel = it }
            val player = CastMediaPlayer(channel).also { player = it }
            if (resuming || loadedSomething) {
                // Our app was closed: if another app runs there now (someone cast to it), it's theirs.
                val other = player.receiverStatus().runningAppIds.firstOrNull { it !in OUR_APPS }
                if (other != null) throw OutputTakenOver("another app took it ($other)")
            }
            // Follow volume changes made elsewhere (the device's buttons or app), so the room's
            // slider shows the real volume.
            volumeWatcher = scope.launch {
                try {
                    player.volumeChanges.collect { volume ->
                        appliedVolume = volume
                        reportVolume(volume)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Not fatal (on Android an exception escaping here would crash the app).
                    log.warn("Cast device {}: stopped following its volume: {}", device.name, e.toString())
                }
            }
            player.receiverStatus().volume?.let { volume ->
                val rounded = Math.round(volume * 100) / 100.0
                appliedVolume = rounded
                reportVolume(rounded)
            }
            return channel to player
        }

        private suspend fun connect(reportVolume: suspend (Double) -> Unit): CastMediaPlayer {
            if (launched) player?.takeIf { channel?.isOpen == true }?.let { return it }
            val (_, player) = open(reportVolume)
            player.launch()
            launched = true
            loadedItemId = null
            log.info("Playing on Cast device {} ({})", device.name, device.host)
            return player
        }

        /** Google's player runs on the device (this connection started it). */
        private var launched = false

        override suspend fun stop() {
            runCatching { if (shownUrl != null) channel?.let { CastWebPage(it).stop() } else player?.stop() }
            close()
        }

        fun close() {
            launched = false
            volumeWatcher?.cancel()
            volumeWatcher = null
            channel?.close()
            channel = null
            player = null
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(CastOutputs::class.java)

        /** Cast devices start a little late after loading or seeking; aim ahead. */
        private const val LOAD_LEAD_MS = 1_000L
        private const val SETTLE_MS = 5_000L
        private const val MAX_DRIFT_MS = 2_000L

        /** How often to check that the party screen still shows. */
        private const val PAGE_CHECK_MS = 10_000L

        /** Apps of ours (and the idle screen): anything else running means someone else uses the device. */
        private val OUR_APPS = setOf(CastMediaPlayer.DEFAULT_MEDIA_RECEIVER, CastWebPage.APP_ID, BACKDROP_APP)
    }
}

/** The Cast device's idle screen (photos), not an app someone is using. */
private const val BACKDROP_APP = "E8C28D3C"
