package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.host.MpdOutputs
import dev.trashpanda.ytmp.host.MpdPlayer

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
