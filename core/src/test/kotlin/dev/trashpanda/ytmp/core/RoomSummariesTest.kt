package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.PodcastRef
import dev.trashpanda.ytmp.protocol.QueueItem
import dev.trashpanda.ytmp.protocol.Song
import dev.trashpanda.ytmp.protocol.Thumbnail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomSummariesTest {
    private var n = 0

    private fun item(vararg artists: String, cover: String? = "c${n + 1}", podcast: String? = null): QueueItem {
        n++
        val thumbs = cover?.let { listOf(Thumbnail("$it-60", 60, 60), Thumbnail("$it-120", 120, 120), Thumbnail("$it-544", 544, 544)) } ?: emptyList()
        val song = Song("ytm:$n", "Song $n", artists.map { ArtistRef(null, it) }, null, 60_000, thumbs, podcast = podcast?.let { PodcastRef("p", it) })
        return QueueItem("i$n", song, "m", "Anna", 0)
    }

    @Test
    fun `an empty room has nothing to show`() {
        val summary = RoomSummaries.of(emptyList(), null, emptyList(), playing = false)
        assertEquals(emptyList(), summary.covers)
        assertEquals(emptyList(), summary.artists)
        assertNull(summary.song)
        assertFalse(summary.playing)
    }

    @Test
    fun `covers are the last songs, the one playing first, small and all different`() {
        val history = listOf(item("A"), item("B"), item("C", cover = "c2"), item("D"), item("E"))
        val current = item("F")
        val summary = RoomSummaries.of(history, current, listOf(item("G")), playing = true)
        assertEquals(listOf("c6-120", "c5-120", "c4-120", "c2-120"), summary.covers)
        assertEquals("Song 6 · F", summary.song)
        assertTrue(summary.playing)
    }

    @Test
    fun `artists follow the music, from what played and what's next`() {
        val metal = List(20) { item("Metallica") }
        val lofi = listOf(item("Nujabes"), item("J Dilla", "Nujabes"), item("Tomppabeats"))
        val next = listOf(item("Nujabes"), item("J Dilla"))
        val summary = RoomSummaries.of(metal + lofi, null, next, playing = false)
        // The last 15 played still count Metallica 13 times; the room just switched.
        assertEquals(listOf("Metallica", "Nujabes", "J Dilla"), summary.artists)
        assertEquals("Song 23 · Tomppabeats", summary.song)
        assertFalse(summary.playing)

        val later = RoomSummaries.of(metal + lofi + List(15) { item(if (it % 2 == 0) "Nujabes" else "J Dilla") }, null, next, playing = true)
        assertEquals(listOf("Nujabes", "J Dilla", "Tomppabeats"), later.artists)
        assertFalse(later.playing, "Nothing is on: it isn't playing")
    }

    @Test
    fun `on a tie, the artist heard last comes first, and podcasts count by their show`() {
        val summary = RoomSummaries.of(listOf(item("Old"), item("Newer")), item("Host", podcast = "The Show"), emptyList(), playing = true)
        assertEquals(listOf("The Show", "Newer", "Old"), summary.artists)
        assertEquals("Song 3 · The Show", summary.song)
    }
}
