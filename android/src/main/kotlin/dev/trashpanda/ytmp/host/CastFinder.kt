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
                NsdQueue.resolve(nsd, info) { resolved ->
                    @Suppress("DEPRECATION")
                    val host = resolved.host?.hostAddress ?: return@resolve
                    val id = resolved.attributes["id"]?.decodeToString() ?: resolved.serviceName
                    val name = resolved.attributes["fn"]?.decodeToString() ?: resolved.serviceName
                    outputs.add(CastDeviceAddress("cast:$id", name, host, resolved.port))
                    Log.d(TAG, "Found Cast device $name at $host")
                }
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
