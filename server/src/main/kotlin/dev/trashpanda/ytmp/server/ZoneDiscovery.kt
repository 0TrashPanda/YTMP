package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.host.ZoneOutputs
import org.slf4j.LoggerFactory
import javax.jmdns.JmmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

private val log = LoggerFactory.getLogger("ytmp.zones")

/** Finds zones files that announce themselves (`_ytmp-zones._tcp`, e.g. from the Raspberry Pi next to an amp) on every network interface. */
fun ZoneOutputs.discover(): AutoCloseable {
    val mdns = JmmDNS.Factory.getInstance()
    mdns.addServiceListener(
        "$SERVICE_TYPE.local.",
        object : ServiceListener {
            override fun serviceAdded(event: ServiceEvent) = Unit

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info
                val host = info.inet4Addresses.firstOrNull()?.hostAddress ?: return
                val url = ZoneOutputs.fileUrl(host, info.port, info.getPropertyString("path"))
                log.info("Found zones {} at {}", info.name, url)
                addSource("mdns:${info.name}", url)
            }

            override fun serviceRemoved(event: ServiceEvent) = removeSource("mdns:${event.name}")
        },
    )
    return AutoCloseable { mdns.close() }
}

private const val SERVICE_TYPE = "_ytmp-zones._tcp"
