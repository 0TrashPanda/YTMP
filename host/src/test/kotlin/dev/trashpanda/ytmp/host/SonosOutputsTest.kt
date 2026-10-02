package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.Outbox
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.OutputKind
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.QueuePosition
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SonosOutputsTest {
    private val song = Song("ytm:abc", "One More Time", listOf(ArtistRef(null, "Daft Punk")), null, 320_000, emptyList())

    @Test
    fun `a room plays on a Sonos and follows pause, volume and switching off`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        FakeSonos().use { speaker ->
            val sonos = SonosOutputs(scope) { address -> "http://${address.hostAddress}:8765" }
            sonos.add(SonosAddress("sonos:fake", "Woonkamer", "127.0.0.1", speaker.port))
            eventually { sonos.devices.value.isNotEmpty() }
            assertEquals(OutputKind.SONOS, sonos.devices.value.single().kind)
            val rooms = RoomManager({ "https://stream/$it" }, scope, outputs = sonos.devices)
            sonos.attach(rooms)
            val room = rooms.create("Party")
            val me = room.join(ClientMessage.Hello(PROTOCOL_VERSION, room.code, "Me", null, room.ownerToken), Outbox { })!!
            suspend fun run(command: Command) = room.handle(me, ClientMessage.CommandMessage("c", command))

            run(Command.AddSongs(listOf(song), QueuePosition.END))
            eventually { room.view().current != null }
            run(Command.SetOutput("sonos:fake", active = true))

            // The speaker gets the host's audio URL (it can seek in it) with the song's details, and plays.
            eventually { speaker.state == "PLAYING" }
            assertEquals("http://127.0.0.1:8765/api/audio/ytm%3Aabc", speaker.uri)
            assertEquals("One More Time", speaker.title)
            // Its own volume shows in the room.
            eventually { room.view().activeOutputs["sonos:fake"] == 0.4 }

            run(Command.Pause)
            eventually { speaker.state == "PAUSED_PLAYBACK" }
            run(Command.SetOutputVolume("sonos:fake", 0.25))
            eventually { speaker.volume == 25 }
            run(Command.Play)
            eventually { speaker.state == "PLAYING" }

            // Way off (someone seeked in the room): the speaker is moved there.
            run(Command.Seek(120_000))
            eventually { speaker.positionMs in 119_000..125_000 }

            run(Command.SetOutput("sonos:fake", active = false))
            eventually { speaker.state == "STOPPED" }
            assertTrue("Stop" in speaker.actions)
        }
        scope.cancel()
    }

    @Test
    fun `DIDL metadata and times`() {
        assertEquals("0:05:21", SonosPlayer.time(321_000))
        assertEquals(3_723_000, SonosPlayer.parseTime("1:02:03"))
        val didl = SonosPlayer.didl("http://h/api/audio/x?a=1&b=2", "Rock & Roll", "AC/DC", null, null, 60_000)
        assertTrue("Rock &amp; Roll" in didl && "a=1&amp;b=2" in didl)
    }

    private suspend fun eventually(check: suspend () -> Boolean) = withTimeout(15_000) {
        while (!check()) delay(100)
    }
}
