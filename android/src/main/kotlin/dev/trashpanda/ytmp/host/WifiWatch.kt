package dev.trashpanda.ytmp.host

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Tells when the phone is on another Wi-Fi network than before ([onMoved]): the speakers and
 * TVs found on the old one can't be reached there. A network is known by its address range and
 * router (no location permission needed, unlike its name). Being off Wi-Fi for longer than
 * [OFFLINE_GRACE_MS] counts as moving too; a short drop on the same network doesn't.
 */
class WifiWatch(context: Context, private val onMoved: () -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    /** The Wi-Fi network the devices were found on, or null after being offline. */
    private var current: String? = null
    private val networks = HashMap<Network, String>()
    private val offline = Runnable {
        synchronized(this) {
            if (networks.isNotEmpty() || current == null) return@Runnable
            current = null
            onMoved()
        }
    }

    fun start() {
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
        connectivity.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                val key = key(properties) ?: return
                synchronized(this@WifiWatch) {
                    networks[network] = key
                    handler.removeCallbacks(offline)
                    val before = current
                    current = key
                    if (before != null && before != key) onMoved()
                }
            }

            override fun onLost(network: Network) {
                synchronized(this@WifiWatch) {
                    networks.remove(network)
                    if (networks.isEmpty()) handler.postDelayed(offline, OFFLINE_GRACE_MS)
                }
            }
        })
    }

    /** "192.168.0.0/24 via 192.168.0.1" */
    private fun key(properties: LinkProperties): String? {
        val link = properties.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val bytes = link.address.address
        for (i in bytes.indices) {
            val bits = (link.prefixLength - i * 8).coerceIn(0, 8)
            bytes[i] = (bytes[i].toInt() and (0xFF shl (8 - bits))).toByte()
        }
        val router = properties.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        return "${InetAddress.getByAddress(bytes).hostAddress}/${link.prefixLength} via $router"
    }

    companion object {
        const val OFFLINE_GRACE_MS = 60_000L
    }
}
