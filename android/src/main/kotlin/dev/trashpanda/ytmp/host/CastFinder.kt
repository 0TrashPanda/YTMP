package dev.trashpanda.ytmp.host

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/** Finds Chromecasts (`_googlecast._tcp`) on the network and adds them to [outputs]. */
class CastFinder(context: Context, private val outputs: CastOutputs) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var discovery: NsdManager.DiscoveryListener? = null

    @Synchronized
    fun start() {
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, error: Int) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, error: Int) {
                Log.w(TAG, "Cast discovery failed: $error")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION") // The replacement needs API 34; this works on 33+.
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, error: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        @Suppress("DEPRECATION")
                        val host = info.host?.hostAddress ?: return
                        val id = info.attributes["id"]?.decodeToString() ?: info.serviceName
                        val name = info.attributes["fn"]?.decodeToString() ?: info.serviceName
                        outputs.add(CastDeviceAddress("cast:$id", name, host, info.port))
                        Log.d(TAG, "Found Cast device $name at $host")
                    }
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                // Lost services carry no TXT record; keep the device until the next scan.
            }
        }
        discovery = listener
        nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    fun stop() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
    }

    companion object {
        private const val TAG = "YtmpCast"
    }
}
