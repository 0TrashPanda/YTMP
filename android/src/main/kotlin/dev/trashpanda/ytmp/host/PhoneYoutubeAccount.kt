package dev.trashpanda.ytmp.host

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.trashpanda.ytmp.Log
import android.webkit.CookieManager
import dev.trashpanda.ytmp.NotSignedInException
import dev.trashpanda.ytmp.OnDeviceYtm
import dev.trashpanda.ytmp.core.PersonalCatalog
import dev.trashpanda.ytmp.protocol.PodcastPage
import dev.trashpanda.ytmp.protocol.HomePage
import dev.trashpanda.ytmp.protocol.PlaylistPage
import dev.trashpanda.ytmp.protocol.PlaylistItemRef
import dev.trashpanda.ytmp.protocol.LikeStatus
import dev.trashpanda.ytmp.protocol.PlaylistSummary
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.YoutubeAccount
import dev.trashpanda.ytmp.protocol.SearchPage
import dev.trashpanda.ytmp.protocol.SearchType
import dev.trashpanda.ytmp.protocol.Song
import dev.trashpanda.ytmp.protocol.YoutubeHistory
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
    private val playlists = TtlCache<String, PlaylistPage>(200, 5.minutes, System::currentTimeMillis)

    private var cookie: String?
        get() = prefs.getString("cookie", null)
        set(value) = prefs.edit().putString("cookie", value).apply()

    /** Who's signed in, remembered so the app can say so without starting Python. */
    private var savedAccount: YoutubeAccount?
        get() = prefs.getString("account", null)?.let { runCatching { ProtocolJson.decodeFromString(YoutubeAccount.serializer(), it) }.getOrNull() }
        set(value) = prefs.edit().putString("account", value?.let { ProtocolJson.encodeToString(YoutubeAccount.serializer(), it) }).apply()

    /**
     * The sign-in, to use it on your server account too (see MainActivity's `youtubeCookie`):
     * the account and its cookies, or null when signed out.
     */
    fun shareable(): Pair<YoutubeAccount, String>? {
        val account = savedAccount ?: return null
        return account to (cookie ?: return null)
    }

    /** Signs in with the cookies from [YoutubeLoginActivity]; throws [SourceException] when they don't work. */
    override suspend fun signIn(cookie: String): YoutubeAccount = lock.withLock {
        val account = ytm().signIn(cookie)
        this.cookie = cookie
        loaded = cookie
        current = account
        savedAccount = account
        forget()
        account
    }

    override suspend fun signOut(): Unit = lock.withLock {
        cookie = null
        loaded = null
        current = null
        savedAccount = null
        forget()
        ytm().signOut()
        // Also out of the sign-in page, so signing in again can pick another account. Nothing
        // else in the app uses cookies.
        Handler(Looper.getMainLooper()).post { CookieManager.getInstance().removeAllCookies(null) }
        Unit
    }

    override suspend fun account(): YoutubeAccount? = lock.withLock { ensureSignedIn() }

    override suspend fun home(): HomePage = homes.get(Unit) { requireSignedIn(); ytm().personalHome() }

    override suspend fun freshHome(): HomePage {
        homes.clear()
        return home()
    }

    override suspend fun library(): HomePage = libraries.get(Unit) { requireSignedIn(); ytm().library() }

    override suspend fun playlist(id: String, more: String?): PlaylistPage =
        playlists.get(if (more == null) id else "$id\n$more") { requireSignedIn(); ytm().personalPlaylist(id, more) }

    override suspend fun podcast(id: String): PodcastPage {
        requireSignedIn()
        return ytm().personalPodcast(id)
    }

    override suspend fun setPodcastSaved(id: String, saved: Boolean) {
        requireSignedIn()
        ytm().setPodcastSaved(id, saved)
        // Library and Home (New episodes) change with it.
        libraries.clear()
        homes.clear()
    }

    // Thumbs are looked up per song (the one playing) and remembered; changing one updates it.
    private val ratings = TtlCache<String, LikeStatus>(500, 30.minutes, System::currentTimeMillis)
    private val ratingsChanged = mutableMapOf<String, LikeStatus>()

    override suspend fun liked(songId: String): Boolean = rating(songId).liked

    override suspend fun setLiked(songId: String, liked: Boolean) = setRating(songId, LikeStatus(liked))

    override suspend fun rating(songId: String): LikeStatus {
        synchronized(ratingsChanged) { ratingsChanged[songId] }?.let { return it }
        return ratings.get(songId) { requireSignedIn(); ytm().rating(songId) }
    }

    override suspend fun setRating(songId: String, rating: LikeStatus) {
        requireSignedIn()
        ytm().setRating(songId, rating)
        synchronized(ratingsChanged) { ratingsChanged[songId] = rating }
        playlists.clear() // Liked music changed
    }

    override suspend fun removeFromPlaylist(playlistId: String, items: List<PlaylistItemRef>) {
        requireSignedIn()
        ytm().removeFromPlaylist(playlistId, items)
        playlists.clear()
    }

    override suspend fun movePlaylistItem(playlistId: String, itemId: String, beforeItemId: String?) {
        requireSignedIn()
        ytm().movePlaylistItem(playlistId, itemId, beforeItemId)
        playlists.clear()
    }

    override suspend fun renamePlaylist(playlistId: String, title: String) {
        requireSignedIn()
        ytm().renamePlaylist(playlistId, title)
        playlists.clear()
        libraries.clear()
    }

    override suspend fun deletePlaylist(playlistId: String) {
        requireSignedIn()
        ytm().deletePlaylist(playlistId)
        playlists.clear()
        libraries.clear()
    }

    override suspend fun historySetting(): YoutubeHistory =
        prefs.getString("history", null)?.let { saved -> YoutubeHistory.entries.firstOrNull { it.name == saved } } ?: YoutubeHistory.SOLO

    override suspend fun setHistorySetting(history: YoutubeHistory) = prefs.edit().putString("history", history.name).apply()

    override suspend fun addToHistory(songId: String) {
        requireSignedIn()
        ytm().addToHistory(songId)
    }

    override suspend fun personalize(): Boolean = prefs.getBoolean("personalize", true)

    override suspend fun setPersonalize(on: Boolean) = prefs.edit().putBoolean("personalize", on).apply()

    override suspend fun search(query: String, type: SearchType): SearchPage { requireSignedIn(); return ytm().personalSearch(query, type) }

    override suspend fun suggestions(query: String): List<String> { requireSignedIn(); return ytm().personalSuggestions(query) }

    override suspend fun radio(seedSongId: String): List<Song> { requireSignedIn(); return ytm().personalRadio(seedSongId) }

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

    /** The [count] songs liked last (Liked music, newest first), in as many parts as that takes. */
    suspend fun recentLikes(count: Int): List<Song> {
        requireSignedIn()
        val songs = mutableListOf<Song>()
        var page = ytm().personalPlaylist(LIKED_MUSIC, null)
        while (true) {
            songs += page.songs
            val more = page.more ?: break
            if (songs.size >= count) break
            page = ytm().personalPlaylist(LIKED_MUSIC, more)
        }
        return songs.take(count)
    }

    /** The account's YouTube Music history: each song and about how many days ago. */
    suspend fun history(): List<Pair<Song, Int?>> {
        requireSignedIn()
        return ytm().history()
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
                savedAccount = it
            }
        } catch (e: NotSignedInException) {
            // Signed out elsewhere, or the cookies expired: sign in again in the app.
            // (Other errors, like being offline, keep the sign-in for next time.)
            Log.w(TAG, "YouTube Music sign-in no longer works: ${e.message}")
            cookie = null
            savedAccount = null
            null
        }
    }

    private fun forget() {
        ratings.clear()
        synchronized(ratingsChanged) { ratingsChanged.clear() }
        homes.clear()
        libraries.clear()
        playlists.clear()
    }

    private companion object {
        const val TAG = "YtmpYoutube"
        const val LIKED_MUSIC = "LM"
    }
}
