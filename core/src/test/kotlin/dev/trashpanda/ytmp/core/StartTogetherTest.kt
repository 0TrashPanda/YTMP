package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.ErrorInfo
import dev.trashpanda.ytmp.protocol.MAX_START_WAIT_SECONDS
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.PlaybackStatus
import dev.trashpanda.ytmp.protocol.RoomState
import dev.trashpanda.ytmp.protocol.RoomVisibility
import dev.trashpanda.ytmp.protocol.ServerMessage
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A new song waits for the players to load it, then starts for everyone together (docs/implementation/playback-sync.md). */
@OptIn(ExperimentalCoroutinesApi::class)
class StartTogetherTest {
    private fun song(n: Int) = Song("ytm:$n", "Song $n", listOf(ArtistRef(null, "Artist")), null, 180_000L, emptyList())

    private class Client : Outbox {
        val messages = mutableListOf<ServerMessage>()
        lateinit var welcome: ServerMessage.Welcome
        val results = mutableMapOf<String, ErrorInfo?>()

        override fun send(message: ServerMessage) {
            messages += message
            if (message is ServerMessage.Welcome) welcome = message
            if (message is ServerMessage.Result) results[message.id] = message.error
        }

        val id get() = welcome.participantId
    }

    private fun TestScope.newRoom() = Room("ABCD", "Party", "owner-token", RoomVisibility.PUBLIC, { "https://s/$it" }, backgroundScope) { testScheduler.currentTime }

    private suspend fun Room.connect(name: String, owner: Boolean = false): Client {
        val c = Client()
        join(ClientMessage.Hello(PROTOCOL_VERSION, "ABCD", name, null, if (owner) "owner-token" else null), c)
        return c
    }

    private var n = 0

    private suspend fun Room.run(c: Client, command: Command): ErrorInfo? {
        val id = "c${n++}"
        handle(c.id, ClientMessage.CommandMessage(id, command))
        return c.results.getValue(id)
    }

    private suspend fun Room.state(c: Client): RoomState {
        handle(c.id, ClientMessage.RequestSnapshot)
        return (c.messages.last() as ServerMessage.Snapshot).state
    }

    private suspend fun Room.playback(c: Client): PlaybackStatus = state(c).playback

    private suspend fun Room.currentItem(c: Client): String = state(c).nowPlaying!!.item.itemId

