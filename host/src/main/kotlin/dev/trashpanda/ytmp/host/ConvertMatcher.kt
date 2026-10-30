package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.SongMatch
import dev.trashpanda.ytmp.core.SongSearch
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ConvertItem
import dev.trashpanda.ytmp.protocol.ConvertItemState
import dev.trashpanda.ytmp.protocol.ConvertItemType
import dev.trashpanda.ytmp.protocol.PodcastRef
import dev.trashpanda.ytmp.protocol.SearchItem
import dev.trashpanda.ytmp.protocol.SearchPage
import dev.trashpanda.ytmp.protocol.SearchType
import dev.trashpanda.ytmp.protocol.Song
import kotlin.math.abs

/** What an item was matched to: its state, the best song (also when unsure), the others, and how sure. */
data class ConvertMatch(val state: ConvertItemState, val song: Song?, val candidates: List<Song>, val score: Double?)

/**
 * Finds an imported item on YouTube Music (docs/implementation/playlist-converter.md#matching):
 * a search for "artist title" (episodes: "show title", shows: their name), each result scored on
 * title, artist, album, length and version ([score]). The match memory and writing are
 * [PlaylistConverter]'s.
 */
class ConvertMatcher(private val search: SongSearch) {
    /** Looks [item] up: one search, a second for music videos when no song fits and [videos] allows. */
    suspend fun match(item: ConvertItem, videos: Boolean): ConvertMatch {
        val scored = when (item.type) {
            ConvertItemType.SONG, ConvertItemType.LOCAL_FILE -> {
                val query = listOfNotNull(item.artists.firstOrNull(), cleanTitle(item.title)).joinToString(" ")
                val songs = results(search.search(query, SearchType.SONGS)).map { (song, video) -> song to score(item, song, video) }
                if (videos && (songs.maxOfOrNull { it.second } ?: 0.0) < MATCH) {
                    songs + results(search.search(query, SearchType.VIDEOS)).map { (song, _) -> song to score(item, song, video = true) }
                } else {
                    songs
                }
            }
            ConvertItemType.EPISODE -> {
                val query = listOfNotNull(item.show, item.title).joinToString(" ")
                results(search.search(query, SearchType.EPISODES)).map { (song, _) -> song to episodeScore(item, song) }
            }
            ConvertItemType.SHOW -> shows(search.search(item.title, SearchType.PODCASTS)).map { it to showScore(item, it) }
        }
        return decide(scored)
    }

