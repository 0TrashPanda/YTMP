package dev.trashpanda.ytmp.host

import android.content.Context
import android.net.nsd.NsdServiceInfo
import dev.trashpanda.ytmp.Log

/** Finds Chromecasts (`_googlecast._tcp`) on the network and adds them to [outputs]. */
class CastFinder(context: Context, private val outputs: CastOutputs) : NsdFinder(context, "_googlecast._tcp", TAG) {
    override fun add(info: NsdServiceInfo): String? {
        @Suppress("DEPRECATION")
        val host = info.host?.hostAddress ?: return null
        val id = "cast:" + (info.attributes["id"]?.decodeToString() ?: info.serviceName)
        val name = info.attributes["fn"]?.decodeToString() ?: info.serviceName
        outputs.add(CastDeviceAddress(id, name, host, info.port))
        Log.d(TAG, "Found Cast device $name at $host")
        return id
    }

    override fun remove(id: String) = outputs.remove(id)

    override fun lose(id: String) = outputs.lost(id)

    companion object {
        private const val TAG = "YtmpCast"
    }
}
