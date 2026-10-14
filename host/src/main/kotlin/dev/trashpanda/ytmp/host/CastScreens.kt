package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.cast.CastChannel
import dev.trashpanda.ytmp.cast.CastWebPage
import dev.trashpanda.ytmp.protocol.ScreenUnlinkRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.net.URLDecoder

/**
 * The party screen of a room on another host (a server) on a TV with Chromecast that only
 * this phone can reach, the TV at your place: the app opens the room's `/tv` page there with
 * DashCast, signed in as the screen participant the room made for it (`POST /api/screens/cast`).
 * The page plays the music itself, so the phone only starts it, checks it keeps showing, and
 * stops it again. When it ends (stopped here, someone cast something else, the TV went off)
 * [onEnded] lets the room know.
 */
class CastScreens(
    private val scope: CoroutineScope,
    private val devices: CastOutputs,
    private val checkMs: Long = 10_000,
    private val onEnded: suspend (ShownScreen) -> Unit,
) {
    private class Session(val screen: ShownScreen, val channel: CastChannel, val watcher: Job)

    private val sessions = HashMap<String, Session>()
    private val lock = Mutex()
    private val _shown = MutableStateFlow<List<ShownScreen>>(emptyList())

    /** What the TVs show now. */
    val shown: StateFlow<List<ShownScreen>> = _shown

    /**
     * Shows [url] (a room's `/tv#cast=…` page) on the device [deviceId], instead of what it
     * showed. Off the caller's thread: Android doesn't allow network on its main thread.
     */
    suspend fun show(deviceId: String, url: String): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            val device = devices.address(deviceId) ?: throw IllegalArgumentException("That TV isn't on this network any more")
            sessions[deviceId]?.let { end(it, stopApp = false) }
            val channel = CastChannel.open(device.host, device.port)
            try {
                CastWebPage(channel).show(url)
            } catch (e: Exception) {
                channel.close()
                throw e
            }
            val screen = ShownScreen(deviceId, device.name, url)
            val watcher = scope.launch { watch(screen, channel) }
            sessions[deviceId] = Session(screen, channel, watcher)
            publish()
            log.info("Showing a party screen on {}", device.name)
        }
    }

    /** Stops the party screen on [deviceId] (DashCast closes there). */
    suspend fun stop(deviceId: String): Unit = withContext(Dispatchers.IO) {
        lock.withLock { sessions[deviceId]?.let { end(it, stopApp = true) } }
    }

    /** Checks every while that DashCast still runs there; when it doesn't, the screen ended. */
    private suspend fun watch(screen: ShownScreen, first: CastChannel) {
        var channel = first
        var failures = 0
        while (true) {
            delay(checkMs)
            val showing = try {
                if (!channel.isOpen) channel = devices.address(screen.deviceId)?.let { CastChannel.open(it.host, it.port) } ?: error("gone")
                CastWebPage(channel).isShowing().also { failures = 0 }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unreachable for a while: switched off, or off the network.
                if (++failures < MAX_FAILURES) continue
                false
            }
            if (!showing) {
                log.info("The party screen on {} ended", screen.name)
                lock.withLock { sessions[screen.deviceId]?.takeIf { it.screen == screen }?.let { end(it, stopApp = false, watching = false) } }
                if (channel !== first) channel.close()
                return
            }
        }
    }

    private suspend fun end(session: Session, stopApp: Boolean, watching: Boolean = true) {
        if (watching) session.watcher.cancel()
        if (stopApp) runCatching { CastWebPage(session.channel).stop() }
        session.channel.close()
        sessions.remove(session.screen.deviceId)
        publish()
        scope.launch {
            try {
                onEnded(session.screen)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                log.info("Couldn't tell the room the party screen on {} ended: {}", session.screen.name, e.message)
            }
        }
    }

    private fun publish() = _shown.update { sessions.values.map { it.screen } }

    companion object {
        private val log = LoggerFactory.getLogger(CastScreens::class.java)
        private const val MAX_FAILURES = 3
    }
}

/** A party screen this phone shows on [deviceId] ([name]): [url] is the room's `/tv#cast=CODE.TOKEN` page. */
@Serializable
data class ShownScreen(val deviceId: String, val name: String, val url: String) {
    /** How to tell the room this screen ended: `POST` [ScreenUnlinkRequest] to this address. */
    fun unlink(): Pair<String, ScreenUnlinkRequest>? {
        val (code, token) = CAST_PAGE.matchEntire(url)?.destructured ?: return null
        return url.substringBefore("/tv#") + "/api/screens/unlink" to
            ScreenUnlinkRequest(URLDecoder.decode(code, Charsets.UTF_8), URLDecoder.decode(token, Charsets.UTF_8))
    }

    companion object {
        /** `https://host/tv#cast=CODE.TOKEN`, as the web app's `/tv` page reads it. */
        val CAST_PAGE = Regex("""https?://[^/#?]+/tv#cast=([^.]+)\.(.+)""")
    }
}
