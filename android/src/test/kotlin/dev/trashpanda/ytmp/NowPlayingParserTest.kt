package dev.trashpanda.ytmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NowPlayingParserTest {
    @Test
    fun `reads title and artist`() {
        assertEquals(DetectedSong("Blinding Lights", "The Weeknd", 5L), NowPlayingParser.parse("Blinding Lights by The Weeknd", 5L))
    }

    @Test
    fun `the last by splits when the title has one`() {
        assertEquals(DetectedSong("Stand by Me", "Ben E. King", 1L), NowPlayingParser.parse("Stand by Me by Ben E. King", 1L))
    }

    @Test
    fun `trims spaces`() {
        assertEquals(DetectedSong("Song", "Artist", 1L), NowPlayingParser.parse("  Song by Artist  ", 1L))
    }

    @Test
    fun `reads a real Now Playing notification`() {
        assertEquals(DetectedSong("Together We Rise", "Wind Rose", 9L), NowPlayingParser.parse("Together We Rise by Wind Rose", 9L))
    }

    @Test
    fun `the history syncing notification is no song`() {
        assertNull(NowPlayingParser.parse("Your music history is syncing…", 1L))
    }

    @Test
    fun `other notifications are no songs`() {
        assertNull(NowPlayingParser.parse(null, 1L))
        assertNull(NowPlayingParser.parse("", 1L))
        assertNull(NowPlayingParser.parse("Now Playing is on", 1L))
        assertNull(NowPlayingParser.parse(" by Artist", 1L))
        assertNull(NowPlayingParser.parse("Song by ", 1L))
    }
}
