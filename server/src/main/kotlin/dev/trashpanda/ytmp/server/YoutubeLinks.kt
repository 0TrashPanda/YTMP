package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.FinishedPlay
import dev.trashpanda.ytmp.core.PersonalCatalog
import dev.trashpanda.ytmp.host.ApiException
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.HomePage
import dev.trashpanda.ytmp.protocol.PlaylistPage
import dev.trashpanda.ytmp.protocol.PlaylistSummary
import dev.trashpanda.ytmp.protocol.RoomVisibility
import dev.trashpanda.ytmp.protocol.YoutubeAccount
import dev.trashpanda.ytmp.protocol.YoutubeHistory
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * YouTube Music sign-ins of this server's accounts: each account can connect its own, and
 * then gets its Home, Library, likes, playlists and history in every browser
 * (docs/features/accounts.md). The cookies are stored encrypted with a key from [secret]
 * (the module key, which lives outside the database), and only ever sent to the YTM module.
 */
class YoutubeLinks(
    private val accounts: Accounts,
    secret: String,
    private val module: RemoteSourceModule,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("ytmp youtube sign-in\n$secret".toByteArray()), "AES")
    private val random = SecureRandom()

    /** Who each account is on YouTube Music, so not every page asks the module. */
    private val known = ConcurrentHashMap<String, Pair<YoutubeAccount, Long>>()

    /** The YouTube Music of [accountId] (an id in [Accounts]), signed in or not. */
    fun of(accountId: String): PersonalCatalog = AccountYoutube(accountId)

    /**
     * Adds a finished song to the YouTube Music history of everyone in the room who
     * connected theirs and wants it there (see [YoutubeHistory]); like YouTube, after 30 s.
     * [localId] maps an account ID (`user@issuer`) to one of this server's accounts.
     */
    fun report(play: FinishedPlay, localId: (String) -> String?) {
        if (play.heardMs < HISTORY_MIN_MS && play.skipped) return
        for (listener in play.listeners) {
            val id = listener.accountId?.let(localId) ?: continue
            scope.launch {
                val wanted = when (history(id)) {
                    YoutubeHistory.OFF -> false
                    YoutubeHistory.SOLO -> play.visibility == RoomVisibility.PRIVATE
                    YoutubeHistory.ALL -> true
                }
                val cookie = cookie(id)
                if (!wanted || cookie == null) return@launch
                try {
                    module.addToMyHistory(cookie, play.item.song.id)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    log.warn("Couldn't add a play to the YouTube Music history of {}: {}", listener.accountId, e.message)
                }
            }
        }
    }

    private fun cookie(accountId: String): String? = accounts.data(accountId, COOKIE)?.takeIf { it.isNotEmpty() }?.let(::decrypt)

    private fun history(accountId: String): YoutubeHistory =
        accounts.data(accountId, HISTORY)?.let { saved -> YoutubeHistory.entries.firstOrNull { it.name == saved } } ?: YoutubeHistory.SOLO

    private inner class AccountYoutube(private val accountId: String) : PersonalCatalog {
        private fun signedIn(): String = cookie(accountId) ?: throw notSignedIn()

        /** Runs [call] with the cookies; an expired sign-in counts as signed out. */
        private suspend fun <T> withCookie(call: suspend (String) -> T): T = try {
            call(signedIn())
        } catch (e: RemoteSourceModule.ModuleException) {
            if (e.code == "not_signed_in") throw notSignedIn() else throw e
        }

        override suspend fun account(): YoutubeAccount? {
            val cookie = cookie(accountId) ?: return null
            known[accountId]?.takeIf { clock() - it.second < ACCOUNT_TTL_MS }?.let { return it.first }
            val account = try {
                module.myAccount(cookie)
            } catch (e: RemoteSourceModule.ModuleException) {
                if (e.code == "not_signed_in") null else throw e
            } ?: return null
            known[accountId] = account to clock()
            return account
        }

        override suspend fun signIn(cookie: String): YoutubeAccount {
            val clean = extractCookie(cookie)
                ?: throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "That doesn't contain YouTube Music's sign-in cookies")
            val account = try {
                module.myAccount(clean)
            } catch (e: RemoteSourceModule.ModuleException) {
                if (e.code == "not_signed_in") null else throw e
            } ?: throw ApiException(HttpStatusCode.BadRequest, ErrorCode.INVALID, "YouTube Music didn't accept that sign-in (signed out, or expired)")
            accounts.setData(accountId, COOKIE, encrypt(clean))
            known[accountId] = account to clock()
            return account
        }

        override suspend fun signOut() {
            accounts.setData(accountId, COOKIE, "")
            known.remove(accountId)
        }

        override suspend fun home(): HomePage = withCookie { module.myHome(it) }

        override suspend fun library(): HomePage = withCookie { module.myLibrary(it) }

        override suspend fun playlist(id: String): PlaylistPage = withCookie { module.myPlaylist(it, id) }

        override suspend fun liked(songId: String): Boolean = withCookie { module.myLike(it, songId) }

        override suspend fun setLiked(songId: String, liked: Boolean) = withCookie { module.setMyLike(it, songId, liked) }

        override suspend fun ownPlaylists(): List<PlaylistSummary> = withCookie { module.myPlaylists(it) }

        override suspend fun addToPlaylist(playlistId: String, songIds: List<String>) = withCookie { module.addToMyPlaylist(it, playlistId, songIds) }

        override suspend fun createPlaylist(title: String, songIds: List<String>): String = withCookie { module.myNewPlaylist(it, title, songIds) }

        override suspend fun historySetting(): YoutubeHistory = history(accountId)

        override suspend fun setHistorySetting(history: YoutubeHistory) = accounts.setData(accountId, HISTORY, history.name)

        override suspend fun addToHistory(songId: String) = withCookie { module.addToMyHistory(it, songId) }
    }

    private fun notSignedIn() = ApiException(HttpStatusCode.Unauthorized, ErrorCode.PERMISSION_DENIED, "Not signed in to YouTube Music (or the sign-in expired)")

    internal fun encrypt(text: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv)) }
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(text.toByteArray()))
    }

    /** Null when it can't be read, e.g. after the module key changed: then you sign in again. */
    internal fun decrypt(stored: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(stored)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes, 0, 12)) }
        String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }.getOrNull()

    companion object {
        private val log = LoggerFactory.getLogger(YoutubeLinks::class.java)
        private const val COOKIE = "youtube_cookie"
        private const val HISTORY = "youtube_history"
        private const val ACCOUNT_TTL_MS = 10 * 60_000L

        /** A skipped song counts as played after this long, like on YouTube. */
        private const val HISTORY_MIN_MS = 30_000L

        /**
         * The cookies from what you pasted: the Cookie header from a browser's developer tools
         * (one line, or "cookie" and its value on two lines), a whole copied request (headers
         * or "Copy as cURL"), or just the cookies. Null if YouTube Music's sign-in isn't in it.
         */
        fun extractCookie(pasted: String): String? {
            val text = pasted.trim()
            val lines = text.lines().map(String::trim)
            val found = sequence {
                // curl: -b '…' / --cookie '…' / -H 'cookie: …'
                Regex("""(?:-b|--cookie)\s+(['"])(.*?)\1""", RegexOption.DOT_MATCHES_ALL).find(text)?.let { yield(it.groupValues[2]) }
                Regex("""-H\s+(['"])cookie:\s*(.*?)\1""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text)?.let { yield(it.groupValues[2]) }
                for ((i, line) in lines.withIndex()) {
                    // "Cookie: …", or "cookie" with the value on the next line (Chrome's header list).
                    Regex("""^cookie:\s*(.+)$""", RegexOption.IGNORE_CASE).find(line)?.let { yield(it.groupValues[1]) }
                    if (line.equals("cookie", ignoreCase = true) || line.equals("cookie:", ignoreCase = true)) lines.getOrNull(i + 1)?.let { yield(it) }
                }
                yield(text)
            }
            return found.map { it.trim().removeSuffix(";").trim() }.firstOrNull { "__Secure-3PAPISID=" in it && '\n' !in it }
        }
    }
}
