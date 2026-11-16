package dev.trashpanda.ytmp.desktop

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import javax.jmdns.JmmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/** A public room a phone on this computer's network hosts (like the phone app's `NearbyRoom`). */
@Serializable
data class NearbyRoom(val name: String, val code: String, val url: String)

/**
 * Public rooms on this computer's network: phones announce them with mDNS (`_ytmp._tcp`, TXT
 * `code`, `name`, `v`; android/…/host/Nearby.kt). The home page lists them as *Nearby rooms*.
 * This computer has no rooms of its own, so it only looks.
 */
class NearbyRooms {
    /** mDNS instance name → the room. */
    private val found = ConcurrentHashMap<String, NearbyRoom>()

    val rooms: List<NearbyRoom> get() = found.values.distinctBy { it.url + it.code }.sortedBy { it.name.lowercase() }

    /** Looks on every network interface until the app ends. */
    fun discover() {
        JmmDNS.Factory.getInstance().addServiceListener(
            "_ytmp._tcp.local.",
            object : ServiceListener {
                override fun serviceAdded(event: ServiceEvent) = Unit

                override fun serviceResolved(event: ServiceEvent) {
                    val info = event.info
                    val code = info.getPropertyString("code") ?: return
                    val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                    val room = NearbyRoom(info.getPropertyString("name") ?: code, code, "http://$host:${info.port}")
                    if (found.put(info.name, room) == null) log.info("Room {} nearby at {}", code, host)
                }

                override fun serviceRemoved(event: ServiceEvent) {
                    found.remove(event.name)
                }
            },
        )
    }

    private companion object {
        val log = LoggerFactory.getLogger("ytmp.rooms")
    }
}
