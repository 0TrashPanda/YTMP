package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.cast.Dial
import dev.trashpanda.ytmp.cast.LoungeStatus
import dev.trashpanda.ytmp.cast.YoutubeLounge
import dev.trashpanda.ytmp.core.OutputDevice
import dev.trashpanda.ytmp.core.OutputRoom
import dev.trashpanda.ytmp.core.RoomView
import dev.trashpanda.ytmp.protocol.OutputKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import kotlin.math.abs
import kotlin.math.roundToInt

/** A TV with the YouTube app (and DIAL) on the network. */
data class YoutubeTvAddress(val id: String, val name: String, val appsUrl: String)

/**
 * TVs that only have the YouTube app (no Chromecast): projectors, TVs with their own system.
 * The host finds them with DIAL and plays the room there the way a phone casts from the
 * YouTube app: it starts the TV's YouTube app and remote-controls it ([YoutubeLounge]). The
 * TV plays each song itself, from YouTube (with its video), so only YouTube songs can play
 * there. See docs/implementation/youtube-tv.md.
 */
class YoutubeTvOutputs(
    scope: CoroutineScope,
    /** YouTube's Lounge API; a test points it elsewhere. */
    private val loungeUrl: String = "https://www.youtube.com/api/lounge",
) : DeviceDriver(scope, "TV") {
    private val known = MutableStateFlow<Map<String, YoutubeTvAddress>>(emptyMap())
    private val checked = HashSet<String>()

    /** The TVs as room outputs. */
    override val devices: StateFlow<List<OutputDevice>> = known
        .map { all -> all.values.sortedBy { it.name }.map { OutputDevice(it.id, it.name, OutputKind.YOUTUBE_TV) } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun add(device: YoutubeTvAddress) {
        known.update { it + (device.id to device) }
        found(device.id)
    }

    fun remove(id: String) {
        known.update { it - id }
    }

    /** On another network: the TVs found here go, once no room plays on them any more ([lose]). */
    fun forgetAll() {
        synchronized(checked) { checked.clear() }
        for (id in known.value.keys) lose(id) { remove(id) }
    }

    /**
     * Adds the DIAL device described at [location] if it has the YouTube app and isn't a
     * Chromecast (those play with the Cast protocol, as Chromecast outputs).
     */
    suspend fun addLocation(location: String) {
        val device = Dial.describe(location) ?: return
        val host = URI(location).host
        if (Dial.isCastDevice(host)) return
        Dial.youtube(device.appsUrl) ?: return
        val id = "ytv:${device.id}"
        if (id !in known.value) log.info("Found a TV with YouTube: {} ({} {}) at {}", device.name, device.manufacturer, device.model, host)
        add(YoutubeTvAddress(id, device.name, device.appsUrl))
    }

    /** Adds a TV by address ("10.0.0.5", or with its DIAL port: "10.0.0.5:56790"), from its DIAL description. */
    fun addHost(address: String) {
        scope.launch(Dispatchers.IO) {
            val hosts = if (':' in address) listOf(address) else DIAL_PORTS.map { "$address:$it" }
            val found = hosts.flatMap { host -> DESCRIPTION_PATHS.map { "http://$host$it" } }.firstOrNull { location ->
                runCatching { Dial.describe(location) != null }.getOrDefault(false)
            }
            if (found == null) log.warn("No TV with DIAL at {}", address) else runCatching { addLocation(found) }
        }
    }

    /** Looks for TVs with DIAL now and every few minutes. Needs UDP multicast replies to get through. */
    fun discover(): Job = scope.launch(Dispatchers.IO) {
        while (true) {
            val locations = runCatching { Dial.search() }.onFailure { log.debug("DIAL discovery failed: {}", it.message) }.getOrDefault(emptySet())
            for (location in locations) {
                // Chromecasts and TVs without YouTube are asked once.
                if (!synchronized(checked) { checked.add(location) }) continue
                runCatching { addLocation(location) }.onFailure {
                    synchronized(checked) { checked.remove(location) }
                    log.debug("DIAL device at {}: {}", location, it.message)
                }
            }
            delay(DISCOVERY_INTERVAL_MS)
        }
    }

    override fun has(id: String) = id in known.value

    override fun name(id: String) = known.value[id]?.name ?: id

    override fun session(id: String, resuming: Boolean): DeviceSession = Session(known.value.getValue(id))

    /** One room playing on one TV's YouTube app. */
    private inner class Session(private val device: YoutubeTvAddress) : DeviceSession {
        private var lounge: YoutubeLounge? = null
        private var loadedItemId: String? = null
        private var loadedVideo: String? = null
        /** The TV showed [loadedVideo]: another video after that means someone else chose it. */
        private var confirmed = false
        private var settledAt = 0L
        private var askedAt = 0L
        private var appliedVolume: Double? = null
        private var seenVolume: Int? = null
        private var failed = false
        private var failedAt = 0L
        override var loadedSomething = false
        /** The TV's own autoplay setting before we turned it off, to put back when we stop. */
        private var autoplayBefore: Boolean? = null

        private suspend fun connect(): YoutubeLounge {
            lounge?.takeIf { it.isOpen }?.let { return it }
            close()
            val app = Dial.youtube(device.appsUrl) ?: throw IOException("the TV has no YouTube app (anymore)")
            val screenId = app.screenId?.takeIf { app.running }
                ?: Dial.launchYoutube(device.appsUrl)
                ?: throw IOException("the YouTube app didn't start")
            val lounge = YoutubeLounge(screenId, "YTMP", loungeUrl)
            lounge.connect(scope)
            this.lounge = lounge
            log.info("Playing on TV {} (its YouTube app)", device.name)
            val autoplay = lounge.status.value.autoplay
            if (autoplayBefore == null) autoplayBefore = autoplay
            // Our songs follow each other; the TV shouldn't pick a related video in between.
            if (autoplay != false) lounge.setAutoplay(false)
            confirmed = false
            if (loadedVideo != null) lounge.requestStatus()
            return lounge
        }

        override suspend fun sync(view: RoomView, wantedVolume: Double?, room: OutputRoom, outputId: String) {
            val report: suspend (String) -> Unit = room::notice
            val ready: suspend (String) -> Unit = { room.outputReady(outputId, it) }
            val reportVolume: suspend (Double) -> Unit = { room.reportOutputVolume(outputId, it) }
            val now = System.currentTimeMillis()
            if (now - failedAt < RETRY_AFTER_MS) return
            val lounge = connect()
            val status = lounge.status.value

            // Volume: changes made on the TV (its remote) show in the room; the room's slider sets it.
            val tvVolume = status.volume
            if (tvVolume != null && tvVolume != seenVolume) {
                seenVolume = tvVolume
                val actual = tvVolume / 100.0
                if (appliedVolume == null || abs(actual - appliedVolume!!) > 0.005) {
                    appliedVolume = actual
                    reportVolume(actual)
                }
            } else if (wantedVolume != null && wantedVolume != appliedVolume) {
                lounge.setVolume((wantedVolume * 100).roundToInt())
                appliedVolume = wantedVolume
            }

            val item = view.current
            if (item == null) {
                if (loadedItemId != null && status.state in setOf(LoungeStatus.PLAYING, LoungeStatus.LOADING)) lounge.pause()
                return
            }
            val expected = view.positionAt(now)
            if (item.itemId != loadedItemId) {
                loadedItemId = item.itemId
                settledAt = now + SETTLE_MS
                confirmed = false
                val videoId = videoId(item.song.id)
                loadedVideo = videoId
                failed = videoId == null
                if (videoId == null) {
                    report("${device.name} only plays songs from YouTube")
                } else {
                    log.info("TV {}: playing {}", device.name, item.song.title)
                    lounge.setPlaylist(videoId, if (view.starting) expected else expected + LOAD_LEAD_MS)
                    loadedSomething = true
                }
                // Sent (it starts by itself) or can't: a new song doesn't wait for it any longer.
                if (view.starting) ready(item.itemId)
                return
            }
            if (failed) return

            if (status.videoId == loadedVideo) {
                confirmed = true
            } else if (confirmed && status.videoId != null && !status.adPlaying) {
                // Someone picked another video (the YouTube app on their phone): theirs now.
                throw OutputTakenOver("someone played another video on it")
            }
            if (!confirmed && now > settledAt + CONFIRM_MS) {
                // The TV didn't take the song (still starting the app, or it missed it): send it again.
                log.info("TV {} didn't start {}; sending it again", device.name, item.song.title)
                loadedItemId = null
                return
            }
            if (status.adPlaying || status.state == LoungeStatus.AD || !confirmed) return
            when {
                view.playing && status.state == LoungeStatus.PAUSED -> lounge.play()
                !view.playing && status.state in setOf(LoungeStatus.PLAYING, LoungeStatus.LOADING) -> lounge.pause()
            }
            if (now < settledAt || !view.playing || status.state != LoungeStatus.PLAYING) return
            // The TV only says where it is when something changes: ask now and then.
            if (now - status.receivedAt > STATUS_EVERY_MS) {
                if (now - askedAt > STATUS_EVERY_MS) {
                    askedAt = now
                    lounge.requestStatus()
                }
                return
            }
            // The TV buffers on its own: only fix clearly audible drift.
            val drift = status.positionMs + (now - status.receivedAt) - expected
            if (abs(drift) > MAX_DRIFT_MS) {
                log.info("TV {}: {} ms off, moving it", device.name, drift)
                lounge.seek(expected + LOAD_LEAD_MS)
                settledAt = now + SETTLE_MS
            }
        }

        override suspend fun stop() {
            val lounge = lounge?.takeIf { it.isOpen }
            if (lounge != null) {
                // Only our own video: never stop what someone else plays there.
                if (loadedVideo != null && lounge.status.value.videoId == loadedVideo) runCatching { lounge.stopVideo() }
                if (autoplayBefore == true) runCatching { lounge.setAutoplay(true) }
            }
            loadedItemId = null
            close()
        }

        /** Someone else plays there now: let go, without stopping their video. */
        override fun takenOver() = close()

        /** It reconnects after a while. */
        override fun failed(): Boolean {
            close()
            failedAt = System.currentTimeMillis()
            return false
        }

        fun close() {
            lounge?.close()
            lounge = null
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(YoutubeTvOutputs::class.java)
        /** The YouTube app takes a few seconds to start a video. */
        private const val SETTLE_MS = 8_000L
        private const val LOAD_LEAD_MS = 1_500L
        private const val MAX_DRIFT_MS = 3_000L
        private const val STATUS_EVERY_MS = 10_000L
        /** After [SETTLE_MS], how long the TV may take to show the song before it's sent again. */
        private const val CONFIRM_MS = 15_000L
        private const val RETRY_AFTER_MS = 10_000L
        private const val DISCOVERY_INTERVAL_MS = 5 * 60_000L

        /** Where DIAL servers usually are and keep their description, for TVs added by address. */
        private val DIAL_PORTS = listOf(56790, 80, 8080)
        private val DESCRIPTION_PATHS = listOf("/dd.xml", "/ssdp/device-desc.xml", "/upnp/dev/desc.xml")

        private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

        /** The YouTube video of a song ("ytm:abc…"), or null for songs from elsewhere. */
        fun videoId(songId: String): String? {
            val prefix = songId.substringBefore(':', "")
            if (prefix !in setOf("ytm", "yt")) return null
            return songId.substringAfter(':').takeIf { VIDEO_ID.matches(it) }
        }
    }
}
