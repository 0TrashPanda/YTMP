package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.host.ApiException
import dev.trashpanda.ytmp.protocol.HostKind
import dev.trashpanda.ytmp.protocol.RoomCopyRequest
import dev.trashpanda.ytmp.protocol.RoomHeartbeatRequest
import dev.trashpanda.ytmp.protocol.RoomHome
import dev.trashpanda.ytmp.protocol.RoomOpenMode
import dev.trashpanda.ytmp.protocol.RoomVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RoomHubTest {
    private val anna = "anna@ytmp.test"
    private val scope = CoroutineScope(SupervisorJob())
    private val server = RoomManager({ "https://stream/$it" }, scope)
    private val phone = RoomManager({ "https://stream/$it" }, scope)
    private val hub = RoomHub(
        Database.open(DatabaseConfig(path = Files.createTempDirectory("ytmp").resolve("ytmp.db").toString())),
        server, issuer = "ytmp.test", ownUrl = "https://ytmp.test", serverName = "ytmp.test",
        verify = { null }, openSetting = { RoomOpenMode.ASK },
    )
    private val pixel = RoomHome(HostKind.PHONE, "pixel-1", "Pixel 7", "http://192.168.1.23:8765")

    @Test
    fun `a phone room is copied to the server, moves there, and the phone hears it moved`() = runBlocking {
        val room = phone.create("Anna's room", RoomVisibility.PRIVATE, ownerAccount = anna)
        val syncId = room.syncId!!
        assertNull(hub.put(anna, syncId, RoomCopyRequest(pixel, room.epoch, room.save().toJson())))

        val listed = hub.list(anna, "anna").rooms.single()
        assertEquals("Pixel 7", listed.home.hostName)
        assertEquals(room.code, listed.code)

        // Opened on the server, moved there.
        hub.take(anna, syncId, RoomHome(HostKind.SERVER, RoomHub.SERVER_ID, "ytmp.test", "https://ytmp.test"))
        val here = assertNotNull(server.bySyncId(syncId))
        assertEquals(1, here.epoch)
        assertEquals("https://ytmp.test", hub.list(anna, "anna").rooms.single().home.url)

        // The phone checks in, or saves, with the old epoch: it's told where the room is now.
        val moved = hub.heartbeat(anna, RoomHeartbeatRequest(pixel, mapOf(syncId to 0))).moved.single()
        assertEquals("https://ytmp.test/room/${here.code}", moved.url)
        assertEquals(moved, hub.put(anna, syncId, RoomCopyRequest(pixel, 0, room.save().toJson())))
    }

    @Test
    fun `a server room moves to the phone and closes on the server`() = runBlocking {
        val room = server.create("Party", ownerAccount = anna)
        val syncId = room.syncId!!

        val snapshot = hub.take(anna, syncId, pixel)
        assertEquals(1, snapshot.epoch)
        assertNull(server.bySyncId(syncId))
        assertEquals("http://192.168.1.23:8765/room/${room.code}", server.movedTo(room.code))

        // The phone runs it now; its saves are taken.
        val onPhone = phone.adopt(dev.trashpanda.ytmp.core.SavedRoom.fromJson(snapshot.room), snapshot.epoch)
        assertNull(hub.put(anna, syncId, RoomCopyRequest(pixel, onPhone.epoch, onPhone.save().toJson())))
        assertEquals("Pixel 7", hub.list(anna, "anna").rooms.single().home.hostName)
    }

    @Test
    fun `only the owner's rooms`() = runBlocking {
        val room = phone.create("Bob's", ownerAccount = "bob@ytmp.test")
        assertFailsWith<ApiException> { hub.put(anna, room.syncId!!, RoomCopyRequest(pixel, 0, room.save().toJson())) }
        assertEquals(emptyList(), hub.list(anna, "anna").rooms)
    }
}
