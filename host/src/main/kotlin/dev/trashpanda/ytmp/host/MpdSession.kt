package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.RoomView
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.URLEncoder
import kotlin.math.abs

/**
 * One room playing on one MPD: keeps its song, play/pause, position and volume in step with
 * the room. Used by [MpdOutputs] (an MPD as a speaker) and [ZoneOutputs] (an MPD that feeds
 * amplifier zones). See docs/implementation/mpd.md.
 */
internal class MpdSession(
    private val name: String,
    host: String,
    port: Int,
    /** Base URL of this host as seen from an MPD that reached us at [InetAddress]. */
    private val audioBaseUrl: (localAddress: InetAddress) -> String,
) {
    private val player = MpdPlayer(host, port)
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

    /** The song MPD has (or couldn't load): a new song needn't wait for it any longer. */
    val loaded: String? get() = loadedItemId

    private val audioPrefix get() = audioBaseUrl(player.localAddress) + "/api/audio/"

    /** @throws OutputTakenOver when MPD plays something that isn't ours (another app, or another host). */
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
            val url = audioPrefix + URLEncoder.encode(item.song.id, Charsets.UTF_8)
            try {
                log.info("MPD {}: loading {}", name, item.song.title)
                player.load(url)
                waitingToStart = !view.playing
                if (view.playing) startAt(expected + if (view.starting) 0 else LOAD_LEAD_MS)
            } catch (e: MpdException) {
                failed = true
                log.warn("MPD {} couldn't play {}: {}", name, item.song.title, e.message)
                report("$name couldn't play \"${item.song.title}\": ${e.message}")
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

        // Playing something that isn't ours (its own library, another MPD app, another YTMP host): someone else took it.
        val file = status.file
        if (now >= settledAt && file != null && !file.startsWith(audioPrefix)) throw OutputTakenOver("something else plays there ($file)")
        if (now >= settledAt && status.state == "stop" && status.error != null) {
            failed = true
            log.warn("MPD {} couldn't play {}: {}", name, item.song.title, status.error)
            report("$name couldn't play \"${item.song.title}\": ${status.error}")
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

    /** Stops MPD and empties its queue, if we gave it something. */
    suspend fun stop() {
        if (loadedItemId != null) runCatching { player.stop() }
        loadedItemId = null
    }

    companion object {
        private val log = LoggerFactory.getLogger(MpdSession::class.java)
        private const val SETTLE_MS = 6_000L
        private const val LOAD_LEAD_MS = 1_000L
        /** Starting a song this far in: seek there after starting. */
        private const val SEEK_THRESHOLD_MS = 3_000L
        private const val MAX_DRIFT_MS = 2_000L
        private const val END_MARGIN_MS = 5_000L
        const val RETRY_AFTER_MS = 10_000L
    }
}
