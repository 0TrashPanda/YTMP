package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.Outbox
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.Event
import dev.trashpanda.ytmp.protocol.OutputKind
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.QueuePosition
import dev.trashpanda.ytmp.protocol.ServerMessage
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MpdOutputsTest {
    private val song = Song("ytm:abc", "One More Time", listOf(ArtistRef(null, "Daft Punk")), null, 320_000, emptyList())

    @Test
    fun `a room plays on an MPD and follows pause, volume, seeking and switching off`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        FakeMpd().use { mpd ->
            val outputs = MpdOutputs(scope) { address -> "http://${address.hostAddress}:8765" }
            // Named after its one audio output, as MPD's default mDNS name says nothing.
            outputs.addHost("127.0.0.1", mpd.port, "Music Player @ raspberrypi")
            eventually { outputs.devices.value.isNotEmpty() }
            val device = outputs.devices.value.single()
            assertEquals("Bluetooth Speaker", device.name)
            assertEquals(OutputKind.MPD, device.kind)
            val rooms = RoomManager({ "https://stream/$it" }, scope, outputs = outputs.devices)
            outputs.attach(rooms)
            val room = rooms.create("Party")
            val me = room.join(ClientMessage.Hello(PROTOCOL_VERSION, room.code, "Me", null, room.ownerToken), Outbox { })!!
            suspend fun run(command: Command) = room.handle(me, ClientMessage.CommandMessage("c", command))

            run(Command.AddSongs(listOf(song), QueuePosition.END))
            eventually { room.view().current != null }
            run(Command.SetOutput(device.id, active = true))

            // MPD gets the host's audio URL (it can seek in it) in place of its own queue, and plays.
            eventually { mpd.state == "play" }
            assertEquals("http://127.0.0.1:8765/api/audio/ytm%3Aabc", mpd.file)
            assertTrue("clear" in mpd.commands)
            // Its own volume shows in the room.
            eventually { room.view().activeOutputs[device.id] == 0.4 }

            run(Command.Pause)
            eventually { mpd.state == "pause" }
            run(Command.SetOutputVolume(device.id, 0.25))
            eventually { mpd.volume == 25 }
            run(Command.Play)
            eventually { mpd.state == "play" }
            // Changed in another MPD app: the room follows.
            mpd.volume = 60
            eventually { room.view().activeOutputs[device.id] == 0.6 }

            // Way off (someone seeked in the room): MPD is moved there.
            run(Command.Seek(120_000))
            eventually { mpd.positionMs in 119_000..125_000 }

            run(Command.SetOutput(device.id, active = false))
            eventually { mpd.state == "stop" && mpd.file == null }
        }
        scope.cancel()
    }

    @Test
    fun `without a mixer there's no volume, and MPD playing its own music lets the room go`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        FakeMpd(volume = null).use { mpd ->
            val outputs = MpdOutputs(scope) { address -> "http://${address.hostAddress}:8765" }
            outputs.add(MpdAddress("mpd:fake", "Pi", "127.0.0.1", mpd.port))
            eventually { outputs.devices.value.isNotEmpty() }
            val rooms = RoomManager({ "https://stream/$it" }, scope, outputs = outputs.devices)
            outputs.attach(rooms)
            val room = rooms.create("Party")
            val notices = mutableListOf<String>()
            val me = room.join(ClientMessage.Hello(PROTOCOL_VERSION, room.code, "Me", null, room.ownerToken), Outbox { m ->
                ((m as? ServerMessage.EventMessage)?.event as? Event.Notice)?.let { synchronized(notices) { notices += it.message } }
            })!!
            suspend fun run(command: Command) = room.handle(me, ClientMessage.CommandMessage("c", command))
            run(Command.AddSongs(listOf(song, song.copy(id = "ytm:def", title = "Next")), QueuePosition.END))
            run(Command.SetOutput("mpd:fake", active = true))
            eventually { mpd.state == "play" }
            delay(2_500)
            assertNull(room.view().activeOutputs["mpd:fake"])
            assertTrue(mpd.commands.none { it.startsWith("setvol") })

            // Someone plays their own library on it.
            val commandsBefore = mpd.commands.size
            mpd.file = "Avril Lavigne/Let Go/03 Skater Boy.mp3"
            eventually { "mpd:fake" !in room.view().activeOutputs }
            assertTrue(synchronized(notices) { notices.any { "Pi is playing something else" in it } })
            // The room doesn't stop it, and doesn't take it back at the next song.
            run(Command.Skip())
            delay(3_000)
            assertEquals("Avril Lavigne/Let Go/03 Skater Boy.mp3", mpd.file)
            assertTrue(mpd.commands.drop(commandsBefore).none { it == "stop" || it == "clear" })
        }
        scope.cancel()
    }

    @Test
    fun `protocol details and names`() {
        assertEquals("\"http://h/a?b=\\\"c\\\"\"", MpdPlayer.quote("http://h/a?b=\"c\""))
        assertEquals("No such song", MpdPlayer.ackText("ACK [50@0] {play} No such song"))
        assertEquals("61.500", MpdPlayer.seconds(61_500))
        val outputs = MpdPlayer.records(listOf("outputid" to "0", "outputname" to "A", "outputid" to "1", "outputname" to "B"), "outputid")
        assertEquals(listOf("A", "B"), outputs.map { it["outputname"] })

        val speaker = listOf(MpdOutput("Bluetooth Speaker", true))
        assertEquals("Keuken", MpdOutputs.displayName("Keuken", speaker, "10.0.0.5"))
        assertEquals("Bluetooth Speaker", MpdOutputs.displayName("Music Player @ pi", speaker, "10.0.0.5"))
        assertEquals("pi", MpdOutputs.displayName("Music Player @ pi", speaker + MpdOutput("HDMI", true), "10.0.0.5"))
        assertEquals("10.0.0.5", MpdOutputs.displayName(null, emptyList(), "10.0.0.5"))
        assertEquals("mpd:10.0.0.5", MpdOutputs.idFor("10.0.0.5"))
        assertEquals("mpd:10.0.0.5:6601", MpdOutputs.idFor("10.0.0.5", 6601))
    }

    private suspend fun eventually(check: suspend () -> Boolean) = withTimeout(15_000) {
        while (!check()) delay(100)
    }
}
