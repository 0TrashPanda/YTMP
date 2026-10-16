package dev.trashpanda.ytmp.host

import android.content.Context
import android.net.nsd.NsdServiceInfo
import dev.trashpanda.ytmp.Log

/** Finds zones files that announce themselves (`_ytmp-zones._tcp`, e.g. from the Raspberry Pi next to an amp) and follows them in [zones]. */
class ZoneFinder(context: Context, private val zones: ZoneOutputs) : NsdFinder(context, "_ytmp-zones._tcp", TAG) {
    override fun add(info: NsdServiceInfo): String? {
        @Suppress("DEPRECATION")
        val host = info.host?.hostAddress ?: return null
        val path = info.attributes["path"]?.toString(Charsets.UTF_8)
        val url = ZoneOutputs.fileUrl(host, info.port, path)
        val id = "nsd:${info.serviceName}"
        zones.addSource(id, url)
        Log.d(TAG, "Found zones ${info.serviceName} at $url")
        return id
    }

    override fun remove(id: String) = zones.removeSource(id)

    companion object {
        private const val TAG = "YtmpZones"
    }
}
