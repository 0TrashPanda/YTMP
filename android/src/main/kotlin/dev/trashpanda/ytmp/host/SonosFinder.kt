package dev.trashpanda.ytmp.host

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/** Finds Sonos speakers (`_sonos._tcp`) on the network and adds them to [outputs]. */
class SonosFinder(context: Context, private val outputs: SonosOutputs) {
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
                Log.w(TAG, "Sonos discovery failed: $error")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                NsdQueue.resolve(nsd, info) { resolved ->
                    @Suppress("DEPRECATION")
                    val host = resolved.host?.hostAddress ?: return@resolve
                    // Named after its room, read from the speaker itself.
                    outputs.addHost(host)
                    Log.d(TAG, "Found Sonos at $host")
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
        }
        discovery = listener
        nsd.discoverServices("_sonos._tcp", NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    fun stop() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
    }

    companion object {
        private const val TAG = "YtmpSonos"
    }
}
