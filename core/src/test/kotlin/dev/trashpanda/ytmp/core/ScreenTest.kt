package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.ErrorInfo
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.QueuePosition
import dev.trashpanda.ytmp.protocol.RoomState
import dev.trashpanda.ytmp.protocol.RoomVisibility
import dev.trashpanda.ytmp.protocol.ServerMessage
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Party screens: a TV someone in the room linked (docs/features/ui.md#party-screen). */
class ScreenTest {
    private val song = Song("ytm:1", "Song", listOf(ArtistRef(null, "Artist")), null, 60_000, emptyList())

    private class Client : Outbox {
        val messages = mutableListOf<ServerMessage>()
        val results = mutableMapOf<String, ErrorInfo?>()
        override fun send(message: ServerMessage) {
            messages += message
            if (message is ServerMessage.Result) results[message.id] = message.error
        }
        val welcome get() = messages.filterIsInstance<ServerMessage.Welcome>().last()
    }

    private fun TestScope.newRoom(visibility: RoomVisibility = RoomVisibility.PUBLIC) =
        Room("ABCD", "Party", "owner-token", visibility, { "https://s/$it" }, backgroundScope) { testScheduler.currentTime }

    private suspend fun Room.connect(name: String, owner: Boolean = false, guestToken: String? = null, local: Boolean = true): Client {
        val c = Client()
        join(ClientMessage.Hello(PROTOCOL_VERSION, "ABCD", name, guestToken, if (owner) "owner-token" else null), c, local = local)
        return c
    }

    private suspend fun Room.state(c: Client): RoomState {
        handle(c.welcome.participantId, ClientMessage.RequestSnapshot)
        return (c.messages.last() as ServerMessage.Snapshot).state
    }

    @Test
    fun `a screen watches, even a solo room from another device, and can't do anything`() = runTest {
        val room = newRoom(RoomVisibility.PRIVATE)
        val owner = room.connect("Owner", owner = true)
        val token = assertNotNull(room.addScreen(owner.welcome.guestToken))
        assertTrue(room.isScreen(token))

        // A stranger from another device is turned away; the screen isn't.
        val stranger = room.connect("Stranger", local = false)
        assertTrue(stranger.messages.single() is ServerMessage.Rejected)
        val tv = room.connect("TV", guestToken = token, local = false)
        val me = tv.welcome.state.participants.single { it.id == tv.welcome.participantId }
        assertTrue(me.screen)
        assertEquals("Party screen", me.name)

        room.handle(me.id, ClientMessage.CommandMessage("c", Command.AddSongs(listOf(song), QueuePosition.END)))
        assertEquals(ErrorCode.PERMISSION_DENIED, tv.results["c"]?.code)
    }

    @Test
    fun `only people here can add a screen`() = runTest {
        val room = newRoom()
        room.connect("Owner", owner = true)
        assertNull(room.addScreen("not-a-guest-token"))
    }

    @Test
    fun `a screen isn't a person for votes, and stays a screen after a restart`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        room.connect("Guest")
        room.handle(owner.welcome.participantId, ClientMessage.CommandMessage("s", Command.UpdateSettings(voteSkip = true, voteSkipPercent = 100)))
        room.handle(owner.welcome.participantId, ClientMessage.CommandMessage("a", Command.AddSongs(listOf(song), QueuePosition.END)))
        val token = room.addScreen(owner.welcome.guestToken)!!
        room.connect("TV", guestToken = token)
        assertEquals(2, room.state(owner).skipVotes?.needed)

        val restored = Room.restore(SavedRoom.fromJson(room.save().toJson()), { "https://s/$it" }, backgroundScope)
        assertTrue(restored.isScreen(token))
        val tv = restored.connect("TV", guestToken = token, local = false)
        assertTrue(tv.welcome.state.participants.single { it.id == tv.welcome.participantId }.screen)
    }
}
