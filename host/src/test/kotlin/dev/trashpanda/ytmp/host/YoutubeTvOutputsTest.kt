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

class YoutubeTvOutputsTest {
    private val song = Song("ytm:dQw4w9WgXcQ", "Never Gonna Give You Up", listOf(ArtistRef(null, "Rick Astley")), null, 213_000, emptyList())

    @Test
    fun `a TV with the YouTube app is found and plays the room, following pause, volume, seeking and switching off`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        FakeYoutubeTv().use { tv ->
            tv.running = false
            val tvs = YoutubeTvOutputs(scope, loungeUrl = "http://127.0.0.1:${tv.port}/api/lounge")
            tvs.addLocation(tv.location)
            eventually { tvs.devices.value.isNotEmpty() }
            val device = tvs.devices.value.single()
            assertEquals("ytv:fake-tv", device.id)
            assertEquals("LeBeamski", device.name)
            assertEquals(OutputKind.YOUTUBE_TV, device.kind)

            val rooms = RoomManager({ "https://stream/$it" }, scope, outputs = tvs.devices)
            tvs.attach(rooms)
            val room = rooms.create("Party")
            val me = room.join(ClientMessage.Hello(PROTOCOL_VERSION, room.code, "Me", null, room.ownerToken), Outbox { })!!
            suspend fun run(command: Command) = room.handle(me, ClientMessage.CommandMessage("c", command))

            run(Command.AddSongs(listOf(song), QueuePosition.END))
            eventually { room.view().current != null }
            run(Command.SetOutput(device.id, active = true))

            // The YouTube app is started, the TV's own autoplay is turned off, and it plays the song's video.
            eventually { tv.videoId == "dQw4w9WgXcQ" && tv.state == 1 }
            assertTrue(tv.running)
            assertEquals(false, tv.autoplay)
            // Its own volume shows in the room.
            eventually { room.view().activeOutputs[device.id] == 0.3 }

            run(Command.Pause)
            eventually { tv.state == 2 }
            run(Command.SetOutputVolume(device.id, 0.25))
            eventually { tv.volume == 25 }
            run(Command.Play)
            eventually { tv.state == 1 }

            // Way off (someone seeked in the room): the TV is moved there.
            run(Command.Seek(120_000))
            eventually(30_000) { tv.position > 115_000 && kotlin.math.abs(tv.position - room.view().positionAt(System.currentTimeMillis())) < 3_000 }

            run(Command.SetOutput(device.id, active = false))
            eventually { tv.videoId == null }
            assertTrue("stopVideo" in tv.commands)
            // Its autoplay setting is back as it was.
            eventually { tv.autoplay }
        }
        scope.cancel()
    }

    @Test
    fun `when someone plays another video on the TV, the room lets go of it`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        FakeYoutubeTv().use { tv ->
            val tvs = YoutubeTvOutputs(scope, loungeUrl = "http://127.0.0.1:${tv.port}/api/lounge")
            tvs.addLocation(tv.location)
            val rooms = RoomManager({ "https://stream/$it" }, scope, outputs = tvs.devices)
            tvs.attach(rooms)
            val room = rooms.create("Party")
            val notices = mutableListOf<String>()
            val me = room.join(ClientMessage.Hello(PROTOCOL_VERSION, room.code, "Me", null, room.ownerToken), Outbox { m ->
                ((m as? ServerMessage.EventMessage)?.event as? Event.Notice)?.let { synchronized(notices) { notices += it.message } }
            })!!
            suspend fun run(command: Command) = room.handle(me, ClientMessage.CommandMessage("c", command))
            run(Command.AddSongs(listOf(song, song.copy(id = "ytm:9bZkp7q19f0", title = "Next")), QueuePosition.END))
            run(Command.SetOutput("ytv:fake-tv", active = true))
            eventually { tv.videoId == "dQw4w9WgXcQ" }
            // Give the driver a round to see it play.
            delay(3_000)

            tv.playOther("someoneElse")
            eventually { "ytv:fake-tv" !in room.view().activeOutputs }
            assertTrue(synchronized(notices) { notices.any { "LeBeamski is playing something else" in it } })
            // The room doesn't stop it, and doesn't take it back at the next song.
            run(Command.Skip())
            delay(3_000)
            assertEquals("someoneElse", tv.videoId)
            assertTrue("stopVideo" !in tv.commands)
        }
        scope.cancel()
    }

    @Test
    fun `only YouTube songs have a video`() {
        assertEquals("dQw4w9WgXcQ", YoutubeTvOutputs.videoId("ytm:dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", YoutubeTvOutputs.videoId("yt:dQw4w9WgXcQ"))
        assertNull(YoutubeTvOutputs.videoId("file:song.mp3"))
        assertNull(YoutubeTvOutputs.videoId("ytm:abc"))
    }

    private suspend fun eventually(timeoutMs: Long = 15_000, check: suspend () -> Boolean) = withTimeout(timeoutMs) {
        while (!check()) delay(100)
    }
}
