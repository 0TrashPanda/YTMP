package dev.trashpanda.ytmp.host

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import dev.trashpanda.ytmp.NotSignedInException
import dev.trashpanda.ytmp.OnDeviceYtm
import dev.trashpanda.ytmp.core.PersonalCatalog
import dev.trashpanda.ytmp.protocol.HomePage
import dev.trashpanda.ytmp.protocol.PlaylistPage
import dev.trashpanda.ytmp.protocol.PlaylistSummary
import dev.trashpanda.ytmp.protocol.YoutubeAccount
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.minutes

/**
 * The phone owner's YouTube Music account. Signing in happens in [YoutubeLoginActivity];
 * its cookies are kept in the app's private storage and only given to the on-device YTM
 * module, never sent anywhere else. The host only answers its own app with it (see
 * `personal` in ytmpModule), so guests in a room on this phone never see your library.
 */
class PhoneYoutubeAccount(context: Context, private val ytm: () -> OnDeviceYtm) : PersonalCatalog {
    private val prefs = context.getSharedPreferences("youtube", Context.MODE_PRIVATE)
    private val lock = Mutex()

    /** The cookies the Python module is signed in with (it forgets them when the app restarts). */
    private var loaded: String? = null
    private var current: YoutubeAccount? = null

    private val homes = TtlCache<Unit, HomePage>(1, 30.minutes, System::currentTimeMillis)
    private val libraries = TtlCache<Unit, HomePage>(1, 5.minutes, System::currentTimeMillis)
    private val playlists = TtlCache<String, PlaylistPage>(50, 5.minutes, System::currentTimeMillis)

    private var cookie: String?
        get() = prefs.getString("cookie", null)
        set(value) = prefs.edit().putString("cookie", value).apply()

    /** Signs in with the cookies from [YoutubeLoginActivity]; throws [SourceException] when they don't work. */
    suspend fun signIn(cookie: String): YoutubeAccount = lock.withLock {
        val account = ytm().signIn(cookie)
        this.cookie = cookie
        loaded = cookie
        current = account
        forget()
        account
    }

    override suspend fun signOut(): Unit = lock.withLock {
        cookie = null
        loaded = null
        current = null
        forget()
        ytm().signOut()
        // Also out of the sign-in page, so signing in again can pick another account. Nothing
        // else in the app uses cookies.
        Handler(Looper.getMainLooper()).post { CookieManager.getInstance().removeAllCookies(null) }
        Unit
    }

    override suspend fun account(): YoutubeAccount? = lock.withLock { ensureSignedIn() }

    override suspend fun home(): HomePage = homes.get(Unit) { requireSignedIn(); ytm().personalHome() }

    override suspend fun library(): HomePage = libraries.get(Unit) { requireSignedIn(); ytm().library() }

    override suspend fun playlist(id: String): PlaylistPage = playlists.get(id) { requireSignedIn(); ytm().personalPlaylist(id) }

    // Likes are looked up per song (the one playing) and remembered; changing one updates it.
    private val likes = TtlCache<String, Boolean>(500, 30.minutes, System::currentTimeMillis)
    private val likesChanged = mutableMapOf<String, Boolean>()

    override suspend fun liked(songId: String): Boolean {
        synchronized(likesChanged) { likesChanged[songId] }?.let { return it }
        return likes.get(songId) { requireSignedIn(); ytm().liked(songId) }
    }

    override suspend fun setLiked(songId: String, liked: Boolean) {
        requireSignedIn()
        ytm().setLiked(songId, liked)
        synchronized(likesChanged) { likesChanged[songId] = liked }
        playlists.clear() // Liked music changed
    }

    override suspend fun ownPlaylists(): List<PlaylistSummary> { requireSignedIn(); return ytm().ownPlaylists() }

    override suspend fun addToPlaylist(playlistId: String, songIds: List<String>) {
        requireSignedIn()
        ytm().addToPlaylist(playlistId, songIds)
        playlists.clear()
    }

    override suspend fun createPlaylist(title: String, songIds: List<String>): String {
        requireSignedIn()
        return ytm().createPlaylist(title, songIds).also { libraries.clear() }
    }

    private suspend fun requireSignedIn() {
        lock.withLock { ensureSignedIn() } ?: throw SourceException("Not signed in to YouTube Music")
    }

    /** After an app restart, signs the Python module in again with the saved cookies. */
    private suspend fun ensureSignedIn(): YoutubeAccount? {
        val saved = cookie ?: return null
        if (loaded == saved) return current
        return try {
            ytm().signIn(saved).also {
                loaded = saved
                current = it
            }
        } catch (e: NotSignedInException) {
            // Signed out elsewhere, or the cookies expired: sign in again in the app.
            // (Other errors, like being offline, keep the sign-in for next time.)
            Log.w(TAG, "YouTube Music sign-in no longer works: ${e.message}")
            cookie = null
            null
        }
    }

    private fun forget() {
        likes.clear()
        synchronized(likesChanged) { likesChanged.clear() }
        homes.clear()
        libraries.clear()
        playlists.clear()
    }

    private companion object {
        const val TAG = "YtmpYoutube"
    }
}
