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
            eventually { "QUEUE_LOAD" in device.types(CastChannel.NS_MEDIA) }
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

    @Test
    fun `a TV shows the party screen with DashCast, and goes back to only the music`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        FakeCastDevice().use { device ->
            val casts = CastOutputs(scope, pageBaseUrl = { "https://ytmp.example.com" }) { address -> "http://${address.hostAddress}:8765" }
            casts.add(CastDeviceAddress("cast:fake", "Living room TV", "127.0.0.1", device.port))
            val rooms = RoomManager({ "https://stream/$it" }, scope, outputs = casts.devices)
            casts.attach(rooms)

            val room = rooms.create("Party")
            val me = room.join(ClientMessage.Hello(PROTOCOL_VERSION, room.code, "Me", null, room.ownerToken), Outbox { })!!
            suspend fun run(command: Command) = room.handle(me, ClientMessage.CommandMessage("c", command))
            run(Command.AddSongs(listOf(song), QueuePosition.END))
            eventually { casts.devices.value.isNotEmpty() }

            run(Command.SetOutput("cast:fake", active = true))
            eventually { device.playerState == "PLAYING" }

            // The party screen: DashCast replaces the media player and opens the room's /tv page, signed in as the screen.
            run(Command.SetOutput("cast:fake", active = true, screen = true))
            eventually { device.dashCastUrl != null }
            val token = room.view().screens.getValue("cast:fake")
            assertEquals("https://ytmp.example.com/tv#cast=${room.code}.$token", device.dashCastUrl)
            assertTrue(room.isScreen(token))
            val screen = room.join(ClientMessage.Hello(PROTOCOL_VERSION, room.code, "TV", token, null), Outbox { })!!
            room.handle(screen, ClientMessage.CommandMessage("l", Command.SetListening(true))) // it may play the music

            // Back to only the music: the media player starts again and the screen participant goes.
            run(Command.SetOutput("cast:fake", active = true, screen = false))
            eventually { !device.dashCastRunning && device.playerState == "PLAYING" }
            assertTrue(!room.isScreen(token))

            run(Command.SetOutput("cast:fake", active = false))
            eventually { "STOP" in device.types(CastChannel.NS_RECEIVER) }
        }
        scope.cancel()
    }

    @Test
    fun `when another app is cast to the device, the room lets go of it`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        FakeCastDevice().use { device ->
            val casts = CastOutputs(scope) { address -> "http://${address.hostAddress}:8765" }
            casts.add(CastDeviceAddress("cast:fake", "Fake TV", "127.0.0.1", device.port))
            val rooms = RoomManager({ "https://stream/$it" }, scope, outputs = casts.devices)
            casts.attach(rooms)
            val room = rooms.create("Party")
            val me = room.join(ClientMessage.Hello(PROTOCOL_VERSION, room.code, "Me", null, room.ownerToken), Outbox { })!!
            suspend fun run(command: Command) = room.handle(me, ClientMessage.CommandMessage("c", command))
            run(Command.AddSongs(listOf(song, song.copy(id = "ytm:def", title = "Next")), QueuePosition.END))
            eventually { casts.devices.value.isNotEmpty() }
            run(Command.SetOutput("cast:fake", active = true))
            eventually { "QUEUE_LOAD" in device.types(CastChannel.NS_MEDIA) }
            val launches = device.types(CastChannel.NS_RECEIVER).count { it == "LAUNCH" }

            device.takenOverBy("CC32E753") // Spotify
            eventually { "cast:fake" !in room.view().activeOutputs }
            run(Command.Skip)
            kotlinx.coroutines.delay(3_000)
            // Not launched again over Spotify.
            assertEquals(launches, device.types(CastChannel.NS_RECEIVER).count { it == "LAUNCH" })

            // Turned on again by hand: then it does take it.
            run(Command.SetOutput("cast:fake", active = true))
            eventually { device.types(CastChannel.NS_RECEIVER).count { it == "LAUNCH" } > launches }
        }
        scope.cancel()
    }

    @Test
    fun `next and previous on the device itself move the room along`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        FakeCastDevice().use { device ->
            val casts = CastOutputs(scope) { address -> "http://${address.hostAddress}:8765" }
            casts.add(CastDeviceAddress("cast:fake", "Fake TV", "127.0.0.1", device.port))
            val rooms = RoomManager({ "https://stream/$it" }, scope, outputs = casts.devices)
            casts.attach(rooms)
            val room = rooms.create("Party")
            val me = room.join(ClientMessage.Hello(PROTOCOL_VERSION, room.code, "Me", null, room.ownerToken), Outbox { })!!
            suspend fun run(command: Command) = room.handle(me, ClientMessage.CommandMessage("c", command))
            val songs = (1..3).map { song.copy(id = "ytm:s$it", title = "Song $it") }

            run(Command.AddSongs(songs, QueuePosition.END))
            eventually { casts.devices.value.isNotEmpty() && room.view().current != null }
            run(Command.SetOutput("cast:fake", active = true))

            // The device gets the current song with the next one in its own queue.
            eventually { device.queueTags.size == 2 }
            val first = room.view().current!!.itemId
            assertEquals(first, device.currentTag)

            // Next on the device: the room follows, and the device's queue gets the new neighbors.
            device.press(forward = true)
            eventually { room.view().current?.song?.id == "ytm:s2" }
            val second = room.view().current!!.itemId
            eventually { device.queueTags == listOf(first, second, room.view().next?.itemId) }
            // It wasn't loaded again: still one queue load.
            assertEquals(1, device.types(CastChannel.NS_MEDIA).count { it == "QUEUE_LOAD" })

            // Previous on the device: back to song 1.
            device.press(forward = false)
            eventually { room.view().current?.song?.id == "ytm:s1" }

            // A skip in the room (not on the device) still loads the song on the device.
            run(Command.Skip)
            eventually { device.currentTag == room.view().current?.itemId && room.view().current?.song?.id == "ytm:s2" }
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
