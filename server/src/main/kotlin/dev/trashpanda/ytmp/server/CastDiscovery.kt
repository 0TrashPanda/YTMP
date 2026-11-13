package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.host.CastDeviceAddress
import dev.trashpanda.ytmp.host.CastOutputs

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
