package dev.trashpanda.ytmp.host

import android.content.Context
import android.net.nsd.NsdServiceInfo
import dev.trashpanda.ytmp.Log

/** Finds MPDs that announce themselves (`_mpd._tcp`, MPD's zeroconf) and adds them to [outputs]. */
class MpdFinder(context: Context, private val outputs: MpdOutputs) : NsdFinder(context, "_mpd._tcp", TAG) {
    override fun add(info: NsdServiceInfo): String? {
        @Suppress("DEPRECATION")
        val host = info.host?.hostAddress ?: return null
        outputs.addHost(host, info.port, info.serviceName)
        Log.d(TAG, "Found MPD ${info.serviceName} at $host")
        return MpdOutputs.idFor(host, info.port)
    }

    override fun remove(id: String) = outputs.remove(id)

    override fun lose(id: String) = outputs.lost(id)

    companion object {
        private const val TAG = "YtmpMpd"
    }
}