    @Test
    fun `with nobody to wait for, a song starts at once`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        // An older player that doesn't say when it's ready.
        room.run(owner, Command.SetListening(true))
        room.run(owner, Command.PlayNow(song(1)))
        runCurrent()
        val playback = room.playback(owner)
        assertTrue(playback.playing)
        assertFalse(playback.starting)
    }

    @Test
    fun `the only player stalling mid-song makes the room wait there until it has data again`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        room.run(owner, Command.SetListening(true, startsTogether = true))
        room.run(owner, Command.PlayNow(song(1)))
        runCurrent()
        val item = room.currentItem(owner)
        assertNull(room.run(owner, Command.PlayerReady(item)))
        advanceTimeBy(10_000)

        // It ran dry at 9.5 s: the room waits there, shown as playing and loading.
        assertNull(room.run(owner, Command.PlayerStalled(item, 9_500)))
        var playback = room.playback(owner)
        assertTrue(playback.starting)
        assertFalse(playback.playing)
        assertEquals(9_500, playback.positionMs)
        advanceTimeBy(8_000)
        assertEquals(9_500, room.playback(owner).positionMs, "the clock stands still while it loads")

        // Data again: it goes on from the same spot.
        assertNull(room.run(owner, Command.PlayerReady(item)))
        playback = room.playback(owner)
        assertFalse(playback.starting)
        assertTrue(playback.playing)
        advanceTimeBy(2_000)
        assertEquals(11_500, room.playback(owner).let { it.positionMs + (testScheduler.currentTime - it.hostTimeMs) })
    }

    @Test
    fun `a stall with other players listening changes nothing, and a stall that never ends is waited for only so long`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        val guest = room.connect("Guest")
        room.run(owner, Command.SetListening(true, startsTogether = true))
        room.run(owner, Command.PlayNow(song(1)))
        runCurrent()
        val item = room.currentItem(owner)
        room.run(owner, Command.PlayerReady(item))
        room.run(guest, Command.SetListening(true, startsTogether = true))
        advanceTimeBy(5_000)
        room.run(guest, Command.PlayerStalled(item, 4_000))
        assertTrue(room.playback(owner).playing, "the others keep playing")

        // Only the owner left: now a stall of theirs is waited for, up to 30 s.
        room.run(guest, Command.SetListening(false))
        room.run(owner, Command.PlayerStalled(item, 5_000))
        assertTrue(room.playback(owner).starting)
        advanceTimeBy(Room.STALL_WAIT_MS + 500)
        val playback = room.playback(owner)
        assertTrue(playback.playing)
        assertTrue(playback.positionMs in 5_000..5_600, "it goes on from where it stood: ${playback.positionMs}")
    }

    @Test
    fun `a new song waits for the players, then starts for everyone from the start`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        val guest = room.connect("Guest")
        room.run(owner, Command.SetListening(true, startsTogether = true))
        room.run(guest, Command.SetListening(true, startsTogether = true))
        room.run(owner, Command.PlayNow(song(1)))
        runCurrent()
        val item = room.currentItem(owner)

        var playback = room.playback(owner)
        assertTrue(playback.starting)
        assertFalse(playback.playing)
        advanceTimeBy(1_000)
        // Ready for another song: doesn't count.
        assertNull(room.run(owner, Command.PlayerReady("other")))
        assertNull(room.run(owner, Command.PlayerReady(item)))
        playback = room.playback(owner)
        assertTrue(playback.starting)
        assertEquals(0, playback.positionMs)

        assertNull(room.run(guest, Command.PlayerReady(item)))
        playback = room.playback(owner)
        assertFalse(playback.starting)
        assertTrue(playback.playing)
        assertEquals(0, playback.positionMs)
        assertEquals(testScheduler.currentTime, playback.hostTimeMs)
    }

    @Test
    fun `a player that doesn't load the song in time doesn't hold up the others for long`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        room.run(owner, Command.SetListening(true, startsTogether = true))
        room.run(owner, Command.PlayNow(song(1)))
        runCurrent()
        // 2 s by default.
        advanceTimeBy(1_900)
        assertTrue(room.playback(owner).starting)
        advanceTimeBy(200)
        val playback = room.playback(owner)
        assertTrue(playback.playing)
        // It started at the deadline, from the start.
        assertTrue(playback.positionMs in 0..200)
    }

    @Test
    fun `how long a new song waits is a room setting, and 0 doesn't wait`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        room.run(owner, Command.SetListening(true, startsTogether = true))
        assertEquals(2, room.state(owner).settings.startWaitSeconds)
        assertNotNull(room.run(owner, Command.UpdateSettings(startWaitSeconds = MAX_START_WAIT_SECONDS + 1)))

        assertNull(room.run(owner, Command.UpdateSettings(startWaitSeconds = 5)))
        room.run(owner, Command.PlayNow(song(1)))
        runCurrent()
        advanceTimeBy(4_500)
        assertTrue(room.playback(owner).starting)
        advanceTimeBy(1_000)
        assertTrue(room.playback(owner).playing)

        assertNull(room.run(owner, Command.UpdateSettings(startWaitSeconds = 0)))
        room.run(owner, Command.PlayNow(song(2)))
        runCurrent()
        assertTrue(room.playback(owner).playing)
    }

    @Test
    fun `a player that stops listening while the song waits isn't waited for`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        val guest = room.connect("Guest")
        room.run(owner, Command.SetListening(true, startsTogether = true))
        room.run(guest, Command.SetListening(true, startsTogether = true))
        room.run(owner, Command.PlayNow(song(1)))
        runCurrent()
        room.run(owner, Command.PlayerReady(room.currentItem(owner)))
        room.run(guest, Command.SetListening(false))
        advanceTimeBy(1_000)
        assertTrue(room.playback(owner).playing)
    }

    @Test
    fun `speakers near a phone are waited for on their own`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        room.run(owner, Command.SetSpeakers(listOf("Kitchen"), startsTogether = true))
        room.run(owner, Command.PlayNow(song(1)))
        runCurrent()
        val item = room.currentItem(owner)
        // The phone's own player isn't playing: its ready doesn't count for the speakers.
        room.run(owner, Command.PlayerReady(item))
        assertTrue(room.playback(owner).starting)
        room.run(owner, Command.PlayerReady(item, speakers = true))
        assertTrue(room.playback(owner).playing)
    }

    @Test
    fun `pausing or seeking while the song waits ends the wait`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        room.run(owner, Command.SetListening(true, startsTogether = true))
        room.run(owner, Command.PlayNow(song(1)))
        runCurrent()
        room.run(owner, Command.Pause)
        var playback = room.playback(owner)
        assertFalse(playback.starting)
        assertFalse(playback.playing)
        assertEquals(0, playback.positionMs)

        room.run(owner, Command.PlayNow(song(2)))
        runCurrent()
        assertTrue(room.playback(owner).starting)
        room.run(owner, Command.Seek(30_000))
        playback = room.playback(owner)
        assertFalse(playback.starting)
        assertTrue(playback.playing)
        assertEquals(30_000, playback.positionMs)
    }

    @Test
    fun `the song doesn't end while it waits`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        room.run(owner, Command.SetListening(true, startsTogether = true))
        room.run(owner, Command.PlayNow(song(1)))
        runCurrent()
        val item = room.currentItem(owner)
        advanceTimeBy(1_500)
        room.run(owner, Command.PlayerReady(item))
        // Ends 180 s after it started, not after it was resolved.
        advanceTimeBy(179_000)
        assertEquals(item, room.currentItem(owner))
        advanceTimeBy(2_000)
        assertEquals(null, room.state(owner).nowPlaying)
    }
}
