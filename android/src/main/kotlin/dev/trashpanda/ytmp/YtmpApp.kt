package dev.trashpanda.ytmp

import android.app.Application
import dev.trashpanda.ytmp.host.HostService
import dev.trashpanda.ytmp.host.LocalHost
import dev.trashpanda.ytmp.host.Nearby
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

    override fun onCreate() {
        super.onCreate()
        host = LocalHost(this).also { it.start() }
        nearby = Nearby(this)
        scope.launch { host.rooms.list.collect { nearby.announce(it) } }
        scope.launch {
            host.rooms.hosting.collect { rooms ->
                if (rooms.isEmpty()) {
                    HostService.stop(this@YtmpApp)
                } else {
                    val text = rooms.singleOrNull()?.let { "Hosting ${it.name} (${it.code})" } ?: "Hosting ${rooms.size} rooms"
                    runCatching { HostService.update(this@YtmpApp, text) }
                }
            }
        }
        // Look for Chromecasts and Sonos only while they can be picked or are in use, to save battery.
        scope.launch {
            combine(visible, host.rooms.hosting) { shown, hosting -> shown || hosting.isNotEmpty() }
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
