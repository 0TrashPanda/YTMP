package dev.trashpanda.ytmp.host

import android.content.Context
import android.util.Base64
import android.util.Log
import dev.trashpanda.ytmp.protocol.MyPhone
import dev.trashpanda.ytmp.protocol.PhoneCopyAsked
import dev.trashpanda.ytmp.protocol.ProtocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * This phone's side of *Songs from your phone* (docs/implementation/apps/desktop-app.md#songs-from-your-phone-):
 * the owner's computer asks through their server ([check]: the open app waits on the server for
 * that); asked, the phone tells the server where it is on
 * its network and shows itself there for a while. [sendNow] does that by hand, also without
 * the server. The phone isn't announced otherwise.
 *
 * The keys ([CopyKeys]) let a computer that copied once with a login copy again without one.
 */
class PhoneCopyLink(
    context: Context,
    private val authLink: AuthLink,
    private val sync: RoomSync,
    private val lanUrl: () -> String?,
    private val scope: CoroutineScope,
) : CopyKeys {
    private val prefs = context.getSharedPreferences("phone-copy", Context.MODE_PRIVATE)

    /** Shows this phone on its network for a while (set by the app: Nearby.announceDevice). */
    @Volatile
    var announce: (id: String, name: String, ownerMark: String, forMs: Long) -> Unit = { _, _, _, _ -> }

    override val deviceId: String get() = sync.hostId
    override val name: String get() = sync.hostName

    @Synchronized
    override fun make(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val key = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val kept = prefs.getStringSet(KEYS, emptySet()).orEmpty().toMutableSet()
        kept += hash(key)
        prefs.edit().putStringSet(KEYS, kept).apply()
        Log.i(TAG, "A computer can copy this phone's songs now (${kept.size} in all)")
        return key
    }

    override fun valid(key: String): Boolean = hash(key) in prefs.getStringSet(KEYS, emptySet()).orEmpty()

    override fun showNow(): Boolean {
        sendNow()
        return true
    }

    /** The ask this phone answered last (its end, ms): the server only says a newer one. */
    @Volatile
    private var answered = 0L

    /**
     * Asks the owner's server whether a computer wants this phone's songs; if so, tells it where
     * the phone is and shows itself. With [waitSeconds] the server holds the answer until the
     * computer asks (the open app: it answers at once). True when asked; false also when there's
     * no server or it can't be reached.
     */
    suspend fun check(waitSeconds: Int = 0): Boolean {
        val owner = authLink.ownerAccount ?: return false
        val server = sync.serverFor(owner) ?: return false
        val token = sync.tokenFor(owner) ?: return false
        val (status, body) = sync.call("GET", "$server/api/auth/phone-copy?after=$answered&wait=$waitSeconds", token, null, readTimeoutMs = waitSeconds * 1000 + 15_000)
            ?: return false
        if (status != 200) return false
        val ask = runCatching { ProtocolJson.decodeFromString(PhoneCopyAsked.serializer(), body) }.getOrNull() ?: return false
        if (!ask.asked) return false
        answered = ask.until ?: System.currentTimeMillis()
        Log.i(TAG, "The owner's computer asks for this phone's songs")
        report(server, token)
        show(owner)
        return true
    }

    /** By hand (*Send songs to my computer*): shows the phone on its network, and tells the server where it is when it can. */
    fun sendNow() {
        val owner = authLink.ownerAccount ?: return
        show(owner)
        scope.launch {
            val server = sync.serverFor(owner) ?: return@launch
            val token = sync.tokenFor(owner) ?: return@launch
            report(server, token)
        }
    }

    private suspend fun report(server: String, token: String) {
        val url = lanUrl() ?: return
        val phone = MyPhone(deviceId, name, url)
        sync.call("POST", "$server/api/auth/phone-copy/here", token, ProtocolJson.encodeToString(MyPhone.serializer(), phone))
    }

    private fun show(owner: String) = announce(deviceId, name, ownerMark(owner), SHOW_MS)

    companion object {
        private const val TAG = "YtmpPhoneCopy"
        private const val KEYS = "keys"

        /** How long the phone shows itself on its network once asked. */
        const val SHOW_MS = 10 * 60_000L

        private fun hash(key: String) = MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

        /**
         * Which account's phone it is, without saying the account: the computer works out the same
         * for its own account and only lists phones that match (`PhoneCopy.ownerMark` on the desktop).
         */
        fun ownerMark(account: String) = hash("ytmp-phone:$account").take(16)
    }
}
