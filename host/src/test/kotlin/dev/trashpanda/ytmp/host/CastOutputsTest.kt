package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.cast.CastChannel
import dev.trashpanda.ytmp.core.Outbox
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CastOutputsTest {
    private val song = Song("ytm:abc", "One More Time", listOf(ArtistRef(null, "Daft Punk")), null, 320_000, emptyList())

    @Test
    fun `a room plays on a Cast device and follows pause, volume and switching off`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        FakeCastDevice().use { device ->
            val casts = CastOutputs(scope) { address -> "http://${address.hostAddress}:8765" }
            casts.add(CastDeviceAddress("cast:fake", "Fake TV", "127.0.0.1", device.port))
            val rooms = RoomManager({ "https://stream/$it" }, scope, outputs = casts.devices)
            casts.attach(rooms)

            val room = rooms.create("Party")
            val me = room.join(ClientMessage.Hello(PROTOCOL_VERSION, room.code, "Me", null, room.ownerToken), Outbox { })!!
            suspend fun run(command: Command) = room.handle(me, ClientMessage.CommandMessage("c", command))

            run(Command.AddSongs(listOf(song), QueuePosition.END))
            eventually { room.view().activeOutputs.isEmpty() && casts.devices.value.isNotEmpty() }
            run(Command.SetOutput("cast:fake", active = true))

            // The device starts its media player and loads the song's direct stream URL, playing.
            eventually { "LOAD" in device.types(CastChannel.NS_MEDIA) }
            assertEquals("https://stream/ytm:abc", device.contentId)
            assertEquals("PLAYING", device.playerState)
            assertTrue("LAUNCH" in device.types(CastChannel.NS_RECEIVER))
            // The device's own volume is reported back to the room.
            eventually { room.view().activeOutputs["cast:fake"] == 0.5 }

            run(Command.Pause)
            eventually { device.playerState == "PAUSED" }

            run(Command.SetOutputVolume("cast:fake", 0.3))
            eventually { device.volume == 0.3 }

            // Turned up on the device itself: the room's slider follows.
            device.changeVolumeExternally(0.65000000596)
            eventually { room.view().activeOutputs["cast:fake"] == 0.65 }

            run(Command.SetOutput("cast:fake", active = false))
            eventually { "STOP" in device.types(CastChannel.NS_RECEIVER) }
        }
        scope.cancel()
    }

    /** A room with a song playing on [device]. */
    private suspend fun playOn(device: FakeCastDevice, scope: CoroutineScope) {
        val casts = CastOutputs(scope) { address -> "http://${address.hostAddress}:8765" }
        casts.add(CastDeviceAddress("cast:fake", "Fake TV", "127.0.0.1", device.port))
        val rooms = RoomManager({ "https://stream/$it" }, scope, outputs = casts.devices)
        casts.attach(rooms)
        val room = rooms.create("Party")
        val me = room.join(ClientMessage.Hello(PROTOCOL_VERSION, room.code, "Me", null, room.ownerToken), Outbox { })!!
        room.handle(me, ClientMessage.CommandMessage("a", Command.AddSongs(listOf(song), QueuePosition.END)))
        eventually { casts.devices.value.isNotEmpty() && room.view().current != null }
        room.handle(me, ClientMessage.CommandMessage("b", Command.SetOutput("cast:fake", active = true)))
    }

    @Test
    fun `a device that can't use the direct URL gets the host's proxy`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        FakeCastDevice().use { device ->
            device.rejectUrlsStartingWith = "https://stream/"
            playOn(device, scope)
            eventually { device.contentId == "http://127.0.0.1:8765/api/audio/ytm%3Aabc" }
            assertEquals("PLAYING", device.playerState)
        }
        scope.cancel()
    }

    @Test
    fun `a device that loses the direct stream mid-song continues via the host`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        FakeCastDevice().use { device ->
            device.dropUrlsStartingWith = "https://stream/"
            playOn(device, scope)
            eventually { device.contentId == "https://stream/ytm:abc" }
            eventually(timeoutMs = 20_000) { device.contentId == "http://127.0.0.1:8765/api/audio/ytm%3Aabc" }
        }
        scope.cancel()
    }

    private suspend fun eventually(timeoutMs: Long = 10_000, check: suspend () -> Boolean) {
        withTimeout(timeoutMs) {
            while (!check()) delay(50)
        }
    }
}
