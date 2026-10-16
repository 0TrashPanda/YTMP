package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.host.MpdOutputs
import dev.trashpanda.ytmp.host.MpdPlayer
import org.slf4j.LoggerFactory
import javax.jmdns.JmmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

private val log = LoggerFactory.getLogger("ytmp.mpd")

/** An MPD from the config file: "host", "host:port" or "Name=host[:port]". */
data class MpdEntry(val name: String?, val host: String, val port: Int) {
    val id get() = MpdOutputs.idFor(host, port)

    companion object {
        fun parse(entry: String): MpdEntry {
            val name = entry.substringBefore('=', missingDelimiterValue = "").trim().ifEmpty { null }
            val address = entry.substringAfter('=').trim()
            return MpdEntry(name, address.substringBefore(':'), address.substringAfter(':', "").toIntOrNull() ?: MpdPlayer.DEFAULT_PORT)
        }
    }
}

/** Adds the MPDs from the config file; one that's off now is added once it answers. */
fun MpdOutputs.addConfigured(entries: List<String>) {
    for (entry in entries.map(MpdEntry::parse)) addHost(entry.host, entry.port, entry.name, retry = true)
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
                log.info("Found MPD {} at {}", info.name, host)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                found.remove(event.name)?.let(::remove)
            }
        },
    )
    return AutoCloseable { mdns.close() }
}
