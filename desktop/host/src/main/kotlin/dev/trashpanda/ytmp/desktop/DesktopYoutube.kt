package dev.trashpanda.ytmp.desktop

import dev.trashpanda.ytmp.host.RemoteSourceModule
import dev.trashpanda.ytmp.protocol.PlaylistPage
import dev.trashpanda.ytmp.protocol.SearchItem
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Your YouTube Music sign-in, for the song cache on this computer (recent likes, your history,
 * playlists kept offline). It's on your server account: the window passes it on when it's
 * logged in there (`PUT /api/desktop/youtube`). Kept in this app's own folder and given only to
 * this computer's module, like the phone keeps its sign-in.
 */
class DesktopYoutube(private val file: File, private val ytm: RemoteSourceModule) {
    @Serializable
    private data class Saved(val cookie: String? = null)

    @Volatile private var cookie: String? = runCatching { json.decodeFromString<Saved>(file.readText()).cookie }.getOrNull()

    val signedIn: Boolean get() = cookie != null

    /** A new sign-in (null: signed out there); true when it changed. */
    @Synchronized
    fun set(cookie: String?): Boolean {
        val wanted = cookie?.takeIf { it.isNotBlank() }
        if (wanted == this.cookie) return false
        this.cookie = wanted
        file.parentFile?.mkdirs()
        if (wanted == null) file.delete() else file.writeText(json.encodeToString(Saved.serializer(), Saved(wanted)))
        return true
    }

    private fun need(): String = cookie ?: throw IllegalStateException("Not signed in to YouTube Music")

    /** The [count] songs liked last (Liked music, newest first), in as many parts as that takes. */
    suspend fun recentLikes(count: Int): List<Song> {
        val me = need()
        val songs = mutableListOf<Song>()
        var page = ytm.myPlaylist(me, LIKED_MUSIC, null)
        while (true) {
            songs += page.songs
            val more = page.more ?: break
            if (songs.size >= count) break
            page = ytm.myPlaylist(me, LIKED_MUSIC, more)
        }
        return songs.take(count)
    }

    suspend fun history(): List<Pair<Song, Int?>> = ytm.myHistory(need())

    /** The playlists in your library; null when not signed in. */
    suspend fun playlists(): List<SearchItem.PlaylistResult>? {
        val me = cookie ?: return null
        return ytm.myLibrary(me).sections.firstOrNull { it.title == "Playlists" }?.items.orEmpty().filterIsInstance<SearchItem.PlaylistResult>()
    }

    suspend fun playlist(id: String, more: String?): PlaylistPage = ytm.myPlaylist(need(), id, more)

    private companion object {
        const val LIKED_MUSIC = "LM"
        val json = Json { ignoreUnknownKeys = true }
    }
}
