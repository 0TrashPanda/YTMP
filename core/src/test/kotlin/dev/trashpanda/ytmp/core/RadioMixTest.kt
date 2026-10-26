package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.RadioHints
import dev.trashpanda.ytmp.protocol.RadioOptions
import dev.trashpanda.ytmp.protocol.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class RadioMixTest {
    private fun song(id: String, title: String = "Song $id", artist: String = "Artist", durationMs: Long = 200_000) =
        Song(id, title, listOf(ArtistRef(null, artist)), null, durationMs, emptyList())

    @Test
    fun `the same song from two sources has the same key`() {
        assertEquals(SongMatch.key(song("ytm:1", "Café del Mar (Remastered 2019)", "Energy 52")), SongMatch.key(song("local:pi:song:x", "Cafe Del Mar", "energy 52")))
        assertEquals(SongMatch.key(song("ytm:1", "One More Time [Official Video]", "Daft Punk")), SongMatch.key(song("ytm:2", "One more time", "Daft Punk")))
        assertEquals(SongMatch.key(song("ytm:1", "Hello feat. Someone", "Adele")), SongMatch.key(song("ytm:2", "Hello", "Adele")))
        assertNotEquals(SongMatch.key(song("ytm:1", "Hello", "Adele")), SongMatch.key(song("ytm:2", "Hello", "Lionel Richie")))
        assertEquals(true, SongMatch.sameLength(200_000, 203_000))
        assertEquals(false, SongMatch.sameLength(200_000, 260_000))
        assertEquals(true, SongMatch.sameLength(0, 260_000))
    }

    @Test
    fun `one graph song after every two, each song once`() {
        val base = (1..5).map { song("b$it") }
        val graph = listOf(song("g1"), song("b2"), song("x", title = "Song b4"), song("g2"), song("g3"), song("g4"))
        assertEquals(listOf("b1", "b2", "g1", "b3", "b4", "b5", "g2", "g3", "g4"), mixRadio(base, graph).map { it.id })
        assertEquals(listOf("g1", "g2"), mixRadio(emptyList(), listOf(song("g1"), song("g2"))).map { it.id })
        assertEquals(3, mixRadio(base, graph, limit = 3).size)
    }

    @Test
    fun `a radio leaves out what it's told to, a few songs per artist, and puts songs you know first or last`() {
        val songs = listOf(song("1", artist = "A"), song("2", artist = "A"), song("3", artist = "A"), song("4", artist = "B"), song("5", artist = "C"))
        val hints = RadioHints(emptyList(), heard = listOf("1", "4"), avoid = listOf("5"))
        assertEquals(listOf("1", "2", "3", "4"), shape(songs, hints, RadioOptions()).map { it.id })
        assertEquals(listOf("1", "2", "4"), shape(songs, hints, RadioOptions(perArtist = 2)).map { it.id })
        assertEquals(listOf("2", "3"), shape(songs, hints, RadioOptions(discover = 1.0, blockedArtists = listOf("b"))).map { it.id })
        assertEquals(listOf("1", "4"), shape(songs, hints, RadioOptions(discover = -1.0)).map { it.id })
        assertEquals(listOf("2", "1", "3", "4"), shape(songs, hints, RadioOptions(discover = 0.5)).map { it.id })
        assertEquals(listOf("1", "4"), shape(songs, hints, RadioOptions(discover = -1.0, blockedSongs = listOf("2"))).map { it.id })
    }
}
