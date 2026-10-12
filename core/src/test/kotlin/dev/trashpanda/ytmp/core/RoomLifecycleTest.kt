package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.Event
import dev.trashpanda.ytmp.protocol.Migrant
import dev.trashpanda.ytmp.protocol.MigrationMode
import dev.trashpanda.ytmp.protocol.MigrationTarget
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.QueuePosition
import dev.trashpanda.ytmp.protocol.RejectReason
import dev.trashpanda.ytmp.protocol.RoomClone
import dev.trashpanda.ytmp.protocol.RoomVisibility
import dev.trashpanda.ytmp.protocol.ServerMessage
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Changing the owner, leaving, cloning and moving people to a clone (docs/features/room-lifecycle.md). */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomLifecycleTest {
    private fun song(n: Int) = Song("ytm:song$n", "Song $n", listOf(ArtistRef(null, "Artist")), null, 60_000, emptyList())

    /** Plays longer than these tests wait. */
    private fun longSong() = Song("ytm:long", "Long", listOf(ArtistRef(null, "Artist")), null, 3_600_000, emptyList())

    private class Client : Outbox {
        val messages = mutableListOf<ServerMessage>()
        val welcome get() = messages.filterIsInstance<ServerMessage.Welcome>().last()
        val id get() = welcome.participantId
        val results get() = messages.filterIsInstance<ServerMessage.Result>()
        val events get() = messages.filterIsInstance<ServerMessage.EventMessage>().map { it.event }
        override fun send(message: ServerMessage) {
            messages += message
        }
    }

    private val disowned = mutableListOf<Pair<String?, String?>>()

    private fun TestScope.newRoom(
        visibility: RoomVisibility = RoomVisibility.PUBLIC,
        ownerAccount: String? = null,
    ): Room = Room(
        "ABCD", "Party", "owner-token", visibility, { id -> "https://stream/$id" }, backgroundScope, ownerAccount = ownerAccount,
        onOwnerChanged = { account, syncId -> disowned += account to syncId },
    ) { testScheduler.currentTime }

    private suspend fun Room.join(
        name: String,
        owner: Boolean = false,
        account: AccountIdentity? = null,
        migrationKey: String? = null,
        guestToken: String? = null,
    ): Client {
        val client = Client()
        join(
            ClientMessage.Hello(PROTOCOL_VERSION, code, name, guestToken, if (owner) ownerToken else null, migrationKey = migrationKey),
            client, account = account,
        )
        return client
    }

    private suspend fun Room.run(client: Client, command: Command) =
        handle(client.id, ClientMessage.CommandMessage("c${client.messages.size}", command))

    private suspend fun Room.participants() = save().members

    @Test
    fun `the owner hands the room over and keeps the top role`() = runTest {
        val room = newRoom(ownerAccount = "anna@home")
        val oldSync = room.syncId
        val anna = room.join("Anna", account = AccountIdentity("anna@home", "Anna"))
        val ben = room.join("Ben", account = AccountIdentity("ben@home", "Ben"))
        val oldToken = room.ownerToken

        room.run(anna, Command.ChangeOwner(ben.id))

        assertNull(anna.results.last().error)
        val members = room.participants().associateBy { it.id }
        assertTrue(members.getValue(ben.id).isOwner)
        assertFalse(members.getValue(anna.id).isOwner)
        assertEquals("admin", members.getValue(anna.id).roleId)
        assertEquals("ben@home", room.ownerAccount)
        // Ben gets the new owner token; Anna's old one and her account don't count any more.
        assertEquals(room.ownerToken, ben.messages.filterIsInstance<ServerMessage.OwnerToken>().single().ownerToken)
        assertNotEquals(oldToken, room.ownerToken)
        assertNotEquals(oldSync, room.syncId)
        assertEquals(listOf<Pair<String?, String?>>("anna@home" to oldSync), disowned)
        val again = room.join("Anna", account = AccountIdentity("anna@home", "Anna"))
        assertFalse(room.participants().single { it.id == again.id }.isOwner)
    }

    @Test
    fun `only the owner can hand the room over`() = runTest {
        val room = newRoom()
        room.join("Anna", owner = true)
        val ben = room.join("Ben")
        val cleo = room.join("Cleo")

        room.run(ben, Command.ChangeOwner(cleo.id))

        assertEquals(ErrorCode.PERMISSION_DENIED, ben.results.last().error?.code)
    }

    @Test
    fun `an owner who leaves without picking hands over to the longest-present admin`() = runTest {
        val room = newRoom()
        val anna = room.join("Anna", owner = true)
        val ben = room.join("Ben")
        advanceTimeBy(1_000)
        val cleo = room.join("Cleo")
        room.run(anna, Command.AssignRole(cleo.id, "admin"))
        advanceTimeBy(1_000)
        val dan = room.join("Dan")
        room.run(anna, Command.AssignRole(dan.id, "admin"))

        room.run(anna, Command.LeaveRoom())

        val members = room.participants().associateBy { it.id }
        assertFalse(anna.id in members)
        // Ben is here longest, but isn't an admin; Cleo came before Dan.
        assertTrue(members.getValue(cleo.id).isOwner)
        assertFalse(members.getValue(ben.id).isOwner)
        assertTrue(cleo.events.any { it is Event.ParticipantLeft && it.participantId == anna.id })
    }

    @Test
    fun `an owner who leaves can pick who takes over`() = runTest {
        val room = newRoom()
        val anna = room.join("Anna", owner = true)
        val ben = room.join("Ben")

        room.run(anna, Command.LeaveRoom(newOwnerId = ben.id))

        assertTrue(room.participants().single { it.id == ben.id }.isOwner)
    }

    @Test
    fun `an owner can leave and stay the owner`() = runTest {
        val room = newRoom()
        val anna = room.join("Anna", owner = true)
        val ben = room.join("Ben")
        room.run(anna, Command.AssignRole(ben.id, "admin"))

        room.run(anna, Command.LeaveRoom(stayOwner = true))

        // Ben is an admin, but Anna kept the room.
        assertTrue(room.participants().none { it.isOwner })
        val back = room.join("Anna", owner = true)
        assertTrue(room.participants().single { it.id == back.id }.isOwner)
    }

    @Test
    fun `an owner who leaves with no admin around stays the owner`() = runTest {
        val room = newRoom()
        val anna = room.join("Anna", owner = true)
        room.join("Ben")
        val token = room.ownerToken

        room.run(anna, Command.LeaveRoom())

        assertTrue(room.participants().none { it.isOwner })
        assertEquals(token, room.ownerToken)
        val back = room.join("Anna", owner = true)
        assertTrue(room.participants().single { it.id == back.id }.isOwner)
    }

    @Test
    fun `leaving ends the app's player connection too`() = runTest {
        val room = newRoom()
        val ben = room.join("Ben")
        val player = Client()
        room.attach(ClientMessage.Attach(PROTOCOL_VERSION, "ABCD", ben.welcome.guestToken), player)

        room.run(ben, Command.LeaveRoom())

        assertEquals(RejectReason.LEFT, player.messages.filterIsInstance<ServerMessage.Rejected>().single().reason)
        assertTrue(room.participants().isEmpty())
    }

    @Test
    fun `a clone starts with the queue, roles and position, paused`() = runTest {
        val old = newRoom()
        val anna = old.join("Anna", owner = true)
        old.run(anna, Command.AddSongs(listOf(song(1), song(2), song(3)), QueuePosition.END))
        runCurrent()
        old.run(anna, Command.CreateRole("Bouncer", "#123456", emptyList()))
        advanceTimeBy(20_000)
        val saved = old.save()
        val clone = RoomClone(saved.queue, saved.history, saved.current, saved.positionMs, roles = saved.roles!!, settings = saved.settings)

        val manager = RoomManager({ id -> "https://stream/$id" }, backgroundScope, clock = { testScheduler.currentTime })
        val copy = manager.create("Party (copy)", clone = clone)
        val state = copy.save()

        assertEquals("ytm:song1", state.current?.song?.id)
        assertEquals(listOf("ytm:song2", "ytm:song3"), state.queue.map { it.song.id })
        assertTrue(state.positionMs in 19_000..21_000)
        assertFalse(state.playing)
        assertEquals(saved.roles!!.map { it.name }, state.roles!!.map { it.name })
    }

    @Test
    fun `people moved to a clone get their role, name and id back`() = runTest {
        val old = newRoom()
        val anna = old.join("Anna", owner = true)
        val ben = old.join("Ben")
        old.run(anna, Command.AssignRole(ben.id, "dj"))
        old.run(ben, Command.AddSongs(listOf(song(1)), QueuePosition.END))
        runCurrent()
        val benTag = old.join("x", guestToken = ben.welcome.guestToken).welcome.state.participants.single { it.id == ben.id }.tag
        val migrants = listOf(Migrant(benTag, ben.id, "Ben", "dj"))

        val copy = newRoom()
        copy.startFrom(RoomClone(emptyList(), old.save().history, old.save().current, migrants = migrants))
        // Someone else with Ben's tag (it's public) but not his key gets nothing.
        val sneaky = copy.join("Ben", migrationKey = benTag)
        val back = copy.join("Ben", migrationKey = ben.welcome.migrationKey)

        val members = copy.participants().associateBy { it.id }
        assertEquals("listener", members.getValue(sneaky.id).roleId)
        assertEquals(ben.id, back.id)
        assertEquals("dj", members.getValue(back.id).roleId)
        // His song is still his: he may remove it with only "remove own".
        assertEquals(ben.id, copy.save().current?.addedBy)
        // The key works once.
        assertNotEquals(ben.id, copy.join("Ben", migrationKey = ben.welcome.migrationKey).id)
    }

    @Test
    fun `an invite shows to everyone, and can be taken back`() = runTest {
        val room = newRoom()
        val anna = room.join("Anna", owner = true)
        val ben = room.join("Ben")
        val target = MigrationTarget("https://ytmp.example.com/room/WXYZ", "WXYZ", "Party (copy)")

        room.run(ben, Command.SendMigration(target, MigrationMode.INVITE))
        assertEquals(ErrorCode.PERMISSION_DENIED, ben.results.last().error?.code)

        room.run(anna, Command.SendMigration(target, MigrationMode.INVITE))
        val offer = ben.events.filterIsInstance<Event.MigrationChanged>().last().migration
        assertEquals(target, offer?.target)
        assertEquals("Anna", offer?.fromName)
        assertEquals(target, room.join("Cleo").welcome.state.migration?.target)

        room.run(anna, Command.CancelMigration)
        assertNull(ben.events.filterIsInstance<Event.MigrationChanged>().last().migration)
    }

    @Test
    fun `a forced move sends everyone else away and stops the music`() = runTest {
        val room = newRoom()
        val anna = room.join("Anna", owner = true)
        val ben = room.join("Ben")
        room.run(anna, Command.AddSongs(listOf(song(1)), QueuePosition.END))
        runCurrent()
        val url = "https://ytmp.example.com/room/WXYZ"

        room.run(anna, Command.SendMigration(MigrationTarget(url, "WXYZ", "Copy"), MigrationMode.FORCED))

        val rejected = ben.messages.filterIsInstance<ServerMessage.Rejected>().single()
        assertEquals(RejectReason.MIGRATED, rejected.reason)
        assertEquals(url, rejected.movedTo)
        assertEquals(listOf(anna.id), room.participants().map { it.id })
        assertFalse(room.save().playing)
    }

    @Test
    fun `a migration must point at a room link`() = runTest {
        val room = newRoom()
        val anna = room.join("Anna", owner = true)

        room.run(anna, Command.SendMigration(MigrationTarget("javascript:alert(1)", "WXYZ", "x"), MigrationMode.INVITE))

        assertEquals(ErrorCode.INVALID, anna.results.last().error?.code)
    }

    @Test
    fun `a party room without its admins for a while says so`() = runTest {
        val room = newRoom()
        val anna = room.join("Anna", owner = true)
        val ben = room.join("Ben")
        room.disconnect(anna.id, anna)

        advanceTimeBy(4 * 60_000L)
        room.checkAdminless(5 * 60_000L)
        assertFalse(room.save().let { ben.events.any { it is Event.AdminlessChanged } })
        advanceTimeBy(2 * 60_000L)
        room.checkAdminless(5 * 60_000L)
        assertEquals(true, ben.events.filterIsInstance<Event.AdminlessChanged>().last().adminless)

        // The owner is back.
        room.join("Anna", owner = true, guestToken = anna.welcome.guestToken)
        assertEquals(false, ben.events.filterIsInstance<Event.AdminlessChanged>().last().adminless)
    }

    @Test
    fun `the music pauses a minute after everyone went`() = runTest {
        val room = newRoom()
        val anna = room.join("Anna", owner = true)
        room.run(anna, Command.AddSongs(listOf(longSong()), QueuePosition.END))
        runCurrent()
        room.disconnect(anna.id, anna)

        // A reload: back within the minute, still playing.
        advanceTimeBy(30_000)
        room.join("Anna", owner = true, guestToken = anna.welcome.guestToken).also { room.disconnect(it.id, it) }
        advanceTimeBy(50_000)
        assertTrue(room.save().playing)

        advanceTimeBy(20_000)
        assertFalse(room.save().playing)
    }

    @Test
    fun `the last one to leave pauses the music at once`() = runTest {
        val room = newRoom()
        val anna = room.join("Anna", owner = true)
        room.run(anna, Command.AddSongs(listOf(longSong()), QueuePosition.END))
        runCurrent()

        room.run(anna, Command.LeaveRoom(stayOwner = true))
        runCurrent()

        assertFalse(room.save().playing)
    }

    @Test
    fun `music on a speaker keeps playing when everyone went`() = runTest {
        val room = newRoom()
        room.setAvailableOutputs(listOf(OutputDevice("kitchen", "Kitchen", dev.trashpanda.ytmp.protocol.OutputKind.SONOS)))
        val anna = room.join("Anna", owner = true)
        room.run(anna, Command.AddSongs(listOf(longSong()), QueuePosition.END))
        room.run(anna, Command.SetOutput("kitchen", true))
        runCurrent()

        room.disconnect(anna.id, anna)
        advanceTimeBy(5 * 60_000L)

        assertTrue(room.save().playing)
    }

    @Test
    fun `an always-on room isn't deleted for being empty`() = runTest {
        val manager = RoomManager(
            { id -> "https://stream/$id" }, backgroundScope, clock = { testScheduler.currentTime },
            timeouts = RoomTimeouts(checkInterval = kotlin.time.Duration.parse("1m")),
        )
        manager.startCleanup()
        val kept = manager.create("Home")
        val gone = manager.create("Party")
        val owner = Client()
        kept.join(ClientMessage.Hello(PROTOCOL_VERSION, kept.code, "Anna", null, kept.ownerToken), owner)
        kept.handle(owner.id, ClientMessage.CommandMessage("c", Command.UpdateSettings(alwaysOn = true)))
        kept.disconnect(owner.id, owner)

        advanceTimeBy(3 * 3_600_000L)

        assertNotNull(manager[kept.code])
        assertNull(manager[gone.code])
    }
}
