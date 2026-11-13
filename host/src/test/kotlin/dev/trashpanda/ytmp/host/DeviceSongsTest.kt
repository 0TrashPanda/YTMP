package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.StreamResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Songs from the music on a desktop app's computer, sent to the server only when another player needs one. */
class DeviceSongsTest {
    private val song = "local:dev-abcdef12:s:0123456789abcdef"
    private val secret = "s".repeat(40)

    @Test
    fun `a computer's song gets the server's audio path, other songs go on as before`() = runTest {
        val songs = DeviceSongs(Files.createTempDirectory("device-songs").toFile())
        val streams = songs.streams(StreamResolver { "https://youtube/$it" })
        assertEquals("/api/audio/local%3Adev-abcdef12%3As%3A0123456789abcdef", streams.resolveStream(song))
        assertEquals("https://youtube/ytm:abc", streams.resolveStream("ytm:abc"))
        assertEquals("abcdef12", DeviceSongs.deviceOf(song))
        assertNull(DeviceSongs.deviceOf("local:phone:s:12"), "the phone's own music is the phone's")
    }

    @Test
    fun `nothing is asked until a player needs the song, then the computer sends it`() = runBlocking {
        val songs = DeviceSongs(Files.createTempDirectory("device-songs").toFile())
        assertTrue(songs.allowed("abcdef12", secret))
        assertEquals(emptyList(), songs.waitForWanted("abcdef12", waitMs = 100), "listening alone: nothing wanted")

        val player = async { songs.fetch(song, waitMs = 10_000) }
        delay(100)
        assertEquals(listOf(song), songs.waitForWanted("abcdef12", waitMs = 1_000))
        assertTrue(songs.receive(song, "audio/mpeg", "the song".byteInputStream()))
        val file = assertNotNull(player.await())
        assertEquals("the song", file.readText())

        // Once here, it isn't asked again.
        assertNotNull(songs.fetch(song, waitMs = 0))
        assertEquals(emptyList(), songs.waitForWanted("abcdef12", waitMs = 100))
    }

    @Test
    fun `a computer that doesn't send it in time leaves the player without it`() = runTest {
        val songs = DeviceSongs(Files.createTempDirectory("device-songs").toFile())
        assertNull(songs.fetch(song, waitMs = 5_000))
    }

    @Test
    fun `only the computer's own secret works, from its first question on`() {
        val songs = DeviceSongs(Files.createTempDirectory("device-songs").toFile())
        assertTrue(songs.allowed("abcdef12", secret))
        assertTrue(songs.allowed("abcdef12", secret))
        assertFalse(songs.allowed("abcdef12", "t".repeat(40)))
        assertFalse(songs.allowed("other123", "short"))
    }
}
