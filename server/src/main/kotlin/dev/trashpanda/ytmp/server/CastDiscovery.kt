package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.host.CastDeviceAddress
import dev.trashpanda.ytmp.host.CastOutputs
import org.slf4j.LoggerFactory
import javax.jmdns.JmmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

private val log = LoggerFactory.getLogger("ytmp.cast")

/** Adds the Cast devices from the config file, as "host" or "Name=host[:port]". */
fun CastOutputs.addConfigured(entries: List<String>) {
    for (entry in entries) {
        val name = entry.substringBefore('=', missingDelimiterValue = "").trim().ifEmpty { null }
        val address = entry.substringAfter('=').trim()
        val host = address.substringBefore(':')
        val port = address.substringAfter(':', "8009").toInt()
        add(CastDeviceAddress(id = "cast:$host", name = name ?: host, host = host, port = port))
    }
}

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
                log.info("Found Cast device {} at {}", name, host)
            }

            override fun serviceRemoved(event: ServiceEvent) {
                val id = event.info.getPropertyString("id") ?: event.info.name
                remove("cast:$id")
            }
        },
    )
    return AutoCloseable { mdns.close() }
}
