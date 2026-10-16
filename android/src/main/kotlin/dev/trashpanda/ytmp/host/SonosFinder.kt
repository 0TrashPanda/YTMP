package dev.trashpanda.ytmp.host

import android.content.Context
import android.net.nsd.NsdServiceInfo
import dev.trashpanda.ytmp.Log

/** Finds Sonos speakers (`_sonos._tcp`) on the network and adds them to [outputs]. */
class SonosFinder(context: Context, private val outputs: SonosOutputs) : NsdFinder(context, "_sonos._tcp", TAG) {
    override fun add(info: NsdServiceInfo): String? {
        @Suppress("DEPRECATION")
        val host = info.host?.hostAddress ?: return null
        // Named after its room, read from the speaker itself.
        outputs.addHost(host)
        Log.d(TAG, "Found Sonos at $host")
        return "sonos:$host"
    }

    override fun remove(id: String) = outputs.remove(id)

    companion object {
        private const val TAG = "YtmpSonos"
    }
}
