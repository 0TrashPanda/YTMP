package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.LyricsSource
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.LyricLine
import dev.trashpanda.ytmp.protocol.Lyrics
import dev.trashpanda.ytmp.protocol.PodcastRef
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LyricsLookupTest {
    private val song = Song("ytm:abc", "One More Time", listOf(ArtistRef(null, "Daft Punk")), null, 320_000, emptyList())
    private val timed = Lyrics("One more time", listOf(LyricLine(1_000, "One more time")), "LRCLIB")
    private val plain = Lyrics("One more time", null, "LRCLIB")
    private val own = Lyrics("One more time", listOf(LyricLine(1_200, "One more time")), "YouTube Music (LyricFind)")

    private class Counting(val answer: () -> Lyrics?) : LyricsSource {
        var calls = 0
        override suspend fun lyrics(song: Song): Lyrics? {
            calls++
            return answer()
        }
    }

    @Test
    fun `LRCLIB's timed lyrics come first, and are kept`() = runBlocking {
        val lrclib = Counting { timed }
        val fallback = Counting { own }
        val lookup = LyricsLookup(fallback, lrclib)
        assertEquals(timed, lookup.lyrics(song))
        assertEquals(timed, lookup.lyrics(song))
        assertEquals(1, lrclib.calls)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun `timed lyrics from the source win over LRCLIB's plain ones, and plain ones are used when nobody has them timed`() = runBlocking {
        assertEquals(own, LyricsLookup({ own }, { plain }).lyrics(song))
        assertEquals(plain, LyricsLookup({ own.copy(synced = null, source = "YT") }, { plain }).lyrics(song))
        assertEquals("YT", LyricsLookup({ own.copy(synced = null, source = "YT") }, { null }).lyrics(song)?.source)
    }

    @Test
    fun `none found is remembered, but not when a lookup failed`() = runBlocking {
        val none = Counting { null }
        val lookup = LyricsLookup(null, none)
        assertNull(lookup.lyrics(song))
        assertNull(lookup.lyrics(song))
        assertEquals(1, none.calls)

        val failing = Counting { throw IOException("offline") }
        val again = LyricsLookup(null, failing)
        assertNull(again.lyrics(song))
        assertNull(again.lyrics(song))
        assertEquals(2, failing.calls)
    }

    @Test
    fun `podcast episodes have no lyrics`() = runBlocking {
        assertNull(LyricsLookup({ own }, { timed }).lyrics(song.copy(podcast = PodcastRef("p", "Podcast"))))
    }

    @Test
    fun `LRC is read line by line, also lines with several times`() {
        val lrc = "[ar:Daft Punk]\n[00:01.50] One more time\n[00:03.20][01:10.00]We're gonna celebrate\n[00:05.00]\n"
        assertEquals(
            listOf(LyricLine(1_500, "One more time"), LyricLine(3_200, "We're gonna celebrate"), LyricLine(5_000, ""), LyricLine(70_000, "We're gonna celebrate")),
            Lrclib.parseLrc(lrc),
        )
        assertEquals("One More Time", Lrclib.plainTitle("One More Time (Official Video)"))
        assertEquals("One More Time", Lrclib.plainTitle("One More Time (Radio Edit)"))
        assertEquals("Song (feat. Someone)", Lrclib.plainTitle("Song (feat. Someone) [Remastered 2011]"))
    }
}
