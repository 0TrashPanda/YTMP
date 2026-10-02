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
            val me = room.join(ClientMessage.Hello(PROTOCOL_VERSION, room.code, "Me", null, null), Outbox { })!!
            suspend fun run(command: Command) = room.handle(me, ClientMessage.CommandMessage("c", command))

            run(Command.AddSongs(listOf(song), QueuePosition.END))
            eventually { room.view().activeOutputs.isEmpty() && casts.devices.value.isNotEmpty() }
            run(Command.SetOutput("cast:fake", active = true))

            // The device starts its media player and loads the song from the host, playing.
            eventually { "LOAD" in device.types(CastChannel.NS_MEDIA) }
            val load = device.requests.first { it.second["type"]?.jsonPrimitive?.content == "LOAD" }.second
            assertEquals("http://127.0.0.1:8765/api/audio/ytm%3Aabc", load["media"]!!.jsonObject["contentId"]!!.jsonPrimitive.content)
            assertEquals("PLAYING", device.playerState)
            assertTrue("LAUNCH" in device.types(CastChannel.NS_RECEIVER))
            // The device's own volume is reported back to the room.
            eventually { room.view().activeOutputs["cast:fake"] == 0.5 }

            run(Command.Pause)
            eventually { device.playerState == "PAUSED" }

            run(Command.SetOutputVolume("cast:fake", 0.3))
            eventually { device.volume == 0.3 }

            run(Command.SetOutput("cast:fake", active = false))
            eventually { "STOP" in device.types(CastChannel.NS_RECEIVER) }
        }
        scope.cancel()
    }

    private suspend fun eventually(check: suspend () -> Boolean) {
        withTimeout(10_000) {
            while (!check()) delay(50)
        }
    }
}
