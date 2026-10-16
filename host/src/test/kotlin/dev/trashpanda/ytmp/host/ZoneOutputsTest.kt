package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.Outbox
import dev.trashpanda.ytmp.core.Room
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZoneOutputsTest {
    private val song = Song("ytm:abc", "One More Time", listOf(ArtistRef(null, "Daft Punk")), null, 320_000, emptyList())

    /** A host with the MPD and the amp's zones, and a way to make rooms. */
    private class Setup(val scope: CoroutineScope, val mpd: FakeMpd, val amp: FakeAmp, audioBase: String = "http://127.0.0.1:8765") {
        val mpds = MpdOutputs(scope) { audioBase }
        val zones = ZoneOutputs(scope, { audioBase }, mpds)
        val rooms = RoomManager(
            { "https://stream/$it" },
            scope,
            outputs = combine(mpds.devices, zones.devices) { a, b -> a + b }.stateIn(scope, SharingStarted.Eagerly, emptyList()),
        )
        val notices = mutableListOf<String>()
        val zithoek = ZoneOutputs.idFor(ZoneOutputs.Player("127.0.0.1", mpd.port), "1")
        val keuken = ZoneOutputs.idFor(ZoneOutputs.Player("127.0.0.1", mpd.port), "5")

        init {
            mpds.add(MpdAddress(MpdOutputs.idFor("127.0.0.1", mpd.port), "Pi", "127.0.0.1", mpd.port))
            mpds.attach(rooms)
            zones.attach(rooms)
            zones.addSource("test", "http://127.0.0.1:${amp.port}/ytmp-zones.json")
        }

        suspend fun join(room: Room): suspend (Command) -> Unit {
            val me = room.join(ClientMessage.Hello(PROTOCOL_VERSION, room.code, "Me", null, room.ownerToken), Outbox { m ->
                ((m as? ServerMessage.EventMessage)?.event as? Event.Notice)?.let { synchronized(notices) { notices += it.message } }
            })!!
            return { command -> room.handle(me, ClientMessage.CommandMessage("c", command)) }
        }
    }

    private fun withSetup(block: suspend Setup.() -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        FakeMpd().use { mpd ->
            FakeAmp().use { amp ->
                amp.zones[1] = FakeAmp.ZoneState(vchan = 1, muted = 0, volume = 39)
                amp.zones[5] = FakeAmp.ZoneState(vchan = 1, muted = 1, volume = 34)
                amp.zones[7] = FakeAmp.ZoneState(vchan = 1, muted = 0, volume = 30)
                amp.zonesFile = amp.zonesFor(mpd.port, mapOf(1 to "Zithoek", 5 to "Keuken", 7 to "Koptelefoon"), hidden = setOf(7))
                Setup(scope, mpd, amp).block()
            }
        }
        scope.cancel()
    }

    @Test
    fun `zones play the room on one player, with their own volume, and get back what they had`() = withSetup {
        eventually { zones.devices.value.size == 2 }
        // Koptelefoon is hidden in the file.
        assertEquals(listOf("Zithoek", "Keuken"), zones.devices.value.map { it.name })
        assertEquals(setOf(OutputKind.ZONE), zones.devices.value.map { it.kind }.toSet())
        // The Pi's MPD is offered as its zones, not by itself.
        eventually { mpds.devices.value.isEmpty() }

        val room = rooms.create("Party")
        val run = join(room)
        run(Command.AddSongs(listOf(song), QueuePosition.END))
        eventually { room.view().current != null }
        run(Command.SetOutput(zithoek, active = true))

        // Switched to the Pi and unmuted on the amp; the MPD plays the room.
        eventually { amp.zones[1] == FakeAmp.ZoneState(2, 0, 39) }
        eventually { mpd.state == "play" }
        assertEquals("http://127.0.0.1:8765/api/audio/ytm%3Aabc", mpd.file)
        // The zone's own volume on the amp (39 of 50) is the room's slider; the player plays at full volume.
        eventually { room.view().activeOutputs[zithoek] == 0.78 }
        eventually { mpd.volume == 100 }

        // A second zone: switched on too, and the MPD doesn't load the song again.
        run(Command.SetOutput(keuken, active = true))
        eventually { amp.zones[5]?.let { it.vchan == 2 && it.muted == 0 } == true }
        delay(2_500)
        assertEquals(1, mpd.commands.count { it.startsWith("addid") })

        run(Command.SetOutputVolume(keuken, 0.5))
        eventually { amp.zones[5]?.volume == 25 }
        // Changed on the amp itself: the room follows.
        amp.zones[1] = amp.zones[1]!!.copy(volume = 20)
        eventually { room.view().activeOutputs[zithoek] == 0.4 }

        // Off: back to what it had (Digibox, muted), and the other zone plays on.
        run(Command.SetOutput(keuken, active = false))
        eventually { amp.zones[5] == FakeAmp.ZoneState(1, 1, 25) }
        assertEquals("play", mpd.state)
        run(Command.SetOutput(zithoek, active = false))
        eventually { amp.zones[1] == FakeAmp.ZoneState(1, 0, 20) }
        eventually { mpd.state == "stop" && mpd.file == null }
    }

    @Test
    fun `a zone switched to something else on the amp leaves the room as it is`() = withSetup {
        eventually { zones.devices.value.size == 2 }
        val room = rooms.create("Party")
        val run = join(room)
        run(Command.AddSongs(listOf(song), QueuePosition.END))
        run(Command.SetOutput(zithoek, active = true))
        eventually { mpd.state == "play" && amp.zones[1]?.vchan == 2 }
        delay(4_500)

        // Someone picks the TV for that room on the amp.
        amp.zones[1] = amp.zones[1]!!.copy(vchan = 1)
        val writesBefore = amp.writes.size
        eventually { zithoek !in room.view().activeOutputs }
        assertTrue(synchronized(notices) { notices.any { "Zithoek is playing something else" in it } })
        // Nothing switched back on the amp; the player, with no zone left, stops.
        eventually { mpd.state == "stop" }
        assertEquals(writesBefore, amp.writes.size)
        assertEquals(1, amp.zones[1]?.vchan)
    }

    @Test
    fun `the room that switched on a zone last gets the player`() = withSetup {
        eventually { zones.devices.value.size == 2 }
        val first = rooms.create("First")
        val runFirst = join(first)
        runFirst(Command.AddSongs(listOf(song), QueuePosition.END))
        runFirst(Command.SetOutput(zithoek, active = true))
        eventually { mpd.state == "play" && amp.zones[1]?.vchan == 2 }

        val second = rooms.create("Second")
        val runSecond = join(second)
        runSecond(Command.AddSongs(listOf(song.copy(id = "ytm:def", title = "Other")), QueuePosition.END))
        runSecond(Command.SetOutput(keuken, active = true))

        // The second room plays there now; the first room's zone goes back to what it had.
        eventually { mpd.file == "http://127.0.0.1:8765/api/audio/ytm%3Adef" }
        eventually { zithoek !in first.view().activeOutputs }
        eventually { amp.zones[1] == FakeAmp.ZoneState(1, 0, 39) }
        assertEquals(2, amp.zones[5]?.vchan)
        delay(2_500)
        assertEquals("http://127.0.0.1:8765/api/audio/ytm%3Adef", mpd.file)
    }

    @Test
    fun `a host that takes the player over puts the house back as it was before the first`() = withSetup {
        // Another host (say a second phone) on the same Pi and amp, which answers the handover over HTTP.
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val other = Setup(scope, mpd, amp, audioBase = "http://127.0.0.1:${server.address.port}")
        server.createContext("/api/zones/handover") { exchange ->
            val query = exchange.requestURI.query.split('&').associate { it.substringBefore('=') to it.substringAfter('=') }
            val body = zoneJson.encodeToString(ZoneHandover.serializer(), other.zones.handover(ZoneOutputs.Player(query["host"]!!, query["port"]!!.toInt()))).toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            eventually { zones.devices.value.size == 2 && other.zones.devices.value.size == 2 }
            // The other host plays on both zones first.
            val first = other.rooms.create("First")
            val runFirst = other.join(first)
            runFirst(Command.AddSongs(listOf(song), QueuePosition.END))
            runFirst(Command.SetOutput(zithoek, active = true))
            runFirst(Command.SetOutput(keuken, active = true))
            eventually { mpd.state == "play" && amp.zones[1]?.vchan == 2 && amp.zones[5]?.vchan == 2 }

            // This host takes Keuken.
            val second = rooms.create("Second")
            val runSecond = join(second)
            runSecond(Command.AddSongs(listOf(song.copy(id = "ytm:def", title = "Other")), QueuePosition.END))
            runSecond(Command.SetOutput(keuken, active = true))
            eventually { mpd.file == "http://127.0.0.1:8765/api/audio/ytm%3Adef" }
            // Zithoek, which it doesn't want, goes back to what it had before the other host (Digibox, unmuted).
            eventually { amp.zones[1] == FakeAmp.ZoneState(1, 0, 39) }
            eventually { first.view().activeOutputs.isEmpty() }
            assertEquals(2, amp.zones[5]?.vchan)

            // Done: Keuken too goes back to what it had before the other host (Digibox, muted).
            runSecond(Command.SetOutput(keuken, active = false))
            eventually { amp.zones[5]?.let { it.vchan == 1 && it.muted == 1 } == true }
            eventually { mpd.state == "stop" }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `the building blocks`() {
        val config = zoneJson.decodeFromString<ZoneConfig>(FakeAmp().use { it.zonesFor(6600, mapOf(5 to "Keuken")) })
        val control = ZoneControl("5", config.amp)
        val base = config.amp.base.replace("{zone}", "5")
        assertEquals("$base&settings=?", control.stateUrl())
        assertEquals(listOf("$base&vchan=2", "$base&muted=0"), control.onUrls())
        assertEquals(listOf("$base&muted=1", "$base&vchan=1", "$base&muted=1"), control.offUrls(mapOf("vchan" to "1", "muted" to "1")))
        // Nothing saved (it played the player already): only what needs no saved value.
        assertEquals(listOf("$base&muted=1"), control.offUrls(emptyMap()))
        assertEquals("$base&volume=25", control.volumeUrl(0.5))

        val state = control.parseState("/audio_set?output=5&settings={\"channel\":3,\"vchan\":2,\"volume\":39,\"muted\":0}")
        assertTrue(control.isOurs(state))
        assertFalse(control.isOurs(JsonObject(state + ("muted" to JsonPrimitive(1)))))
        assertEquals(0.78, control.volumeOf(state))
        assertEquals(mapOf("vchan" to "2", "muted" to "0"), control.saved(state))
        assertFailsWith<ZoneConfigException> { control.parseState("not it") }

        assertEquals("127.0.0.1", validateZones(config, "127.0.0.1"))
        fun invalid(edit: (ZoneConfig) -> ZoneConfig) = assertFailsWith<ZoneConfigException> { validateZones(edit(config), "127.0.0.1") }
        invalid { it.copy(amp = it.amp.copy(base = "http://8.8.8.8/audio_set?output={zone}")) }
        invalid { it.copy(player = it.player.copy(host = "8.8.8.8")) }
        invalid { it.copy(amp = it.amp.copy(on = listOf("{base}&vchan={input}"))) }
        invalid { it.copy(amp = it.amp.copy(off = listOf("{base}&vchan={channel}"))) }
        invalid { it.copy(amp = it.amp.copy(state = null)) }
        invalid { it.copy(zones = emptyList()) }
        assertTrue(isLocal("192.168.0.156") && isLocal("10.0.0.1") && isLocal("fd00::1") && !isLocal("1.1.1.1"))

        // The example in the repo is a valid zones file.
        val example = zoneJson.decodeFromString<ZoneConfig>(File("../deploy/zones/ytmp-zones.json").readText())
        assertEquals("192.168.0.50", validateZones(example, "192.168.0.50"))
    }

    private suspend fun eventually(check: suspend () -> Boolean) = withTimeout(15_000) {
        while (!check()) delay(100)
    }
}
