package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.ConvertItemType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SpotifyLinkTest {
    /** Spotify's embed page as far as YTMP reads it. */
    private fun page(name: String, tracks: String) =
        """<html><head></head><body><div id="__next"></div><script id="__NEXT_DATA__" type="application/json">""" +
            """{"props":{"pageProps":{"state":{"data":{"entity":{"type":"playlist","name":"$name","subtitle":"J von A","trackList":[$tracks]}}}}}}""" +
            """</script></body></html>"""

    private val tracks = """
        {"uri":"spotify:track:5aK1kRbYlDHjrXCzBx1Ej7","title":"Kurogane","subtitle":"Yasuharu Takanashi","duration":140653,"entityType":"track"},
        {"uri":"spotify:track:x","title":"Stay","subtitle":"Rihanna, Mikky Ekko","duration":240000,"entityType":"track"},
        {"uri":"spotify:episode:e","title":"Monday","subtitle":"The Daily","duration":1800000,"entityType":"episode"},
        {"uri":"spotify:audiobook:b","title":"A book","subtitle":"Someone","entityType":"audiobook"}
    """.trimIndent()

    @Test
    fun `links and uris of playlists and albums`() {
        assertEquals("playlist" to "2tutdwBqDZelbXJ42umgbj", SpotifyLink.parse("https://open.spotify.com/playlist/2tutdwBqDZelbXJ42umgbj?si=NIpAFpyUR0KGZ60WABZxBw&utm_source=copy-link&pi=XD-G6m_vS8-vf"))
        assertEquals("album" to "2noRn2Aes5aoNVsU6iWThc", SpotifyLink.parse("https://open.spotify.com/intl-nl/album/2noRn2Aes5aoNVsU6iWThc"))
        assertEquals("playlist" to "37i9dQZF1DXcBWIGoYBM5M", SpotifyLink.parse("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(SpotifyLink.parse("https://open.spotify.com/track/5aK1kRbYlDHjrXCzBx1Ej7"))
        assertNull(SpotifyLink.parse("https://music.youtube.com/playlist?list=PL123"))
    }

    @Test
    fun `reads the songs and episodes from the embed page`() {
        var asked: String? = null
        val read = SpotifyLink.read("https://open.spotify.com/playlist/2tutdwBqDZelbXJ42umgbj?si=x") { url -> asked = url; page("G - Fairy Tail Battle", tracks) }
        assertEquals("https://open.spotify.com/embed/playlist/2tutdwBqDZelbXJ42umgbj", asked)
        val list = read.lists.single()
        assertEquals("G - Fairy Tail Battle", list.name)
        assertEquals(3, list.items.size)
        assertEquals(1, read.skipped)
        assertEquals(false, list.truncated)
        val kurogane = list.items[0]
        assertEquals(listOf("Yasuharu Takanashi"), kurogane.artists)
        assertEquals(140_653L, kurogane.durationMs)
        assertEquals("spotify:track:5aK1kRbYlDHjrXCzBx1Ej7", kurogane.sourceId)
        assertEquals(listOf("Rihanna", "Mikky Ekko"), list.items[1].artists)
        assertEquals(ConvertItemType.EPISODE, list.items[2].type)
        assertEquals("The Daily", list.items[2].show)
    }

    @Test
    fun `a full page says there may be more, an album's songs know their album`() {
        val many = (1..100).joinToString(",") { """{"uri":"spotify:track:$it","title":"Song $it","subtitle":"A","duration":1000,"entityType":"track"}""" }
        assertEquals(true, SpotifyLink.parsePage(page("Long", many), "playlist").lists.single().truncated)
        assertEquals("Discovery", SpotifyLink.parsePage(page("Discovery", tracks), "album").lists.single().items.first().album)
    }

    @Test
    fun `not a link, not public, or a page YTMP can't read`() {
        assertFailsWith<ConvertReadException> { SpotifyLink.read("hello") { error("not asked") } }
        assertFailsWith<ConvertReadException> { SpotifyLink.read("https://open.spotify.com/playlist/2tutdwBqDZelbXJ42umgbj") { "<html>nothing</html>" } }
        assertFailsWith<ConvertReadException> { SpotifyLink.read("https://open.spotify.com/playlist/2tutdwBqDZelbXJ42umgbj") { throw java.io.IOException("offline") } }
    }
}