    companion object {
        /** At least this sure: matched. */
        const val MATCH = 0.85

        /** At least this sure: unsure, for you to check. Below: not found. */
        const val UNSURE = 0.5

        private const val CANDIDATES = 5

        fun decide(scored: List<Pair<Song, Double>>): ConvertMatch {
            val best = scored.groupBy { it.first.id }.map { (_, same) -> same.maxBy { it.second } }.sortedByDescending { it.second }
            val top = best.firstOrNull() ?: return ConvertMatch(ConvertItemState.NOT_FOUND, null, emptyList(), null)
            val score = round(top.second)
            val others = best.drop(1).take(CANDIDATES - 1).map { it.first }
            return when {
                score >= MATCH -> ConvertMatch(ConvertItemState.MATCHED, top.first, others, score)
                score >= UNSURE -> ConvertMatch(ConvertItemState.UNSURE, top.first, others, score)
                else -> ConvertMatch(ConvertItemState.NOT_FOUND, null, best.take(CANDIDATES).map { it.first }, score)
            }
        }

        private fun round(score: Double) = Math.round(score * 100) / 100.0

        /** The songs (and videos) in a search page, with whether each is a video. */
        private fun results(page: SearchPage): List<Pair<Song, Boolean>> = page.sections.flatMap { it.items }
            .mapNotNull { (it as? SearchItem.SongResult)?.let { r -> r.song to r.video } }
            .filter { it.first.id.startsWith("ytm:") }

        /** Podcasts in a search page, as songs (a podcast's ID and name, its author as the artist) to keep them like the rest. */
        private fun shows(page: SearchPage): List<Song> = page.sections.flatMap { it.items }.mapNotNull { it as? SearchItem.PodcastResult }.map { p ->
            Song(p.id, p.title, listOfNotNull(p.author).map { ArtistRef(null, it) }, null, 0, p.thumbnails, podcast = PodcastRef(p.id, p.title))
        }

        /** "Halo - Remastered 2008", "Song - From \"Film\"": Spotify's " - …" version notes left out. */
        fun cleanTitle(title: String): String = title.replace(DASH_NOTE, "").trim()

        /** How sure we are [song] is [item], 0..1. */
        fun score(item: ConvertItem, song: Song, video: Boolean): Double {
            val title = similarity(SongMatch.normalize(cleanTitle(item.title)), SongMatch.normalize(song.title))
            val artist = artistSimilarity(item.artists, song)
            val album = when {
                item.album == null || song.album == null -> 0.5
                similarity(SongMatch.normalize(item.album!!), SongMatch.normalize(song.album!!.name)) >= 0.9 -> 1.0
                else -> 0.3
            }
            var score = 0.55 * title + 0.35 * artist + 0.10 * album
            val wanted = item.durationMs
            if (wanted != null && song.durationMs > 0) {
                val off = abs(wanted - song.durationMs)
                if (off <= 3_000) score += 0.05 else if (off > 20_000) score -= 0.25
            }
            if (versions(item.title) != versions(song.title)) score -= 0.3
            if (video) score -= 0.05
            if (song.podcast != null) score -= 0.5
            return score.coerceIn(0.0, 1.0)
        }

        fun episodeScore(item: ConvertItem, song: Song): Double {
            if (song.podcast == null) return 0.0
            val title = similarity(SongMatch.normalize(item.title), SongMatch.normalize(song.title))
            val show = item.show?.let { similarity(SongMatch.normalize(it), SongMatch.normalize(song.podcast!!.name)) } ?: 0.5
            return (0.7 * title + 0.3 * show).coerceIn(0.0, 1.0)
        }

        fun showScore(item: ConvertItem, show: Song): Double {
            val title = similarity(SongMatch.normalize(item.title), SongMatch.normalize(show.title))
            val author = item.artists.firstOrNull()?.let { a -> show.artists.maxOfOrNull { similarity(SongMatch.normalize(a), SongMatch.normalize(it.name)) } }
            return if (author == null) title else (0.8 * title + 0.2 * author).coerceIn(0.0, 1.0)
        }

        private fun artistSimilarity(artists: List<String>, song: Song): Double {
            if (artists.isEmpty()) return 0.5
            val theirs = song.artists.map { SongMatch.normalize(it.name) }.filter { it.isNotEmpty() }
            if (theirs.isEmpty()) return 0.3
            val all = theirs.joinToString(" ")
            return artists.map(SongMatch::normalize).filter { it.isNotEmpty() }.maxOfOrNull { a ->
                // "Rihanna" in "Rihanna & Mikky Ekko", or one of several artists.
                if (Regex("\\b${Regex.escape(a)}\\b").containsMatchIn(all)) 1.0 else theirs.maxOf { similarity(a, it) }
            } ?: 0.5
        }

        /** 1 when the same, 0.85 when one is the other with more words, else how many letter pairs they share. */
        fun similarity(a: String, b: String): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            if (a == b) return 1.0
            if (Regex("\\b${Regex.escape(a)}\\b").containsMatchIn(b) || Regex("\\b${Regex.escape(b)}\\b").containsMatchIn(a)) return 0.85
            val x = pairs(a)
            val y = pairs(b).toMutableList()
            if (x.isEmpty() || y.isEmpty()) return 0.0
            var shared = 0
            for (p in x) if (y.remove(p)) shared++
            return 2.0 * shared / (x.size + y.size + shared)
        }

        private fun pairs(text: String): List<String> = text.split(' ').filter { it.isNotEmpty() }.flatMap { w -> if (w.length == 1) listOf(w) else w.windowed(2) }

        /** The kinds of version a title says it is (live, remix, …): a live version isn't the studio one. */
        private fun versions(title: String): Set<String> {
            val text = title.lowercase()
            return VERSIONS.filterValues { it.containsMatchIn(text) }.keys
        }

        private val DASH_NOTE = Regex("""\s+-\s+(.*\b(remaster(ed)?|version|edit|mix|mono|stereo|from|live|acoustic|deluxe|bonus|single|recorded)\b.*)$""", RegexOption.IGNORE_CASE)

        private val VERSIONS = mapOf(
            "live" to Regex("""\blive\b"""),
            "remix" to Regex("""\bremix\b|\brmx\b"""),
            "acoustic" to Regex("""\bacoustic\b|\bunplugged\b"""),
            "instrumental" to Regex("""\binstrumental\b"""),
            "karaoke" to Regex("""\bkaraoke\b"""),
            "sped" to Regex("""\bsped up\b|\bspeed up\b|\bnightcore\b"""),
            "slowed" to Regex("""\bslowed\b"""),
            "cover" to Regex("""\bcover\b"""),
        )
    }
}
