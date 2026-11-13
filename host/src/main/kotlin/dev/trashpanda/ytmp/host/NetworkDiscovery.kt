package dev.trashpanda.ytmp.host

import org.slf4j.LoggerFactory
import javax.jmdns.JmmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

// Finding speakers with mDNS on every network interface (jmDNS): the server and the desktop app.
// The phone uses Android's own (NsdManager, android/…/NsdFinder.kt).

private val castLog = LoggerFactory.getLogger("ytmp.cast")
private val mpdLog = LoggerFactory.getLogger("ytmp.mpd")
private val zoneLog = LoggerFactory.getLogger("ytmp.zones")

/** Finds Chromecasts (`_googlecast._tcp`) on every network interface with mDNS. */
fun CastOutputs.discover(): AutoCloseable {
    val mdns = JmmDNS.Factory.getInstance()
    mdns.addServiceListener(
        "_googlecast._tcp.local.",
        object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) = Unit

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info
                val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                val id = info.getPropertyString("id") ?: info.name
                val name = info.getPropertyString("fn") ?: info.name
                add(CastDeviceAddress("cast:$id", name, host, info.port))
                castLog.info("Found Cast device {} at {}", name, host)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                val id = event.info.getPropertyString("id") ?: event.info.name
                remove("cast:$id")
            }
        },
    )
    return AutoCloseable { mdns.close() }
}

/** Finds MPDs that announce themselves (`_mpd._tcp`, MPD's zeroconf) on every network interface; the ones in [keep] stay when they go quiet. */
fun MpdOutputs.discover(keep: Set<String> = emptySet()): AutoCloseable {
    val mdns = JmmDNS.Factory.getInstance()
    // A removed service comes without its address: remember which MPD each name was.
    val found = java.util.concurrent.ConcurrentHashMap<String, String>()
    mdns.addServiceListener(
        "_mpd._tcp.local.",
        object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) = Unit

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info
                val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                val id = MpdOutputs.idFor(host, info.port)
                if (id in keep) return
                found[info.name] = id
                addHost(host, info.port, info.name)
                mpdLog.info("Found MPD {} at {}", info.name, host)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                found.remove(event.name)?.let(::remove)
            }
        },
    )
    return AutoCloseable { mdns.close() }
}

/** Finds zones files that announce themselves (`_ytmp-zones._tcp`, e.g. from the Raspberry Pi next to an amp) on every network interface. */
fun ZoneOutputs.discover(): AutoCloseable {
    val mdns = JmmDNS.Factory.getInstance()
    mdns.addServiceListener(
        "$ZONES_SERVICE.local.",
        object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) = Unit

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info
                val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                val url = ZoneOutputs.fileUrl(host, info.port, info.getPropertyString("path"))
                zoneLog.info("Found zones {} at {}", info.name, url)
                addSource("mdns:${info.name}", url)
            }

            override fun serviceRemoved(event: ServiceEvent) = removeSource("mdns:${event.name}")
        },
    )
    return AutoCloseable { mdns.close() }
}

private const val ZONES_SERVICE = "_ytmp-zones._tcp"
