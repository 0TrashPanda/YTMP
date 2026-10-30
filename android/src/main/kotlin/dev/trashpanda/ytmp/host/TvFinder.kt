package dev.trashpanda.ytmp.host

import android.content.Context
import android.net.wifi.WifiManager
import dev.trashpanda.ytmp.Log
import dev.trashpanda.ytmp.cast.Dial
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Finds TVs with only the YouTube app (DIAL, over SSDP) and adds them to [outputs]. Android
 * drops multicast replies unless a multicast lock is held, so it's held during each search.
 */
class TvFinder(context: Context, private val outputs: YoutubeTvOutputs, private val scope: CoroutineScope) {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val checked = HashSet<String>()
    private var searching: Job? = null

    @Synchronized
    fun start() {
        if (searching != null) return
        searching = scope.launch(Dispatchers.IO) {
            while (true) {
                search()
                delay(SEARCH_INTERVAL_MS)
            }
        }
    }

    private suspend fun search() {
        val lock = wifi.createMulticastLock(TAG).apply { setReferenceCounted(false) }
        val locations = try {
            lock.acquire()
            Dial.search()
        } catch (e: Exception) {
            Log.w(TAG, "TV discovery failed: ${e.message}")
            emptySet()
        } finally {
            runCatching { lock.release() }
        }
        for (location in locations) {
            // Chromecasts and TVs without YouTube are asked once.
            if (!synchronized(checked) { checked.add(location) }) continue
            runCatching { outputs.addLocation(location) }.onFailure {
                synchronized(checked) { checked.remove(location) }
                Log.d(TAG, "DIAL device at $location: ${it.message}")
            }
        }
    }

    /** On another network: search afresh, and the TVs found before go. */
    fun forgetAll() {
        synchronized(checked) { checked.clear() }
        outputs.forgetAll()
    }

    @Synchronized
    fun stop() {
        searching?.cancel()
        searching = null
    }

    companion object {
        private const val TAG = "YtmpTv"
        private const val SEARCH_INTERVAL_MS = 60_000L
    }
}
