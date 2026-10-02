package dev.trashpanda.ytmp.host

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.RoomInfo
import dev.trashpanda.ytmp.protocol.RoomVisibility
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** A room another phone on the same network is hosting. */
@Serializable
data class NearbyRoom(val name: String, val code: String, val url: String)

/**
 * Finding rooms on the local network with mDNS/DNS-SD (`_ytmp._tcp`): announces this
 * phone's public rooms, and lists rooms hosted by other phones.
 */
class Nearby(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val registered = HashMap<String, NsdManager.RegistrationListener>()
    private val found = HashMap<String, NearbyRoom>()
    private val _rooms = MutableStateFlow<List<NearbyRoom>>(emptyList())
    private var discovery: NsdManager.DiscoveryListener? = null

    /** Rooms found on the network, excluding this phone's own. */
    val rooms: StateFlow<List<NearbyRoom>> = _rooms

    /** Announces exactly the public rooms in [rooms]. Solo rooms are never announced. */
    @Synchronized
    fun announce(rooms: List<RoomInfo>) {
        val public = rooms.filter { it.visibility == RoomVisibility.PUBLIC }.associateBy { it.code }
        for (code in registered.keys - public.keys) {
            runCatching { nsd.unregisterService(registered.remove(code)) }
        }
        for ((code, room) in public - registered.keys) {
            val info = NsdServiceInfo().apply {
                serviceName = "YTMP $code"
                serviceType = SERVICE_TYPE
                port = LocalHost.PORT
                setAttribute("code", code)
                setAttribute("name", room.name)
                setAttribute("v", PROTOCOL_VERSION.toString())
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.d(TAG, "Announcing ${info.serviceName}")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                    Log.w(TAG, "Announcing $code failed: $error")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
            }
            registered[code] = listener
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    /** Looks for rooms while the app is visible. */
    @Synchronized
    fun startDiscovery() {
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, error: Int) {
                Log.w(TAG, "Discovery failed: $error")
            }
            override fun onStopDiscoveryFailed(serviceType: String, error: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION") // The replacement needs API 34; this works on 33+.
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, error: Int) = Unit
                    override fun onServiceResolved(info: NsdServiceInfo) = onResolved(info)
                })
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                synchronized(this@Nearby) {
                    found.remove(info.serviceName)
                    _rooms.value = found.values.sortedBy { it.name }
                }
            }
        }
        discovery = listener
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    @Synchronized
    fun stopDiscovery() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        found.clear()
        _rooms.value = emptyList()
    }

    @Synchronized
    private fun onResolved(info: NsdServiceInfo) {
        val code = info.attributes["code"]?.decodeToString() ?: return
        if (code in registered) return // One of our own rooms.
        @Suppress("DEPRECATION")
        val address = info.host?.hostAddress ?: return
        val name = info.attributes["name"]?.decodeToString() ?: code
        found[info.serviceName] = NearbyRoom(name, code, "http://$address:${info.port}")
        _rooms.value = found.values.sortedBy { it.name }
    }

    companion object {
        private const val SERVICE_TYPE = "_ytmp._tcp"
        private const val TAG = "YtmpNearby"
    }
}
