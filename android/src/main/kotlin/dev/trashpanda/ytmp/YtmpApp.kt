package dev.trashpanda.ytmp

import android.app.Application
import dev.trashpanda.ytmp.host.HostService
import dev.trashpanda.ytmp.host.LocalHost
import dev.trashpanda.ytmp.host.Nearby
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Starts the phone's own host when the app starts, and keeps hosting alive while it has rooms. */
class YtmpApp : Application() {
    lateinit var host: LocalHost
        private set
    lateinit var nearby: Nearby
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        host = LocalHost(this).also { it.start() }
        nearby = Nearby(this)
        scope.launch {
            host.rooms.list.collect { rooms ->
                nearby.announce(rooms)
                if (rooms.isEmpty()) {
                    HostService.stop(this@YtmpApp)
                    host.castFinder.stop()
                } else {
                    // Look for Chromecasts only while hosting, to save battery.
                    host.castFinder.start()
                    val text = rooms.singleOrNull()?.let { "Hosting ${it.name} (${it.code})" } ?: "Hosting ${rooms.size} rooms"
                    runCatching { HostService.update(this@YtmpApp, text) }
                }
            }
        }
    }
}
