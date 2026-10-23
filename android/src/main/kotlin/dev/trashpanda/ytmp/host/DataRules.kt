package dev.trashpanda.ytmp.host

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import androidx.core.content.edit
import kotlinx.coroutines.delay

/**
 * The owner's say over work the app does in the background or on request: only on Wi-Fi (an
 * unmetered network), and for looking up phone songs also only while charging (Settings →
 * Playback → Song cache). Work that waits checks again every [POLL_MS].
 */
class DataRules(private val context: Context) {
    private val prefs = context.getSharedPreferences("ytmp.cache", Context.MODE_PRIVATE)

    /** Songs kept in the background (from their 2nd play) wait for Wi-Fi. On by default: it's data nobody asked for. */
    var fetchWifiOnly: Boolean
        get() = prefs.getBoolean(FETCH_WIFI, true)
        set(value) = prefs.edit { putBoolean(FETCH_WIFI, value) }

    /** Downloads from the song menu wait for Wi-Fi. */
    var downloadsWifiOnly: Boolean
        get() = prefs.getBoolean(DOWNLOADS_WIFI, false)
        set(value) = prefs.edit { putBoolean(DOWNLOADS_WIFI, value) }

    var localWifiOnly: Boolean
        get() = prefs.getBoolean(LOCAL_WIFI, false)
        set(value) = prefs.edit { putBoolean(LOCAL_WIFI, value) }

    var localCharging: Boolean
        get() = prefs.getBoolean(LOCAL_CHARGING, false)
        set(value) = prefs.edit { putBoolean(LOCAL_CHARGING, value) }

    /** Connected through Wi-Fi (or anything else not paid per megabyte, like ethernet). */
    fun onWifi(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    fun charging(): Boolean {
        val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged != 0
    }

    /** Why phone songs aren't looked up now, or null. */
    fun localHold(): String? = when {
        localWifiOnly && !onWifi() -> "Wi-Fi"
        localCharging && !charging() -> "charging"
        else -> null
    }

    /** Returns when a song kept in the background may be fetched. */
    suspend fun awaitFetch() {
        while (fetchWifiOnly && !onWifi()) delay(POLL_MS)
    }

    /** Returns when a download may start: on Wi-Fi, or when it isn't limited, or [now]. */
    suspend fun awaitDownload(now: () -> Boolean) {
        while (downloadsWifiOnly && !onWifi() && !now()) delay(DOWNLOAD_POLL_MS)
    }

    /** A download would have to wait for Wi-Fi right now. */
    fun downloadWaits() = downloadsWifiOnly && !onWifi()

    private companion object {
        const val POLL_MS = 15_000L
        const val DOWNLOAD_POLL_MS = 1_000L
        const val FETCH_WIFI = "fetchWifiOnly"
        const val DOWNLOADS_WIFI = "downloadsWifiOnly"
        const val LOCAL_WIFI = "localWifiOnly"
        const val LOCAL_CHARGING = "localCharging"
    }
}
