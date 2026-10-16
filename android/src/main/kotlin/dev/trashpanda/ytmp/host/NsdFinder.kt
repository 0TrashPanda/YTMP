package dev.trashpanda.ytmp.host

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.trashpanda.ytmp.Log

/**
 * Finds one kind of device (an mDNS service type) on the phone's network for an output list.
 * A device leaves the list again when the network says it's gone (after [LOST_GRACE_MS], in
 * case it's back right away), when a new search doesn't find it within [SEARCH_GRACE_MS], and
 * when the phone moves to another network ([forgetAll]).
 */
abstract class NsdFinder(context: Context, private val serviceType: String, private val tag: String) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var discovery: NsdManager.DiscoveryListener? = null
    /** Service name → id of the device it was added as. */
    private val found = HashMap<String, String>()
    /** Service name → when a search last reported it (elapsed realtime). */
    private val seenAt = HashMap<String, Long>()
    private val leaving = HashMap<String, Runnable>()
    private var searchStartedAt = 0L
    private val afterSearch = Runnable { forgetUnseen() }

    /** Adds the device [info] announces; its id, or null if it isn't one. */
    protected abstract fun add(info: NsdServiceInfo): String?

    protected abstract fun remove(id: String)

    @Synchronized
    fun start() {
        if (discovery != null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, error: Int) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, error: Int) {
                Log.w(tag, "Discovery of $serviceType failed: $error")
            }

            override fun onServiceFound(info: NsdServiceInfo) {
                val name = info.serviceName
                synchronized(this@NsdFinder) {
                    seenAt[name] = SystemClock.elapsedRealtime()
                    leaving.remove(name)?.let(handler::removeCallbacks)
                }
                NsdQueue.resolve(nsd, info) { resolved ->
                    val id = add(resolved) ?: return@resolve
                    synchronized(this@NsdFinder) { found[name] = id }
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                val name = info.serviceName
                val gone = Runnable { forget(name, "gone from the network") }
                synchronized(this@NsdFinder) {
                    leaving.remove(name)?.let(handler::removeCallbacks)
                    leaving[name] = gone
                }
                handler.postDelayed(gone, LOST_GRACE_MS)
            }
        }
        discovery = listener
        searchStartedAt = SystemClock.elapsedRealtime()
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        // Found earlier but not by this search: gone (switched off, or out of reach).
        handler.removeCallbacks(afterSearch)
        handler.postDelayed(afterSearch, SEARCH_GRACE_MS)
    }

    @Synchronized
    fun stop() {
        handler.removeCallbacks(afterSearch)
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
    }

    /** The phone is on another network now: none of the devices found are here, look again. */
    @Synchronized
    fun forgetAll() {
        for (name in found.keys.toList()) forget(name, "on another network")
        seenAt.clear()
        if (discovery != null) {
            stop()
            start()
        }
    }

    @Synchronized
    private fun forgetUnseen() {
        if (discovery == null) return
        for (name in found.keys.toList()) if ((seenAt[name] ?: 0) < searchStartedAt) forget(name, "not found again")
    }

    @Synchronized
    private fun forget(name: String, why: String) {
        leaving.remove(name)?.let(handler::removeCallbacks)
        seenAt.remove(name)
        val id = found.remove(name) ?: return
        remove(id)
        Log.d(tag, "Removed $name ($why)")
    }

    companion object {
        const val LOST_GRACE_MS = 30_000L
        const val SEARCH_GRACE_MS = 20_000L
    }
}
