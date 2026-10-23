package dev.trashpanda.ytmp

import android.app.Application
import ch.qos.logback.classic.LoggerContext
import dev.trashpanda.ytmp.host.HostService
import dev.trashpanda.ytmp.host.LocalHost
import dev.trashpanda.ytmp.host.Nearby
import dev.trashpanda.ytmp.protocol.RoomVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** Starts the phone's own host when the app starts, and keeps hosting alive while it is needed. */
class YtmpApp : Application() {
    lateinit var host: LocalHost
        private set
    lateinit var nearby: Nearby
        private set

    /** The songs a Pixel's Now Playing recognized lately (read by [NowPlayingListener]). */
    lateinit var nowPlaying: NowPlayingHistory
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** The app is on screen (set by [MainActivity]). */
    val visible = MutableStateFlow(false)

    /**
     * A login for the phone's own pages, handed over by your server's pages (log in once, see
     * MainActivity's bridge). Only in memory: it's a 30-day token.
     */
    @Volatile
    var sharedIdentity: String? = null

    /** Looked for a new version since the app started (see [Updates]). */
    var checkedForUpdate = false

    /** A crash goes into the log files too (written out before the app stops), so *Send logs* shows why. */
    private fun logCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                Log.e("YtmpApp", "Crashed on thread ${thread.name}", error)
                (LoggerFactory.getILoggerFactory() as? LoggerContext)?.stop()
            }
            previous?.uncaughtException(thread, error)
        }
    }

    override fun onCreate() {
        super.onCreate()
        logCrashes()
        Log.i("YtmpApp", "YTMP ${Updates.installedVersion(this)} starting")
        nowPlaying = NowPlayingHistory(this)
        Equalizers.start(this)
        host = LocalHost(this).also { it.start() }
        nearby = Nearby(this)
        scope.launch { host.rooms.list.collect { nearby.announce(it) } }
        scope.launch {
            combine(host.rooms.hosting, host.rooms.playing, host.nearby.playing, ::Triple).distinctUntilChanged().collect { (rooms, playing, speakers) ->
                // Speakers here playing a server's room: the phone serves them the audio, also with the screen off.
                val nearbyText = speakers?.let { "Playing on ${it.speakers.joinToString { s -> s.name }}" }
                if (rooms.isEmpty() && nearbyText == null) {
                    HostService.stop(this@YtmpApp)
                } else {
                    val text = rooms.singleOrNull()?.let { "Hosting ${it.name} (${it.code})" }
                        ?: rooms.takeIf { it.isNotEmpty() }?.let { "Hosting ${it.size} rooms" }
                        ?: nearbyText!!
                    // Both while a room plays, also solo: the next song must load with the screen off.
                    val busy = playing.isNotEmpty() || speakers != null
                    runCatching { HostService.update(this@YtmpApp, text, wakeLock = busy, wifiLock = busy) }
                }
            }
        }
        // Look for Chromecasts, Sonos and MPDs only while they can be picked, to save battery: while
        // the app is on screen, or while a public room plays (friends may pick one). Devices found
        // earlier stay in the list until a new search misses them or the phone changes networks.
        scope.launch {
            combine(visible, host.rooms.playing) { shown, playing -> shown || playing.any { it.visibility == RoomVisibility.PUBLIC } }
                .distinctUntilChanged()
                .collect {
                    if (it) {
                        host.castFinder.start()
                        host.sonosFinder.start()
                        host.mpdFinder.start()
                        host.zoneFinder.start()
                    } else {
                        host.castFinder.stop()
                        host.sonosFinder.stop()
                        host.mpdFinder.stop()
                        host.zoneFinder.stop()
                    }
                }
        }
    }
}
