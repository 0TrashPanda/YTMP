package dev.trashpanda.ytmp.share

import java.net.URI

/**
 * What another phone (or another app's Share) handed over: a song, as its YouTube Music link,
 * or a YTMP room link (docs/features/sharing.md). Pure, so it can be tested without Android.
 */
sealed interface Shared {
    /** A YouTube Music or YouTube song; [songId] as YTMP names it (`ytm:<videoId>`). */
    data class Song(val videoId: String, val url: String) : Shared {
        val songId get() = "ytm:$videoId"
    }

    /** A room: `http(s)://<host>/room/<CODE>`. */
    data class Room(val url: String, val code: String) : Shared
}

object SharedLinks {
    private val VIDEO = Regex("^[A-Za-z0-9_-]{11}$")
    private val YOUTUBE = setOf("youtube.com", "music.youtube.com", "youtu.be")
    private val ROOM = Regex("^/room/([A-Za-z0-9]{3,12})/?$")
    private val URL = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    /**
     * The song or room in [text]: a link, or a shared text with a link in it (YouTube Music's
     * Share sends the link, sometimes after the title). Null when there's neither.
     */
    fun parse(text: String?): Shared? {
        if (text == null) return null
        return URL.findAll(text).firstNotNullOfOrNull { parseUrl(it.value.trimEnd('.', ',', ')', '"', '\'')) }
    }

    private fun parseUrl(link: String): Shared? {
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https")) return null
        val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val path = uri.rawPath.orEmpty()
        if (host in YOUTUBE) {
            val id = when {
                host == "youtu.be" -> path.removePrefix("/").trimEnd('/')
                path.trimEnd('/') == "/watch" -> query(uri, "v")
                else -> Regex("^/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})/?$").find(path)?.groupValues?.get(1)
            }
            return id?.takeIf(VIDEO::matches)?.let { Shared.Song(it, songLink(it)) }
        }
        val code = ROOM.find(path)?.groupValues?.get(1) ?: return null
        return Shared.Room(link, code.uppercase())
    }

    private fun query(uri: URI, name: String): String? =
        uri.rawQuery?.split('&')?.firstNotNullOfOrNull { part ->
            val (key, value) = part.split('=', limit = 2).let { it[0] to it.getOrNull(1) }
            value.takeIf { key == name }
        }

    /** A song's link, the one YTMP shares (and YouTube Music opens). */
    fun songLink(videoId: String) = "https://music.youtube.com/watch?v=$videoId"
}
