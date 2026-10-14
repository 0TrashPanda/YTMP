package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.cast.CastChannel
import dev.trashpanda.ytmp.core.Outbox
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.QueuePosition
import dev.trashpanda.ytmp.protocol.RoomState
import dev.trashpanda.ytmp.protocol.ServerMessage
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A room on another host, played on speakers near the phone (the room here stands in for the server's). */
class NearbySpeakersTest {
    private val song = Song("ytm:abc", "One More Time", listOf(ArtistRef(null, "Daft Punk")), null, 320_000, emptyList())

    private class FakeSource : RoomSource {
        override val room = MutableStateFlow<Pair<RoomState, Double>?>(null)
        val commands = CopyOnWriteArrayList<Command>()
        @Volatile var closed = false
        override fun command(command: Command): Boolean = (room.value != null).also { if (it) commands += command }
        override fun close() {
            closed = true
        }
    }

    @Test
    fun `the phone plays a server room on its Chromecast, with the audio from the phone, and the room hears so`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        FakeCastDevice().use { device ->
            val casts = CastOutputs(scope) { address -> "http://${address.hostAddress}:8765" }
            casts.add(CastDeviceAddress("cast:fake", "Woonkamer", "127.0.0.1", device.port))
            val nearby = NearbySpeakers(scope, casts, SonosOutputs(scope) { "http://unused" })

            // The server's room (no speakers of its own), as the app follows it.
            val server = RoomManager({ "https://googlevideo.example/$it" }, scope)
            val room = server.create("Party")
            var state: RoomState? = null
            val me = room.join(ClientMessage.Hello(PROTOCOL_VERSION, room.code, "Me", null, room.ownerToken), Outbox { m ->
                if (m is ServerMessage.Welcome) state = m.state
                if (m is ServerMessage.Snapshot) state = m.state
            })!!
            room.handle(me, ClientMessage.CommandMessage("a", Command.AddSongs(listOf(song), QueuePosition.END)))
            room.handle(me, ClientMessage.CommandMessage("p", Command.SetListening(true)))
            val source = FakeSource()
            suspend fun refresh() {
                room.handle(me, ClientMessage.RequestSnapshot)
                source.room.value = state!! to 0.0
            }
            eventually { refresh(); state?.nowPlaying?.streamUrl != null && state!!.playback.playing }

            nearby.turnOn(room.code, "cast:fake", "Woonkamer") { source }
            eventually { device.playerState == "PLAYING" }
            // Not the server's link (it only works on the server's connection): the phone's.
            assertEquals("http://127.0.0.1:8765/api/audio/ytm%3Aabc", device.contentId)
            eventually { Command.SetSpeakers(listOf("Woonkamer"), startsTogether = true) in source.commands }
            assertEquals(listOf(NearbySpeaker("cast:fake", "Woonkamer", 0.5)), nearby.playing.value?.speakers)

            // The room heard: it keeps playing when the phone itself stops listening.
            room.handle(me, ClientMessage.CommandMessage("s", Command.SetSpeakers(listOf("Woonkamer"))))
            room.handle(me, ClientMessage.CommandMessage("q", Command.SetListening(false)))
            refresh()
            assertTrue(state!!.playback.playing)
            assertEquals(listOf("Woonkamer"), state!!.participants.single { it.id == me }.speakers)

            nearby.setVolume("cast:fake", 0.3)
            eventually { device.volume == 0.3 }

            // Paused in the room: paused on the speaker.
            room.handle(me, ClientMessage.CommandMessage("x", Command.Pause))
            refresh()
            eventually { device.playerState == "PAUSED" }

            nearby.turnOff("cast:fake")
            assertEquals(null, nearby.playing.value)
            eventually { "STOP" in device.types(CastChannel.NS_RECEIVER) }
            assertEquals(emptyList(), (source.commands.last() as Command.SetSpeakers).names)
            eventually { source.closed }
        }
        scope.cancel()
    }

    @Test
    fun `a new song waits until the speakers near the phone have it, and they start it at its start`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        FakeCastDevice().use { device ->
            val casts = CastOutputs(scope) { address -> "http://${address.hostAddress}:8765" }
            casts.add(CastDeviceAddress("cast:fake", "Woonkamer", "127.0.0.1", device.port))
            val nearby = NearbySpeakers(scope, casts, SonosOutputs(scope) { "http://unused" })

            val server = RoomManager({ "https://googlevideo.example/$it" }, scope)
            val room = server.create("Party")
            var state: RoomState? = null
            val me = room.join(ClientMessage.Hello(PROTOCOL_VERSION, room.code, "Me", null, room.ownerToken), Outbox { m ->
                if (m is ServerMessage.Welcome) state = m.state
                if (m is ServerMessage.Snapshot) state = m.state
            })!!
            val source = FakeSource()
            suspend fun refresh() {
                room.handle(me, ClientMessage.RequestSnapshot)
                source.room.value = state!! to 0.0
            }
            refresh()
            nearby.turnOn(room.code, "cast:fake", "Woonkamer") { source }
            // What the app does with the phone's commands: they go to the room.
            eventually { source.commands.any { it is Command.SetSpeakers } }
            room.handle(me, ClientMessage.CommandMessage("s", source.commands.first { it is Command.SetSpeakers }))

            room.handle(me, ClientMessage.CommandMessage("a", Command.AddSongs(listOf(song), QueuePosition.END)))
            eventually { refresh(); state?.nowPlaying?.streamUrl != null }
            assertTrue(state!!.playback.starting)
            // Loaded on the speaker, paused at the very start, and the phone says so.
            eventually { device.playerState == "PAUSED" }
            assertEquals(0.0, device.currentTime)
            val itemId = state!!.nowPlaying!!.item.itemId
            eventually { Command.PlayerReady(itemId, speakers = true) in source.commands }

            room.handle(me, ClientMessage.CommandMessage("r", Command.PlayerReady(itemId, speakers = true)))
            refresh()
            assertTrue(state!!.playback.playing)
            eventually { device.playerState == "PLAYING" }
            nearby.turnOff("cast:fake")
        }
        scope.cancel()
    }

    private suspend fun eventually(check: suspend () -> Boolean) = withTimeout(15_000) {
        while (!check()) delay(50)
    }
}
