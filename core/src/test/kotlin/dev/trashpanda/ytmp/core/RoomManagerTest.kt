package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.Event
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.QueuePosition
import dev.trashpanda.ytmp.protocol.RoomState
import dev.trashpanda.ytmp.protocol.RoomVisibility
import dev.trashpanda.ytmp.protocol.ServerMessage
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

@OptIn(ExperimentalCoroutinesApi::class)
class RoomManagerTest {
    private class MemoryStore : RoomStore {
        val rooms = ConcurrentHashMap<String, String>()

        override fun loadAll() = rooms.values.map(SavedRoom::fromJson)

        override fun save(room: SavedRoom) {
            rooms[room.code] = room.toJson()
        }

        override fun delete(code: String) {
            rooms.remove(code)
        }
    }

    private class Client : Outbox {
        var welcome: ServerMessage.Welcome? = null
        val events = mutableListOf<Event>()

        override fun send(message: ServerMessage) {
            when (message) {
                is ServerMessage.Welcome -> welcome = message
                is ServerMessage.EventMessage -> events += message.event
                else -> Unit
            }
        }

        val state: RoomState get() = welcome!!.state
    }

    private fun song(n: Int) = Song("ytm:song$n", "Song $n", listOf(ArtistRef(null, "Artist")), null, 60_000, emptyList())

    private val timeouts = RoomTimeouts(participantOffline = 15.minutes, emptyRoom = 60.minutes, checkInterval = 1.minutes)

    private fun TestScope.manager(store: RoomStore) = RoomManager(
        streams = { id -> "https://stream/$id" },
        scope = backgroundScope,
        timeouts = timeouts,
        clock = { testScheduler.currentTime },
        store = store,
        storeContext = StandardTestDispatcher(testScheduler),
    )

    private suspend fun Room.connect(name: String, guestToken: String? = null, ownerToken: String? = null): Client {
        val client = Client()
        join(ClientMessage.Hello(PROTOCOL_VERSION, code, name, guestToken, ownerToken), client)
        return client
    }

    private suspend fun Room.run(client: Client, command: Command) =
        handle(client.welcome!!.participantId, ClientMessage.CommandMessage("c", command))

    @Test
    fun `a room comes back after a restart, paused where it was`() = runTest {
        val store = MemoryStore()
        val before = manager(store)
        val room = before.create("Party")
        val anna = room.connect("Anna", ownerToken = room.ownerToken)
        room.run(anna, Command.AddSongs(listOf(song(1), song(2), song(3)), QueuePosition.END))
        runCurrent()
        room.run(anna, Command.Skip)
        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(store.rooms.containsKey(room.code))

        val after = manager(store)
        runCurrent()
        val restored = assertNotNull(after[room.code])
        assertEquals(room.ownerToken, restored.ownerToken)
        assertEquals(listOf(room.info), after.list.value)

        // Anna comes back as herself.
        val again = restored.connect("Anna", guestToken = anna.welcome!!.guestToken)
        val state = again.state
        assertEquals(anna.welcome!!.participantId, again.welcome!!.participantId)
        assertEquals("ytm:song2", state.nowPlaying?.item?.song?.id)
        assertNull(state.nowPlaying?.streamUrl)
        assertFalse(state.playback.playing)
        assertTrue(state.playback.positionMs in 15_000..20_000, "position ${state.playback.positionMs}")
        assertEquals(listOf("ytm:song3"), state.queue.map { it.song.id })
        assertEquals(listOf("ytm:song1"), state.history.map { it.song.id })

        // Play resolves the stream again and goes on from the saved position.
        restored.run(again, Command.Play)
        runCurrent()
        val playback = again.events.filterIsInstance<Event.PlaybackChanged>().last().playback
        assertTrue(playback.playing)
        assertEquals(state.playback.positionMs, playback.positionMs)
        assertTrue(again.events.any { it is Event.StreamReady })
    }

    @Test
    fun `closed rooms are removed from the store`() = runTest {
        val store = MemoryStore()
        val rooms = manager(store)
        val room = rooms.create("Party")
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(store.rooms.containsKey(room.code))

        rooms.close(room.code)
        runCurrent()
        assertTrue(store.rooms.isEmpty())
        assertTrue(manager(store).list.value.isEmpty())
    }

    @Test
    fun `empty public rooms expire, solo rooms stay`() = runTest {
        val store = MemoryStore()
        val rooms = manager(store)
        val party = rooms.create("Party")
        val solo = rooms.create("Solo", RoomVisibility.PRIVATE)
        rooms.startCleanup()

        advanceTimeBy(62.minutes)
        runCurrent()

        assertNull(rooms[party.code])
        assertNotNull(rooms[solo.code])
        assertEquals(setOf(solo.code), store.rooms.keys)
    }

    @Test
    fun `hosting lists public rooms and playing solo rooms`() = runTest {
        val rooms = manager(MemoryStore())
        val party = rooms.create("Party")
        val solo = rooms.create("Solo", RoomVisibility.PRIVATE)
        assertEquals(listOf(party.code), rooms.hosting.value.map { it.code })

        val me = solo.connect("Me", ownerToken = solo.ownerToken)
        solo.run(me, Command.AddSongs(listOf(song(1)), QueuePosition.END))
        runCurrent()
        assertEquals(setOf(party.code, solo.code), rooms.hosting.value.map { it.code }.toSet())

        solo.pause()
        assertEquals(listOf(party.code), rooms.hosting.value.map { it.code })
    }
}
