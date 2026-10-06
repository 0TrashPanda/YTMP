package dev.trashpanda.ytmp.host

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper

/**
 * Resolves mDNS services one at a time. Before Android 14, [NsdManager] resolves only one
 * service at a time (the others fail with `FAILURE_ALREADY_ACTIVE`), and the Cast, Sonos and
 * room finders all resolve what they find: they share this queue.
 */
object NsdQueue {
    private val waiting = ArrayDeque<Pair<NsdServiceInfo, (NsdServiceInfo) -> Unit>>()
    private var busy = false
    private val handler = Handler(Looper.getMainLooper())

    /** Resolves [info]; [onResolved] runs when it worked (failures are dropped, like before). */
    @Synchronized
    fun resolve(nsd: NsdManager, info: NsdServiceInfo, onResolved: (NsdServiceInfo) -> Unit) {
        waiting += info to onResolved
        next(nsd)
    }

    @Synchronized
    private fun next(nsd: NsdManager) {
        if (busy) return
        val (info, onResolved) = waiting.removeFirstOrNull() ?: return
        busy = true
        var finished = false
        val done = Runnable {
            synchronized(this) {
                if (finished) return@Runnable
                finished = true
                busy = false
            }
            next(nsd)
        }
        // A lookup that never answers mustn't block the others.
        handler.postDelayed(done, TIMEOUT_MS)
        @Suppress("DEPRECATION") // The replacement needs API 34.
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                handler.removeCallbacks(done)
                done.run()
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                handler.removeCallbacks(done)
                onResolved(info)
                done.run()
            }
        })
    }

    private const val TIMEOUT_MS = 10_000L
}
