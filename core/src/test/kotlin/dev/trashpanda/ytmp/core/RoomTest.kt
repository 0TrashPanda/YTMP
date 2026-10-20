package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.apply
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.Permission
import dev.trashpanda.ytmp.protocol.ItemList
import dev.trashpanda.ytmp.protocol.RepeatMode
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.Event
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.QueueItemResult
import dev.trashpanda.ytmp.protocol.QueuePosition
import dev.trashpanda.ytmp.protocol.RejectReason
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RoomTest {
    private fun song(n: Int, durationMs: Long = 60_000) =
        Song("ytm:song$n", "Song $n", listOf(ArtistRef(null, "Artist")), null, durationMs, emptyList())

    /** A client that rebuilds the room state from Welcome + events, like the frontend does. */
    private class FakeClient : Outbox {
        val messages = mutableListOf<ServerMessage>()
        lateinit var state: RoomState
        var participantId = ""

        override fun send(message: ServerMessage) {
            messages += message
            when (message) {
                is ServerMessage.Welcome -> { state = message.state; participantId = message.participantId }
                is ServerMessage.EventMessage -> state = state.apply(message.event)
                else -> Unit
            }
        }

        val events get() = messages.filterIsInstance<ServerMessage.EventMessage>().map { it.event }
    }

    private fun TestScope.newRoom(): Room =
        Room("ABCD", "Test", "owner-token", RoomVisibility.PUBLIC, { id -> "https://stream/$id" }, backgroundScope) { testScheduler.currentTime }

    /** Joins as the owner, who may do everything (permissions are tested in RolesTest). */
    private suspend fun Room.connect(name: String = "Anna"): FakeClient {
        val client = FakeClient()
        join(ClientMessage.Hello(PROTOCOL_VERSION, "ABCD", name, null, "owner-token"), client)
        return client
    }

    private suspend fun Room.run(client: FakeClient, command: Command) =
        handle(client.participantId, ClientMessage.CommandMessage("c", command))

    @Test
    fun `a client that follows the events has the room the host has`() = runTest {
        val room = newRoom()
        val anna = room.connect()
        val guest = FakeClient()
        room.join(ClientMessage.Hello(PROTOCOL_VERSION, "ABCD", "Carl", null, null), guest)
        val songs = (1..6).map { song(it) }
        room.run(anna, Command.AddSongs(songs.take(4), QueuePosition.END))
        room.run(guest, Command.AddSongs(songs.drop(4), QueuePosition.NEXT))
        runCurrent()
        room.run(anna, Command.MoveQueueItem(anna.state.queue.last().itemId, 0))
        room.run(anna, Command.RemoveQueueItem(anna.state.queue[1].itemId))
        room.run(anna, Command.ShuffleQueue)
        room.run(anna, Command.SetRepeat(RepeatMode.QUEUE))
        room.run(anna, Command.Skip())
        room.run(anna, Command.Skip())
        runCurrent()
        room.run(anna, Command.JumpTo(anna.state.history.first().itemId))
        room.run(anna, Command.MoveItem(anna.state.queue.first().itemId, ItemList.HISTORY, 0))
        room.run(anna, Command.UpdateSettings(name = "Party", fairOrder = true, voteSkip = true, maxSongsPerPerson = 10))
        room.run(anna, Command.CreateRole("Bouncer", "#ff0000", listOf(Permission.KICK, Permission.ADD_SONGS)))
        room.run(anna, Command.AssignRole(guest.participantId, anna.state.roles.last().id))
        room.run(guest, Command.VoteSkip(true))
        room.run(anna, Command.AutoplaySongs(listOf(song(7), song(8))))
        room.run(anna, Command.SetPassword("secret"))
        room.run(anna, Command.Pause)
        room.run(anna, Command.Kick(guest.participantId))
        room.run(anna, Command.ClearQueue)
        runCurrent()

        room.handle(anna.participantId, ClientMessage.RequestSnapshot)
        val host = (anna.messages.last() as ServerMessage.Snapshot).state
        assertEquals(host, anna.state.copy(playback = host.playback))
        // The room did change: this isn't two empty rooms.
        assertEquals("Party", anna.state.room.name)
        assertTrue(anna.state.roles.any { it.name == "Bouncer" } && anna.state.history.isNotEmpty() && anna.state.room.hasPassword)
    }

    @Test
    fun `the app's player connection keeps a participant here while its page is frozen`() = runTest {
        val room = newRoom()
        val page = room.connect()
        val other = room.connect("Ben")
        val me = page.participantId
        room.handle(me, ClientMessage.CommandMessage("c", Command.SetListening(true)))
        val token = (page.messages.first() as ServerMessage.Welcome).guestToken
        val player = FakeClient()
        room.attach(ClientMessage.Attach(PROTOCOL_VERSION, "ABCD", token), player)

        // The page's connection times out (Android froze it): the player still plays.
        room.disconnect(me, page)
        advanceTimeBy(20 * 60_000L)
        assertFalse(room.cleanup(15 * 60_000L))
        var anna = other.state.participants.single { it.id == me }
        assertTrue(anna.online && anna.listening)

        // The player's connection goes too: now really gone.
        room.disconnect(me, player)
        anna = other.state.participants.single { it.id == me }
        assertFalse(anna.online || anna.listening)
    }

    @Test
    fun `speakers near someone's phone keep the music going, until they go offline`() = runTest {
        val room = newRoom()
        val page = room.connect()
        val other = room.connect("Ben")
        room.run(page, Command.AddSongs(listOf(song(1)), QueuePosition.END))
        room.run(page, Command.SetListening(true))
        room.run(page, Command.SetSpeakers(listOf("Woonkamer", " Woonkamer ", "")))
        room.run(page, Command.SetListening(false))
        runCurrent()
        assertTrue(other.state.playback.playing)
        assertEquals(listOf("Woonkamer"), other.state.participants.single { it.id == page.participantId }.speakers)

        // The phone goes: its speakers can't play the room any more.
        room.disconnect(page.participantId, page)
        assertEquals(emptyList(), other.state.participants.single { it.id == page.participantId }.speakers)
        advanceTimeBy(5 * 60_000L)
        runCurrent()
        assertFalse(other.state.playback.playing)
    }

    @Test
    fun `adding to an empty room starts playing`() = runTest {
        val room = newRoom()
        val client = room.connect()

        room.run(client, Command.AddSongs(listOf(song(1), song(2)), QueuePosition.END))
        runCurrent()

        assertEquals("ytm:song1", client.state.nowPlaying?.item?.song?.id)
        assertEquals("https://stream/ytm:song1", client.state.nowPlaying?.streamUrl)
        assertTrue(client.state.playback.playing)
        assertEquals(listOf("ytm:song2"), client.state.queue.map { it.song.id })
    }

    @Test
    fun `songs advance when they end`() = runTest {
        val room = newRoom()
        val client = room.connect()
        room.run(client, Command.AddSongs(listOf(song(1), song(2)), QueuePosition.END))
        runCurrent()

        advanceTimeBy(60_001)
        runCurrent()

        assertEquals("ytm:song2", client.state.nowPlaying?.item?.song?.id)
        assertEquals(listOf(QueueItemResult.PLAYED), client.state.history.map { it.result })
    }

    @Test
    fun `play next puts songs at the front`() = runTest {
        val room = newRoom()
        val client = room.connect()
        room.run(client, Command.AddSongs(listOf(song(1), song(2), song(3)), QueuePosition.END))
        room.run(client, Command.AddSongs(listOf(song(4)), QueuePosition.NEXT))
        runCurrent()

        assertEquals(listOf("ytm:song4", "ytm:song2", "ytm:song3"), client.state.queue.map { it.song.id })
    }

    @Test
    fun `jumping ahead skips the songs in between`() = runTest {
        val room = newRoom()
        val client = room.connect()
        room.run(client, Command.AddSongs(listOf(song(1), song(2), song(3), song(4)), QueuePosition.END))
        runCurrent()

        val target = client.state.queue.first { it.song.id == "ytm:song4" }
        room.run(client, Command.JumpTo(target.itemId))
        runCurrent()

        assertEquals("ytm:song4", client.state.nowPlaying?.item?.song?.id)
        assertEquals(listOf("ytm:song1", "ytm:song2", "ytm:song3"), client.state.history.map { it.song.id })
        assertTrue(client.state.history.all { it.result == QueueItemResult.SKIPPED })
        assertTrue(client.state.queue.isEmpty())
    }

    @Test
    fun `jumping back makes later songs upcoming again`() = runTest {
        val room = newRoom()
        val client = room.connect()
        room.run(client, Command.AddSongs(listOf(song(1), song(2), song(3)), QueuePosition.END))
        runCurrent()
        room.run(client, Command.Skip())
        runCurrent()
        room.run(client, Command.Skip())
        runCurrent()
        // Now: history [1, 2], playing 3.

        room.run(client, Command.JumpTo(client.state.history.first().itemId))
        runCurrent()

        assertEquals("ytm:song1", client.state.nowPlaying?.item?.song?.id)
        assertEquals(listOf("ytm:song2", "ytm:song3"), client.state.queue.map { it.song.id })
        assertTrue(client.state.history.isEmpty())
    }

    @Test
    fun `next while paused stays paused, unless it asks to play`() = runTest {
        val room = newRoom()
        val client = room.connect()
        room.run(client, Command.AddSongs(listOf(song(1), song(2), song(3)), QueuePosition.END))
        runCurrent()
        room.run(client, Command.Pause)
        runCurrent()

        room.run(client, Command.Skip())
        runCurrent()
        assertEquals("ytm:song2", client.state.nowPlaying?.item?.song?.id)
        assertFalse(client.state.playback.playing)

        // Like YouTube Music's Next: the next song plays.
        room.run(client, Command.Skip(play = true))
        runCurrent()
        assertEquals("ytm:song3", client.state.nowPlaying?.item?.song?.id)
        assertTrue(client.state.playback.playing)
    }

    @Test
    fun `pause stops the clock`() = runTest {
        val room = newRoom()
        val client = room.connect()
        room.run(client, Command.AddSongs(listOf(song(1)), QueuePosition.END))
        runCurrent()

        advanceTimeBy(10_000)
        room.run(client, Command.Pause)
        advanceTimeBy(100_000)
        runCurrent()

        assertEquals("ytm:song1", client.state.nowPlaying?.item?.song?.id)
        assertEquals(10_000, client.state.playback.positionMs)
        assertEquals(false, client.state.playback.playing)
    }

    @Test
    fun `everyone sees the same events`() = runTest {
        val room = newRoom()
        val anna = room.connect("Anna")
        val ben = room.connect("Ben")
        room.run(ben, Command.AddSongs(listOf(song(1), song(2)), QueuePosition.END))
        runCurrent()

        assertEquals(anna.state.queue, ben.state.queue)
        assertEquals(anna.state.nowPlaying, ben.state.nowPlaying)
        assertEquals("Ben", anna.state.queue.single().addedByName)
    }

    @Test
    fun `a guest reconnecting with their token is the same participant`() = runTest {
        val room = newRoom()
        val first = room.connect("Anna")
        val token = first.messages.filterIsInstance<ServerMessage.Welcome>().single().guestToken
        room.disconnect(first.participantId, first)

        val again = FakeClient()
        room.join(ClientMessage.Hello(PROTOCOL_VERSION, "ABCD", "Anna", token, null), again)

        assertEquals(first.participantId, again.participantId)
        assertEquals(1, again.state.participants.size)
    }

    @Test
    fun `a song that can't be resolved is skipped`() = runTest {
        val room = Room("ABCD", "Test", "t", RoomVisibility.PUBLIC, { id -> if (id == "ytm:song1") error("blocked") else "https://stream/$id" }, backgroundScope) {
            testScheduler.currentTime
        }
        val client = room.connect()
        room.run(client, Command.AddSongs(listOf(song(1), song(2)), QueuePosition.END))
        runCurrent()

        assertEquals("ytm:song2", client.state.nowPlaying?.item?.song?.id)
        assertTrue(client.events.any { it is Event.Notice })
        assertNull(client.state.queue.firstOrNull())
    }

    @Test
    fun `when the source is blocked for a while, the room pauses on the song instead of skipping`() = runTest {
        class Blocked : Exception("YouTube is blocking requests from this network for now (bot check)."), SourceBlocked
        var blocked = true
        val room = Room("ABCD", "Test", "t", RoomVisibility.PUBLIC, { id -> if (blocked) throw Blocked() else "https://stream/$id" }, backgroundScope) {
            testScheduler.currentTime
        }
        val client = room.connect()
        room.run(client, Command.AddSongs(listOf(song(1), song(2)), QueuePosition.END))
        runCurrent()

        assertEquals("ytm:song1", client.state.nowPlaying?.item?.song?.id)
        assertEquals(listOf("ytm:song2"), client.state.queue.map { it.song.id })
        assertFalse(client.state.playback.playing)
        assertEquals("Paused: YouTube is blocking requests from this network for now (bot check).",
            client.events.filterIsInstance<Event.Notice>().last().message)

        // Play tries the same song again.
        blocked = false
        room.run(client, Command.Play)
        runCurrent()
        assertEquals("ytm:song1", client.state.nowPlaying?.item?.song?.id)
        assertTrue(client.state.playback.playing)
    }

    @Test
    fun `only the hosting device can join a private room until it is made public`() = runTest {
        val room = Room("ABCD", "Solo", "owner-token", RoomVisibility.PRIVATE, { "x" }, backgroundScope) { testScheduler.currentTime }
        val owner = FakeClient()
        room.join(ClientMessage.Hello(PROTOCOL_VERSION, "ABCD", "Me", null, "owner-token"), owner, local = true)

        val friend = FakeClient()
        room.join(ClientMessage.Hello(PROTOCOL_VERSION, "ABCD", "Friend", null, null), friend, local = false)
        assertEquals(ServerMessage.Rejected(RejectReason.PRIVATE_ROOM), friend.messages.single())

        room.run(owner, Command.SetVisibility(RoomVisibility.PUBLIC))
        val again = FakeClient()
        room.join(ClientMessage.Hello(PROTOCOL_VERSION, "ABCD", "Friend", null, null), again, local = false)
        assertIs<ServerMessage.Welcome>(again.messages.first())
        assertEquals(RoomVisibility.PUBLIC, owner.events.filterIsInstance<Event.RoomUpdated>().single().room.visibility)
    }

    @Test
    fun `only the owner can change visibility`() = runTest {
        val room = newRoom()
        val guest = FakeClient()
        room.join(ClientMessage.Hello(PROTOCOL_VERSION, "ABCD", "Guest", null, null), guest)
        room.run(guest, Command.SetVisibility(RoomVisibility.PRIVATE))
        val result = guest.messages.filterIsInstance<ServerMessage.Result>().single()
        assertEquals(dev.trashpanda.ytmp.protocol.ErrorCode.PERMISSION_DENIED, result.error?.code)
    }

    @Test
    fun `outputs can be switched on, get a volume, and disappear with their device`() = runTest {
        val room = newRoom()
        val client = room.connect()
        var changes = 0
        room.addChangeListener { changes++ }
        val bose = OutputDevice("cast:bose", "Bose", dev.trashpanda.ytmp.protocol.OutputKind.CHROMECAST)

        room.setAvailableOutputs(listOf(bose))
        room.run(client, Command.SetOutput("cast:bose", active = true))
        room.run(client, Command.SetOutputVolume("cast:bose", 0.4))
        assertEquals(mapOf("cast:bose" to 0.4), room.view().activeOutputs)
        assertEquals(true, client.events.filterIsInstance<Event.OutputsChanged>().last().outputs.single().active)

        room.setAvailableOutputs(emptyList())
        assertEquals(emptyMap(), room.view().activeOutputs)
        assertTrue(changes >= 4)
    }

    @Test
    fun `an unknown output can't be switched on`() = runTest {
        val room = newRoom()
        val client = room.connect()
        room.run(client, Command.SetOutput("cast:nope", active = true))
        assertEquals(dev.trashpanda.ytmp.protocol.ErrorCode.NOT_FOUND, client.messages.filterIsInstance<ServerMessage.Result>().last().error?.code)
    }
}
