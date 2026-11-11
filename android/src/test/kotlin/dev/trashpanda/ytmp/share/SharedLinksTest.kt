package dev.trashpanda.ytmp.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SharedLinksTest {
    private val song = Shared.Song("dQw4w9WgXcQ", "https://music.youtube.com/watch?v=dQw4w9WgXcQ")

    @Test
    fun `song links`() {
        assertEquals(song, SharedLinks.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(song, SharedLinks.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ&si=AbC123&list=RDAMVM"))
        assertEquals(song, SharedLinks.parse("https://www.youtube.com/watch?feature=share&v=dQw4w9WgXcQ"))
        assertEquals(song, SharedLinks.parse("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(song, SharedLinks.parse("https://youtu.be/dQw4w9WgXcQ?si=xyz"))
        assertEquals(song, SharedLinks.parse("https://youtube.com/shorts/dQw4w9WgXcQ"))
        assertEquals("ytm:dQw4w9WgXcQ", song.songId)
    }

    @Test
    fun `a link in a shared text`() {
        assertEquals(song, SharedLinks.parse("Never Gonna Give You Up\nhttps://music.youtube.com/watch?v=dQw4w9WgXcQ&si=abc"))
        assertEquals(song, SharedLinks.parse("Listen (https://youtu.be/dQw4w9WgXcQ)."))
    }

    @Test
    fun `room links`() {
        assertEquals(Shared.Room("http://192.168.1.5:8765/room/abcd", "ABCD"), SharedLinks.parse("http://192.168.1.5:8765/room/abcd"))
        assertEquals(Shared.Room("https://ytmp.example.com/room/PARTY42/", "PARTY42"), SharedLinks.parse("https://ytmp.example.com/room/PARTY42/"))
    }

    @Test
    fun `not a song or room`() {
        assertNull(SharedLinks.parse(null))
        assertNull(SharedLinks.parse("just some text"))
        assertNull(SharedLinks.parse("https://music.youtube.com/watch?v=short"))
        assertNull(SharedLinks.parse("https://music.youtube.com/playlist?list=PL123"))
        assertNull(SharedLinks.parse("https://example.com/room/a"))
        assertNull(SharedLinks.parse("https://example.com/rooms/ABCD"))
        assertNull(SharedLinks.parse("ftp://music.youtube.com/watch?v=dQw4w9WgXcQ"))
    }
}
