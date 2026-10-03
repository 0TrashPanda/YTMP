package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.Event
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.PodcastRef
import dev.trashpanda.ytmp.protocol.QueuePosition
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

@OptIn(ExperimentalCoroutinesApi::class)
class PodcastTest {
    private val podcast = PodcastRef("MPSP1", "The Show")
    // Listed as "1 hr" (rounded); the stream is 61.5 minutes.
    private val episode = Song("ytm:ep1", "Episode 1", emptyList(), null, 3_600_000, emptyList(), podcast = podcast)
    private val song = Song("ytm:s1", "A song", listOf(ArtistRef(null, "A")), null, 60_000, emptyList())

    private val exact = object : StreamResolver {
        override suspend fun resolveStream(songId: String) = "https://stream/$songId"
        override suspend fun resolve(songId: String) =
            ResolvedStream(resolveStream(songId), if (songId == episode.id) 3_690_000 else 60_000)
    }

    private class Client : Outbox {
        val messages = mutableListOf<ServerMessage>()
        var participantId = ""
        override fun send(message: ServerMessage) {
            messages += message
            if (message is ServerMessage.Welcome) participantId = message.participantId
        }
        val events get() = messages.filterIsInstance<ServerMessage.EventMessage>().map { it.event }
    }

    private fun TestScope.newRoom() = Room("ABCD", "Test", "owner", RoomVisibility.PUBLIC, exact, backgroundScope) { testScheduler.currentTime }

    private suspend fun Room.owner() = Client().also { join(ClientMessage.Hello(PROTOCOL_VERSION, "ABCD", "Anna", null, "owner"), it) }

    private suspend fun Room.run(client: Client, command: Command) = handle(client.participantId, ClientMessage.CommandMessage("c", command))

    @Test
    fun `an episode gets its exact length from the stream`() = runTest {
        val room = newRoom()
        val anna = room.owner()
        room.run(anna, Command.AddSongs(listOf(episode), QueuePosition.END))
        runCurrent()
        val now = anna.events.filterIsInstance<Event.NowPlayingChanged>().last().item!!
        assertEquals(3_690_000, now.song.durationMs)
        // It ends by that length, not the listed one.
        advanceTimeBy(3_600_000 + 1)
        assertEquals(episode.id, room.save().current?.song?.id)
        advanceTimeBy(90_000)
        assertEquals(null, room.save().current)
    }

    @Test
    fun `an episode left halfway resumes there`() = runTest {
        val room = newRoom()
        val anna = room.owner()
        room.run(anna, Command.AddSongs(listOf(episode, song), QueuePosition.END))
        runCurrent()
        advanceTimeBy(600_000)
        room.run(anna, Command.Skip)
        runCurrent()
        assertEquals(song.id, room.save().current?.song?.id)

        room.run(anna, Command.PlayNow(episode))
        runCurrent()
        val playback = anna.events.filterIsInstance<Event.PlaybackChanged>().last().playback
        assertEquals(600_000, playback.positionMs)
        // Saved with the room, too.
        assertEquals(600_000, room.save().episodePositions[episode.id])
    }

    @Test
    fun `a finished episode starts over`() = runTest {
        val room = newRoom()
        val anna = room.owner()
        room.run(anna, Command.AddSongs(listOf(episode), QueuePosition.END))
        runCurrent()
        advanceTimeBy(3_690_000 + 1)
        runCurrent()
        room.run(anna, Command.PlayNow(episode))
        runCurrent()
        assertEquals(0, anna.events.filterIsInstance<Event.PlaybackChanged>().last().playback.positionMs)
    }

    @Test
    fun `no autoplay radio after an episode`() = runTest {
        var radios = 0
        val room = Room(
            "ABCD", "Test", "owner", RoomVisibility.PUBLIC, exact, backgroundScope,
            radio = { radios++; listOf(song) },
        ) { testScheduler.currentTime }
        val anna = room.owner()
        room.run(anna, Command.AddSongs(listOf(episode), QueuePosition.END))
        runCurrent()
        advanceTimeBy(3_690_000 + 1)
        runCurrent()
        assertEquals(0, radios)
        assertEquals(null, room.save().current)
    }

    @Test
    fun `songs don't resume`() = runTest {
        val room = newRoom()
        val anna = room.owner()
        val long = song.copy(id = "ytm:long", durationMs = 600_000)
        room.run(anna, Command.AddSongs(listOf(long, song), QueuePosition.END))
        runCurrent()
        advanceTimeBy(120_000)
        room.run(anna, Command.Skip)
        runCurrent()
        room.run(anna, Command.PlayNow(long))
        runCurrent()
        assertEquals(0, anna.events.filterIsInstance<Event.PlaybackChanged>().last().playback.positionMs)
    }
}
