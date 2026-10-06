package dev.trashpanda.ytmp

import android.app.Application
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

/** Starts the phone's own host when the app starts, and keeps hosting alive while it is needed. */
class YtmpApp : Application() {
    lateinit var host: LocalHost
        private set
    lateinit var nearby: Nearby
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

    override fun onCreate() {
        super.onCreate()
        host = LocalHost(this).also { it.start() }
        nearby = Nearby(this)
        scope.launch { host.rooms.list.collect { nearby.announce(it) } }
        scope.launch {
            combine(host.rooms.hosting, host.rooms.playing, ::Pair).distinctUntilChanged().collect { (rooms, playing) ->
                if (rooms.isEmpty()) {
                    HostService.stop(this@YtmpApp)
                } else {
                    val text = rooms.singleOrNull()?.let { "Hosting ${it.name} (${it.code})" } ?: "Hosting ${rooms.size} rooms"
                    // Both while a room plays, also solo: the next song must load with the screen off.
                    runCatching { HostService.update(this@YtmpApp, text, wakeLock = playing.isNotEmpty(), wifiLock = playing.isNotEmpty()) }
                }
            }
        }
        // Look for Chromecasts and Sonos only while they can be picked, to save battery: while the
        // app is on screen, or while a public room plays (friends may pick one). Devices found
        // earlier stay in the list.
        scope.launch {
            combine(visible, host.rooms.playing) { shown, playing -> shown || playing.any { it.visibility == RoomVisibility.PUBLIC } }
                .distinctUntilChanged()
                .collect {
                    if (it) {
                        host.castFinder.start()
                        host.sonosFinder.start()
                    } else {
                        host.castFinder.stop()
                        host.sonosFinder.stop()
                    }
                }
        }
    }
}
