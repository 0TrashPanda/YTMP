package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.ErrorInfo
import dev.trashpanda.ytmp.protocol.Event
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.Permission
import dev.trashpanda.ytmp.protocol.QueuePosition
import dev.trashpanda.ytmp.protocol.RejectReason
import dev.trashpanda.ytmp.protocol.Role
import dev.trashpanda.ytmp.protocol.RoomState
import dev.trashpanda.ytmp.protocol.RoomVisibility
import dev.trashpanda.ytmp.protocol.ServerMessage
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RolesTest {
    private val song = Song("ytm:1", "Song", listOf(ArtistRef(null, "Artist")), null, 60_000, emptyList())

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
        val events get() = messages.filterIsInstance<ServerMessage.EventMessage>().map { it.event }
    }

    private fun TestScope.newRoom() = Room("ABCD", "Party", "owner-token", RoomVisibility.PUBLIC, { "https://s/$it" }, backgroundScope) { testScheduler.currentTime }

    private suspend fun Room.connect(name: String, owner: Boolean = false, guestToken: String? = null, account: AccountIdentity? = null): Client {
        val c = Client()
        join(ClientMessage.Hello(PROTOCOL_VERSION, "ABCD", name, guestToken, if (owner) "owner-token" else null), c, account = account)
        return c
    }

    private var n = 0

    private suspend fun Room.run(c: Client, command: Command): ErrorInfo? {
        val id = "c${n++}"
        handle(c.id, ClientMessage.CommandMessage(id, command))
        return c.results.getValue(id)
    }

    private suspend fun Room.state(c: Client): RoomState {
        handle(c.id, ClientMessage.RequestSnapshot)
        return (c.messages.last() as ServerMessage.Snapshot).state
    }

    private fun assertDenied(error: ErrorInfo?) = assertEquals(ErrorCode.PERMISSION_DENIED, error?.code, error?.message)

    @Test
    fun `guests get the listener role, which can't do DJ things`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        val guest = room.connect("Guest")
        assertEquals("admin", room.state(owner).participants.first { it.id == owner.id }.roleId)
        assertEquals("listener", room.state(owner).participants.first { it.id == guest.id }.roleId)

        assertNull(room.run(guest, Command.AddSongs(listOf(song, song), QueuePosition.END)))
        // Pause stops the music for everyone: their Pause only stops it on their own device.
        assertDenied(room.run(guest, Command.Pause))
        assertDenied(room.run(guest, Command.PlayNow(song)))
        assertDenied(room.run(guest, Command.Seek(1000)))
        assertDenied(room.run(guest, Command.Kick(owner.id)))

        // Removing your own song is fine, someone else's is not.
        room.run(owner, Command.AddSongs(listOf(song), QueuePosition.END))
        val queue = room.state(owner).queue
        assertNull(room.run(guest, Command.RemoveQueueItem(queue.first { it.addedBy == guest.id }.itemId)))
        assertDenied(room.run(guest, Command.RemoveQueueItem(queue.first { it.addedBy == owner.id }.itemId)))
    }

    @Test
    fun `the others hear who paused or skipped, not the one who did it`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        val guest = room.connect("Guest")
        room.run(owner, Command.AddSongs(listOf(song, song.copy(id = "ytm:two", title = "Two")), QueuePosition.END))
        testScheduler.runCurrent()
        assertNull(room.run(owner, Command.Pause))
        assertNull(room.run(owner, Command.Skip(play = true)))
        val heard = { c: Client -> c.events.filterIsInstance<Event.Notice>().filter { it.notFor != c.id }.map { it.message } }
        assertEquals(listOf("Owner paused", "Owner skipped “${song.title}”"), heard(guest))
        assertEquals(emptyList(), heard(owner))
    }

    @Test
    fun `start radio clears the history only for someone who may remove those songs`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        val guest = room.connect("Guest")
        fun s(id: String) = song.copy(id = "ytm:$id")
        room.run(owner, Command.AddSongs(listOf(s("1"), s("2")), QueuePosition.END))
        room.run(owner, Command.Skip())
        assertEquals(listOf("ytm:1"), room.state(owner).history.map { it.song.id })

        // A listener allowed to start a radio, but not to remove others' songs: the history stays.
        room.run(owner, Command.SetParticipantPermissions(guest.id, allow = listOf(Permission.START_RADIO), deny = emptyList()))
        assertNull(room.run(guest, Command.StartRadio(s("3"), songs = listOf(s("4")), clearHistory = true)))
        var state = room.state(owner)
        assertEquals("ytm:3", state.nowPlaying?.item?.song?.id)
        assertEquals(listOf("ytm:1", "ytm:2"), state.history.map { it.song.id })

        // The owner may: the history goes, the song that played before too.
        assertNull(room.run(owner, Command.StartRadio(s("5"), songs = listOf(s("6")), clearHistory = true)))
        state = room.state(owner)
        assertEquals("ytm:5", state.nowPlaying?.item?.song?.id)
        assertTrue(state.history.isEmpty())
        assertTrue(state.queue.isEmpty())
        assertTrue(guest.events.any { it == Event.HistoryReplaced(emptyList()) })

        // Without asking, it stays (as before).
        room.run(owner, Command.StartRadio(s("7"), songs = listOf(s("8"))))
        assertEquals(listOf("ytm:5"), room.state(owner).history.map { it.song.id })
    }

    @Test
    fun `per-person overrides allow and deny on top of the role`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        val guest = room.connect("Guest")
        room.run(owner, Command.AddSongs(listOf(song), QueuePosition.END))

        // Listeners skip with *Vote to skip* (right away while the room doesn't vote).
        assertNull(room.run(owner, Command.SetParticipantPermissions(guest.id, allow = listOf(Permission.SEEK), deny = listOf(Permission.VOTE_SKIP))))
        assertNull(room.run(guest, Command.Seek(1000)))
        assertDenied(room.run(guest, Command.Skip()))
    }

    @Test
    fun `like Discord, you only manage people and roles below your own`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        val dj1 = room.connect("DJ 1")
        val dj2 = room.connect("DJ 2")
        val guest = room.connect("Guest")
        room.run(owner, Command.AssignRole(dj1.id, "dj"))
        room.run(owner, Command.AssignRole(dj2.id, "dj"))
        // Give the DJ role people management, but not everything.
        val dj = room.state(owner).roles.first { it.id == "dj" }
        assertNull(room.run(owner, Command.UpdateRole(dj.copy(permissions = dj.permissions + listOf(Permission.KICK, Permission.ASSIGN_ROLES, Permission.EDIT_ROLES)))))

        assertNull(room.run(dj1, Command.AssignRole(guest.id, "listener")))
        assertDenied(room.run(dj1, Command.Kick(dj2.id))) // same rank
        assertDenied(room.run(dj1, Command.Kick(owner.id)))
        assertDenied(room.run(dj1, Command.AssignRole(guest.id, "dj"))) // not below their own
        assertDenied(room.run(dj1, Command.AssignRole(guest.id, "admin")))
        assertDenied(room.run(dj1, Command.MoveRole("listener", 0))) // above themselves
        // Can't hand out what they don't have.
        assertDenied(room.run(dj1, Command.CreateRole("Mods", "#00ff00", listOf(Permission.BAN))))
        assertNull(room.run(dj1, Command.CreateRole("Helpers", "#00ff00", listOf(Permission.ADD_SONGS))))

        assertNull(room.run(dj1, Command.Kick(guest.id)))
        assertTrue(guest.messages.last() == ServerMessage.Rejected(RejectReason.KICKED))
        assertTrue(dj1.events.any { it == Event.ParticipantLeft(guest.id) })
        // Kicked people can come back.
        val back = room.connect("Guest", guestToken = guest.welcome.guestToken)
        assertTrue(back.messages.first() is ServerMessage.Welcome)
    }

    @Test
    fun `banned people can't come back, by account or guest token`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        val guest = room.connect("Guest")
        val anna = room.connect("Anna", account = AccountIdentity("anna@x", "Anna"))

        assertNull(room.run(owner, Command.Ban(guest.id)))
        assertNull(room.run(owner, Command.Ban(anna.id)))
        assertEquals(listOf("Guest", "Anna"), room.state(owner).bans.map { it.name })

        assertEquals(ServerMessage.Rejected(RejectReason.BANNED), room.connect("Guest", guestToken = guest.welcome.guestToken).messages.single())
        assertEquals(ServerMessage.Rejected(RejectReason.BANNED), room.connect("Anna again", account = AccountIdentity("anna@x", "Anna")).messages.single())

        assertNull(room.run(owner, Command.Unban(room.state(owner).bans.first { it.accountId == "anna@x" }.id)))
        assertTrue(room.connect("Anna", account = AccountIdentity("anna@x", "Anna")).messages.first() is ServerMessage.Welcome)
    }

    @Test
    fun `deleting a role moves its people to the default role`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        val guest = room.connect("Guest")
        room.run(owner, Command.AssignRole(guest.id, "dj"))

        assertEquals(ErrorCode.INVALID, room.run(owner, Command.DeleteRole("listener"))?.code)
        assertNull(room.run(owner, Command.DeleteRole("dj")))
        val state = room.state(owner)
        assertEquals(listOf("admin", "listener"), state.roles.map { it.id })
        assertEquals("listener", state.participants.first { it.id == guest.id }.roleId)
    }

    @Test
    fun `a room starts from the creator's template, and keeps roles across a restart`() = runTest {
        val template = DefaultRoles.template.copy(roles = listOf(Role("boss", "Boss", "#FFAA00", Permission.entries), Role("crowd", "Crowd", "nope", listOf(Permission.ADD_SONGS))))
        val room = Room("ABCD", "Party", "owner-token", RoomVisibility.PUBLIC, { "https://s/$it" }, backgroundScope, null, template) { testScheduler.currentTime }
        val owner = room.connect("Owner", owner = true)
        val guest = room.connect("Guest")
        room.run(owner, Command.SetParticipantPermissions(guest.id, listOf(Permission.SKIP), emptyList()))
        room.run(owner, Command.UpdateSettings(name = "Renamed"))

        val state = room.state(owner)
        assertEquals(listOf("#ffaa00", "#949ba4"), state.roles.map { it.color })
        // The template's default roles didn't exist; the lowest role is used instead.
        assertEquals("crowd", state.settings.defaultGuestRole)

        val restored = Room.restore(room.save(), { "https://s/$it" }, backgroundScope) { testScheduler.currentTime }
        val again = restored.connect("Owner", owner = true)
        val after = restored.state(again)
        assertEquals("Renamed", after.room.name)
        assertEquals(state.roles, after.roles)
        assertEquals(listOf(Permission.SKIP), after.participants.first { it.id == guest.id }.allow)
    }

    @Test
    fun `rooms saved before radio give their admin and default roles the radio permissions`() = runTest {
        val before = Permissions.BEFORE_RADIO.toList()
        val oldDj = listOf(Permission.ADD_SONGS, Permission.REMOVE_OWN, Permission.PLAY_PAUSE, Permission.SKIP, Permission.LISTEN_LOCALLY,
            Permission.PLAY_NOW, Permission.REORDER, Permission.REMOVE_OTHERS, Permission.SEEK, Permission.CHANGE_OUTPUTS, Permission.OUTPUT_VOLUME)
        val custom = Role("vip", "VIP", "#ffffff", listOf(Permission.ADD_SONGS, Permission.SKIP))
        val saved = Room("ABCD", "Party", "owner", RoomVisibility.PUBLIC, { "" }, backgroundScope) { 0 }.save().copy(
            roles = listOf(Role("admin", "Admin", "#f23f43", before), Role("dj", "DJ", "#5865f2", oldDj), custom),
            knownPermissions = null,
        )
        val room = Room.restore(saved, { "" }, backgroundScope) { 0 }
        val owner = room.connect("Owner", owner = true)
        val roles = room.state(owner).roles.associateBy { it.id }
        assertTrue(Permission.START_RADIO in roles.getValue("admin").permissions)
        assertTrue(Permission.AUTOPLAY_FROM_HERE in roles.getValue("dj").permissions)
        // A role that could skip may vote to skip too (that came later than radio).
        assertEquals(custom.permissions + Permission.VOTE_SKIP, roles.getValue("vip").permissions)
        // Saved again, it knows about radio: nothing is added twice.
        assertEquals(Permission.entries, room.save().knownPermissions)
    }
}
