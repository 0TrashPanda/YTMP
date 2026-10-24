package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.ErrorInfo
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.Participant
import dev.trashpanda.ytmp.protocol.RoomVisibility
import dev.trashpanda.ytmp.protocol.ServerMessage
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The volume of your devices: each says its own, and you can set it from another of yours. */
class DeviceVolumeTest {
    private class Client : Outbox {
        val messages = mutableListOf<ServerMessage>()
        lateinit var welcome: ServerMessage.Welcome
        val results = mutableMapOf<String, ErrorInfo?>()

        override fun send(message: ServerMessage) {
            messages += message
            if (message is ServerMessage.Welcome) welcome = message
            if (message is ServerMessage.Result) results[message.id] = message.error
        }

        val id get() = welcome.participantId
    }

    private fun TestScope.newRoom() = Room("ABCD", "Party", "owner-token", RoomVisibility.PUBLIC, { "https://s/$it" }, backgroundScope) { testScheduler.currentTime }

    private suspend fun Room.connect(name: String, device: String? = null, account: AccountIdentity? = null): Client {
        val c = Client()
        join(ClientMessage.Hello(PROTOCOL_VERSION, "ABCD", name, null, null, device = device), c, account = account)
        return c
    }

    private var n = 0

    private suspend fun Room.run(c: Client, command: Command): ErrorInfo? {
        val id = "c${n++}"
        handle(c.id, ClientMessage.CommandMessage(id, command))
        return c.results.getValue(id)
    }

    private suspend fun Room.participant(c: Client, id: String): Participant {
        handle(c.id, ClientMessage.RequestSnapshot)
        return (c.messages.last() as ServerMessage.Snapshot).state.participants.single { it.id == id }
    }

    private val anna = AccountIdentity("anna@x", "Anna")

    @Test
    fun `a device says its own volume, which everyone sees with its name`() = runTest {
        val room = newRoom()
        val phone = room.connect("Anna", device = "Pixel 8", account = anna)
        assertNull(room.run(phone, Command.SetDeviceVolume(0.4)))

        val me = room.participant(phone, phone.id)
        assertEquals("Pixel 8", me.device)
        assertEquals(0.4, me.volume)
        // Its own volume isn't a change from elsewhere.
        assertEquals(0, me.volumeChanges)
    }

    @Test
    fun `you set the volume of another of your devices`() = runTest {
        val room = newRoom()
        val phone = room.connect("Anna", device = "Pixel 8", account = anna)
        val laptop = room.connect("Anna", device = "Firefox on Linux", account = anna)
        room.run(phone, Command.SetDeviceVolume(0.4))

        assertNull(room.run(laptop, Command.SetDeviceVolume(0.7, participantId = phone.id)))
        val seen = room.participant(phone, phone.id)
        assertEquals(0.7, seen.volume)
        assertEquals(1, seen.volumeChanges)

        // Out of range is the nearest end.
        room.run(laptop, Command.SetDeviceVolume(3.0, participantId = phone.id))
        assertEquals(1.0, room.participant(phone, phone.id).volume)
        assertEquals(2, room.participant(phone, phone.id).volumeChanges)
    }

    @Test
    fun `only your own devices`() = runTest {
        val room = newRoom()
        val annasPhone = room.connect("Anna", account = anna)
        val ben = room.connect("Ben", account = AccountIdentity("ben@x", "Ben"))
        val guest = room.connect("Guest")
        val otherGuest = room.connect("Guest 2")

        assertEquals(ErrorCode.PERMISSION_DENIED, room.run(ben, Command.SetDeviceVolume(0.1, annasPhone.id))?.code)
        // Guests have no account: no devices of theirs to recognise.
        assertEquals(ErrorCode.PERMISSION_DENIED, room.run(guest, Command.SetDeviceVolume(0.1, otherGuest.id))?.code)
        assertEquals(ErrorCode.NOT_FOUND, room.run(ben, Command.SetDeviceVolume(0.1, "nobody"))?.code)
        assertEquals(ErrorCode.INVALID, room.run(ben, Command.SetDeviceVolume(Double.NaN))?.code)
        assertNull(room.participant(annasPhone, annasPhone.id).volume)
    }

    @Test
    fun `you switch "Play here" on and off on another of your devices that allows it`() = runTest {
        val room = newRoom()
        val phone = room.connect("Anna", device = "Pixel 8", account = anna)
        val laptop = room.connect("Anna", device = "Firefox on Linux", account = anna)

        // The laptop doesn't say it allows it (an older version, or its setting is off).
        assertEquals(ErrorCode.PERMISSION_DENIED, room.run(phone, Command.SetDeviceListening(laptop.id, true))?.code)

        // It allows it, but nobody clicked in it yet: a browser can't start sound then.
        assertNull(room.run(laptop, Command.SetRemotePlay(allowed = true, canStart = false)))
        assertEquals(ErrorCode.INVALID, room.run(phone, Command.SetDeviceListening(laptop.id, true))?.code)
        // Off works anyway.
        assertNull(room.run(phone, Command.SetDeviceListening(laptop.id, false)))

        room.run(laptop, Command.SetRemotePlay(allowed = true, canStart = true))
        assertNull(room.run(phone, Command.SetDeviceListening(laptop.id, true)))
        val seen = room.participant(laptop, laptop.id)
        assertEquals(true, seen.remotePlay)
        assertEquals(true, seen.listenRequest)
        assertEquals(2, seen.listenRequests)
    }

    @Test
    fun `nobody else switches your device on`() = runTest {
        val room = newRoom()
        val laptop = room.connect("Anna", account = anna)
        val ben = room.connect("Ben", account = AccountIdentity("ben@x", "Ben"))
        room.run(laptop, Command.SetRemotePlay(allowed = true, canStart = true))

        assertEquals(ErrorCode.PERMISSION_DENIED, room.run(ben, Command.SetDeviceListening(laptop.id, true))?.code)
        assertEquals(ErrorCode.PERMISSION_DENIED, room.run(laptop, Command.SetDeviceListening(laptop.id, true))?.code)
        assertEquals(ErrorCode.NOT_FOUND, room.run(ben, Command.SetDeviceListening("nobody", true))?.code)
        assertEquals(0, room.participant(laptop, laptop.id).listenRequests)
    }
}
