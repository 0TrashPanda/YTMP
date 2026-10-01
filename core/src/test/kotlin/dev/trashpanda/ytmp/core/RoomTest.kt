package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.Event
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.QueueItemResult
import dev.trashpanda.ytmp.protocol.QueuePosition
import dev.trashpanda.ytmp.protocol.RoomState
import dev.trashpanda.ytmp.protocol.ServerMessage
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
                is ServerMessage.EventMessage -> state = apply(state, message.event)
                else -> Unit
            }
        }

        val events get() = messages.filterIsInstance<ServerMessage.EventMessage>().map { it.event }

        private fun apply(s: RoomState, e: Event): RoomState = when (e) {
            is Event.QueueItemsAdded -> s.copy(queue = s.queue.toMutableList().apply { addAll(e.index, e.items) })
            is Event.QueueItemRemoved -> s.copy(queue = s.queue.filter { it.itemId != e.itemId })
            is Event.QueueItemMoved -> {
                val q = s.queue.toMutableList()
                val item = q.first { it.itemId == e.itemId }
                q.remove(item); q.add(e.toIndex, item)
                s.copy(queue = q)
            }
            is Event.HistoryAppended -> s.copy(history = s.history + e.item)
            is Event.HistoryItemRemoved -> s.copy(history = s.history.filter { it.itemId != e.itemId })
            is Event.NowPlayingChanged -> s.copy(nowPlaying = e.item?.let { dev.trashpanda.ytmp.protocol.NowPlaying(it, null) })
            is Event.StreamReady -> s.copy(nowPlaying = s.nowPlaying?.copy(streamUrl = e.streamUrl))
            is Event.PlaybackChanged -> s.copy(playback = e.playback)
            else -> s
        }
    }

    private fun TestScope.newRoom(): Room =
        Room("ABCD", "Test", "owner-token", { id -> "https://stream/$id" }, backgroundScope) { testScheduler.currentTime }

    private suspend fun Room.connect(name: String = "Anna"): FakeClient {
        val client = FakeClient()
        join(ClientMessage.Hello(PROTOCOL_VERSION, "ABCD", name, null, null), client)
        return client
    }

    private suspend fun Room.run(client: FakeClient, command: Command) =
        handle(client.participantId, ClientMessage.CommandMessage("c", command))

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
        room.run(client, Command.Skip)
        runCurrent()
        room.run(client, Command.Skip)
        runCurrent()
        // Now: history [1, 2], playing 3.

        room.run(client, Command.JumpTo(client.state.history.first().itemId))
        runCurrent()

        assertEquals("ytm:song1", client.state.nowPlaying?.item?.song?.id)
        assertEquals(listOf("ytm:song2", "ytm:song3"), client.state.queue.map { it.song.id })
        assertTrue(client.state.history.isEmpty())
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
        val room = Room("ABCD", "Test", "t", { id -> if (id == "ytm:song1") error("blocked") else "https://stream/$id" }, backgroundScope) {
            testScheduler.currentTime
        }
        val client = room.connect()
        room.run(client, Command.AddSongs(listOf(song(1), song(2)), QueuePosition.END))
        runCurrent()

        assertEquals("ytm:song2", client.state.nowPlaying?.item?.song?.id)
        assertTrue(client.events.any { it is Event.Notice })
        assertNull(client.state.queue.firstOrNull())
    }
}
