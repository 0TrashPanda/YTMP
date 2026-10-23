package dev.trashpanda.ytmp.host

import io.ktor.server.application.ApplicationCall

/**
 * The phone's own music files that are a YouTube Music song ([LocalMatcher]): they play from the
 * file and are listed with the cached songs, like a download but not made by the app.
 */
class PhoneMatchedSongs(private val matcherOf: () -> LocalMatcher, private val media: PhoneLibrary) : DownloadedSongs {
    private val matcher get() = matcherOf()
    private var listed: List<DownloadedSong> = emptyList()

    override fun find(songId: String): DownloadedSong? = matcher.find(songId)?.let(::download)

    override suspend fun all(): List<DownloadedSong> {
        val now = matcher.all().map(::download)
        if (now != listed) listed = now
        return listed
    }

    override suspend fun respond(call: ApplicationCall, download: DownloadedSong, range: String?) =
        media.respond(call, key(download), range)

    override suspend fun respondArt(call: ApplicationCall, download: DownloadedSong, size: Int) = media.respondArt(call, key(download), size)

    private fun download(match: Pair<LocalTrack, dev.trashpanda.ytmp.protocol.Song>) = DownloadedSong(
        match.second,
        media.uriOf(match.first.key).toString(),
        local = true,
    )

    private fun key(download: DownloadedSong) = matcher.find(download.song.id)?.first?.key ?: throw SourceException("That song isn't on this phone any more")
}

/** [first]'s downloads, then [second]'s songs for the ones it doesn't have. */
class CombinedDownloads(private val first: DownloadedSongs, private val second: DownloadedSongs) : DownloadedSongs {
    private var from: Pair<List<DownloadedSong>, List<DownloadedSong>>? = null
    private var merged: List<DownloadedSong> = emptyList()

    override fun find(songId: String) = first.find(songId) ?: second.find(songId)

    /** The same list until one of them changed (the cache compares it by identity). */
    override suspend fun all(): List<DownloadedSong> {
        val a = first.all()
        val b = second.all()
        val last = from
        if (last != null && last.first === a && last.second == b) return merged
        from = a to b
        val ids = a.mapTo(HashSet()) { it.song.id }
        merged = a + b.filter { it.song.id !in ids }
        return merged
    }

    override suspend fun respond(call: ApplicationCall, download: DownloadedSong, range: String?) =
        (if (download.local) second else first).respond(call, download, range)

    override suspend fun respondArt(call: ApplicationCall, download: DownloadedSong, size: Int) =
        (if (download.local) second else first).respondArt(call, download, size)
}
