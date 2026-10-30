package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.SongSearch
import dev.trashpanda.ytmp.protocol.AlbumRef
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ConvertItem
import dev.trashpanda.ytmp.protocol.ConvertItemState
import dev.trashpanda.ytmp.protocol.ConvertItemType
import dev.trashpanda.ytmp.protocol.PodcastRef
import dev.trashpanda.ytmp.protocol.SearchItem
import dev.trashpanda.ytmp.protocol.SearchPage
import dev.trashpanda.ytmp.protocol.SearchSection
import dev.trashpanda.ytmp.protocol.SearchType
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConvertMatcherTest {
    private fun song(id: String, title: String, vararg artists: String, album: String? = null, ms: Long = 200_000) =
        Song("ytm:$id", title, artists.map { ArtistRef(null, it) }, album?.let { AlbumRef(null, it) }, ms, emptyList())

    private fun item(title: String, artist: String?, album: String? = null, ms: Long? = null) =
        ConvertItem(ConvertItemType.SONG, title, listOfNotNull(artist), album, ms)

    private fun score(item: ConvertItem, song: Song, video: Boolean = false) = ConvertMatcher.score(item, song, video)

    @Test
    fun `the same song is sure, also with a remaster note, accents or another album`() {
        assertTrue(score(item("Halo", "Beyoncé", "I Am... Sasha Fierce"), song("a", "Halo", "Beyonce", album = "I Am...Sasha Fierce")) >= ConvertMatcher.MATCH)
        assertTrue(score(item("Wish You Were Here - 2011 Remaster", "Pink Floyd"), song("a", "Wish You Were Here", "Pink Floyd", album = "Wish You Were Here")) >= ConvertMatcher.MATCH)
        assertTrue(score(item("Stay", "Rihanna", "Unapologetic"), song("a", "Stay", "Rihanna", "Mikky Ekko", album = "Now 85")) >= ConvertMatcher.MATCH)
        assertTrue(score(item("Hello", "Adele"), song("a", "Hello (feat. Nobody)", "Adele")) >= ConvertMatcher.MATCH)
    }

    @Test
    fun `another artist, version or length is not sure`() {
        // A cover by someone else.
        assertTrue(score(item("Halo", "Beyoncé"), song("a", "Halo", "Texas")) < ConvertMatcher.MATCH)
        // Live, remixed, sped up.
        assertTrue(score(item("Halo", "Beyoncé"), song("a", "Halo (Live)", "Beyoncé")) < ConvertMatcher.MATCH)
        assertTrue(score(item("Halo", "Beyoncé"), song("a", "Halo - Remix", "Beyoncé")) < ConvertMatcher.MATCH)
        assertTrue(score(item("Halo - Live", "Beyoncé"), song("a", "Halo (Live)", "Beyoncé")) >= ConvertMatcher.MATCH)
        // Far off in length (a local file says).
        assertTrue(score(item("Halo", "Beyoncé", ms = 261_000), song("a", "Halo", "Beyoncé", ms = 400_000)) < ConvertMatcher.MATCH)
        // Something else altogether.
        assertTrue(score(item("Halo", "Beyoncé"), song("a", "Crazy in Love", "Beyoncé")) < ConvertMatcher.UNSURE)
    }

    @Test
    fun `a video counts a bit less than the song`() {
        val i = item("Halo", "Beyoncé")
        assertTrue(score(i, song("a", "Halo", "Beyoncé"), video = true) < score(i, song("a", "Halo", "Beyoncé")))
    }

    @Test
    fun `episodes and shows`() {
        val episode = ConvertItem(ConvertItemType.EPISODE, "Monday", listOf("The Daily"), show = "The Daily")
        val match = song("e", "Monday", "The Daily").copy(podcast = PodcastRef("p", "The Daily"))
        assertTrue(ConvertMatcher.episodeScore(episode, match) >= ConvertMatcher.MATCH)
        assertEquals(0.0, ConvertMatcher.episodeScore(episode, song("s", "Monday", "The Mamas")))
        val show = ConvertItem(ConvertItemType.SHOW, "The Daily", listOf("The New York Times"))
        assertTrue(ConvertMatcher.showScore(show, song("p", "The Daily", "The New York Times")) >= ConvertMatcher.MATCH)
    }

    private class FakeSearch(val pages: Map<SearchType, List<SearchItem>>) : SongSearch {
        val asked = mutableListOf<Pair<String, SearchType>>()

        override suspend fun search(query: String, type: SearchType): SearchPage {
            asked += query to type
            return SearchPage(listOf(SearchSection("x", type, pages[type].orEmpty())))
        }
    }

    @Test
    fun `searches artist and title, songs first, then videos when no song fits`() = runBlocking {
        val search = FakeSearch(
            mapOf(
                SearchType.SONGS to listOf(SearchItem.SongResult(song("c", "Halo", "Texas")), SearchItem.SongResult(song("x", "Halo", "Beyoncé", "Someone"))),
                SearchType.VIDEOS to listOf(SearchItem.SongResult(song("v", "Halo", "Beyoncé"), video = true)),
            ),
        )
        val sure = ConvertMatcher(search).match(item("Halo - Remastered", "Beyoncé"), videos = true)
        assertEquals(listOf("Beyoncé Halo" to SearchType.SONGS), search.asked)
        assertEquals(ConvertItemState.MATCHED, sure.state)
        assertEquals("ytm:x", sure.song?.id)
        assertEquals(listOf("ytm:c"), sure.candidates.map { it.id })

        val onlyVideo = FakeSearch(mapOf(SearchType.VIDEOS to listOf(SearchItem.SongResult(song("v", "Halo", "Beyoncé"), video = true))))
        val video = ConvertMatcher(onlyVideo).match(item("Halo", "Beyoncé"), videos = true)
        assertEquals(listOf(SearchType.SONGS, SearchType.VIDEOS), onlyVideo.asked.map { it.second })
        assertEquals("ytm:v", video.song?.id)
        assertEquals(ConvertItemState.MATCHED, video.state)

        val none = ConvertMatcher(FakeSearch(emptyMap())).match(item("Halo", "Beyoncé"), videos = false)
        assertEquals(ConvertItemState.NOT_FOUND, none.state)
        assertNull(none.song)
    }

    @Test
    fun `just below sure is unsure, with the best guess`() {
        val decided = ConvertMatcher.decide(listOf(song("a", "A", "B") to 0.7, song("b", "A", "C") to 0.6))
        assertEquals(ConvertItemState.UNSURE, decided.state)
        assertEquals("ytm:a", decided.song?.id)
        assertEquals(listOf("ytm:b"), decided.candidates.map { it.id })
    }
}
