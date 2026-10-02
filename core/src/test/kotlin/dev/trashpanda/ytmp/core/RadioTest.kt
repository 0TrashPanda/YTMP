package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.QueueItemOrigin
import dev.trashpanda.ytmp.protocol.QueuePosition
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RadioTest {
    private fun song(id: String) = Song(id, "Song $id", listOf(ArtistRef(null, "Artist")), null, 60_000, emptyList())

    /** Like YTM: the seed first, then 10 "similar" songs named after it (seed-1, seed-2, …). */
    private val radio = RadioSource { seed -> listOf(song(seed)) + (1..10).map { song("$seed-$it") } }
    val requests = mutableListOf<String>()

    private lateinit var me: String
    private val messages = mutableListOf<ServerMessage>()

    private suspend fun TestScope.room(): Room {
        val room = Room("ABCD", "Party", "owner", RoomVisibility.PUBLIC, { "https://s/$it" }, backgroundScope, radio = { requests += it; radio.radio(it) }) { testScheduler.currentTime }
        room.join(ClientMessage.Hello(PROTOCOL_VERSION, "ABCD", "Me", null, "owner"), {
            messages += it
            if (it is ServerMessage.Welcome) me = it.participantId
        })
        return room
    }

    private suspend fun Room.run(command: Command) = handle(me, ClientMessage.CommandMessage("c", command))

    private suspend fun Room.state(): RoomState {
        handle(me, ClientMessage.RequestSnapshot)
        return (messages.last() as ServerMessage.Snapshot).state
    }

    @Test
    fun `start radio plays the song and replaces the queue`() = runTest {
        val room = room()
        room.run(Command.AddSongs(listOf(song("x")), QueuePosition.END))
        runCurrent()
        assertTrue(room.state().autoplay.isNotEmpty())
        room.run(Command.StartRadio(song("a")))
        runCurrent()

        val state = room.state()
        assertEquals("a", state.nowPlaying?.item?.song?.id)
        assertEquals((1..10).map { "a-$it" }, state.queue.map { it.song.id })
        assertTrue(state.queue.all { it.origin == QueueItemOrigin.RADIO && it.addedByName == "Me" })
        // Autoplay from the old queue is gone; the queue is long enough not to need it yet.
        assertTrue(state.autoplay.isEmpty())
    }

    @Test
    fun `when the queue runs out, autoplay keeps going without repeats`() = runTest {
        val room = room()
        room.run(Command.AddSongs(listOf(song("a")), QueuePosition.END))
        runCurrent()
        // With one song left, autoplay is already lined up (like YTM shows it).
        var state = room.state()
        assertEquals((1..10).map { "a-$it" }, state.autoplay.map { it.song.id })
        assertTrue(state.autoplay.all { it.origin == QueueItemOrigin.AUTOPLAY })

        // Let 12 songs play: the autoplay queue refills itself from its last song.
        repeat(12) {
            advanceTimeBy(60_001)
            runCurrent()
        }
        state = room.state()
        assertTrue(state.playback.playing)
        val played = state.history.map { it.song.id } + state.nowPlaying!!.item.song.id
        assertEquals(played.size, played.toSet().size, "no repeats: $played")
        assertTrue(state.autoplay.size >= Room.AUTOPLAY_LOW - 1)
    }

    @Test
    fun `with autoplay off the music stops at the end of the queue`() = runTest {
        val room = room()
        room.run(Command.UpdateSettings(autoplay = false))
        room.run(Command.AddSongs(listOf(song("a")), QueuePosition.END))
        runCurrent()
        assertTrue(room.state().autoplay.isEmpty())
        advanceTimeBy(60_001)
        runCurrent()
        val state = room.state()
        assertNull(state.nowPlaying)
        assertFalse(state.playback.playing)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `autoplay from here works even with the setting off`() = runTest {
        val room = room()
        room.run(Command.UpdateSettings(autoplay = false))
        room.run(Command.AutoplayFromHere(song("b")))
        runCurrent()
        // Nothing was playing, so it starts.
        var state = room.state()
        assertEquals("b", state.autoplaySeed?.id)
        assertEquals("b-1", state.nowPlaying?.item?.song?.id)
        repeat(10) {
            advanceTimeBy(60_001)
            runCurrent()
        }
        state = room.state()
        assertTrue(state.playback.playing, "keeps going")

        // Turning autoplay off ends it.
        room.run(Command.UpdateSettings(autoplay = true))
        room.run(Command.UpdateSettings(autoplay = false))
        assertTrue(room.state().autoplay.isEmpty())
    }

    @Test
    fun `tapping an autoplay song plays it and drops the ones before`() = runTest {
        val room = room()
        room.run(Command.AddSongs(listOf(song("a")), QueuePosition.END))
        runCurrent()
        val target = room.state().autoplay[3]
        room.run(Command.JumpTo(target.itemId))
        runCurrent()
        val state = room.state()
        assertEquals(target.song.id, state.nowPlaying?.item?.song?.id)
        assertEquals("a-5", state.autoplay.first().song.id)
    }
}
