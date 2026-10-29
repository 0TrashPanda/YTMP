package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.ErrorCode
import dev.trashpanda.ytmp.protocol.ErrorInfo
import dev.trashpanda.ytmp.protocol.Event
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.Permission
import dev.trashpanda.ytmp.protocol.QueueItem
import dev.trashpanda.ytmp.protocol.QueueItemResult
import dev.trashpanda.ytmp.protocol.QueuePosition
import dev.trashpanda.ytmp.protocol.RepeatMode
import dev.trashpanda.ytmp.protocol.Role
import dev.trashpanda.ytmp.protocol.RoomState
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fair order, shuffle, repeat, vote to skip and queue limits (docs/features/queue.md). */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueTest {
    private fun song(n: Int, minutes: Int = 1) = Song("ytm:$n", "Song $n", listOf(ArtistRef(null, "Artist")), null, minutes * 60_000L, emptyList())

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

    private suspend fun Room.connect(name: String, owner: Boolean = false): Client {
        val c = Client()
        join(ClientMessage.Hello(PROTOCOL_VERSION, "ABCD", name, null, if (owner) "owner-token" else null), c)
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

    private suspend fun Room.queueBy(c: Client): List<String> = state(c).queue.map { it.addedByName }

    /** Each song's round: the how-manieth song of its person. */
    private fun rounds(queue: List<QueueItem>): List<Int> {
        val count = HashMap<String, Int>()
        return queue.map { (count.merge(it.addedBy, 1, Int::plus) ?: 1) - 1 }
    }

    // --- fair order ---

    @Test
    fun `fair order spreads songs out per person, as in the docs' example`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        val (p1, p2, p3) = listOf(room.connect("P1"), room.connect("P2"), room.connect("P3"))
        assertNull(room.run(owner, Command.UpdateSettings(fairOrder = true)))
        room.run(owner, Command.PlayNow(song(0)))

        room.run(p1, Command.AddSongs((1..5).map { song(it) }, QueuePosition.END))
        room.run(p2, Command.AddSongs((6..8).map { song(it) }, QueuePosition.END))
        room.run(p3, Command.AddSongs((9..10).map { song(it) }, QueuePosition.END))

        assertEquals("P1 P2 P3 P1 P2 P3 P1 P2 P1 P1".split(" "), room.queueBy(owner))
        // Each person's own songs keep their order.
        assertEquals((1..5).map { "ytm:$it" }, room.state(owner).queue.filter { it.addedByName == "P1" }.map { it.song.id })

        // Someone with nothing queued lands near the top, however long the queue is.
        val p4 = room.connect("P4")
        room.run(p4, Command.AddSongs(listOf(song(11)), QueuePosition.END))
        assertEquals("P1 P2 P3 P4 P1 P2 P3 P1 P2 P1 P1".split(" "), room.queueBy(owner))
    }

    @Test
    fun `the person whose song plays now waits for the others`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        val guest = room.connect("Guest")
        room.run(owner, Command.UpdateSettings(fairOrder = true))
        room.run(owner, Command.AddSongs(listOf(song(1), song(2)), QueuePosition.END)) // 1 plays, 2 queued

        room.run(guest, Command.AddSongs(listOf(song(3)), QueuePosition.END))

        assertEquals(listOf("Guest", "Owner"), room.queueBy(owner))
    }

    @Test
    fun `play next goes to your fair slot, unless you may reorder`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        val guest = room.connect("Guest")
        room.run(owner, Command.UpdateSettings(fairOrder = true))
        room.run(owner, Command.PlayNow(song(0)))
        room.run(guest, Command.AddSongs(listOf(song(1)), QueuePosition.END))
        room.run(guest, Command.AddSongs(listOf(song(2)), QueuePosition.NEXT)) // a listener: fair slot, not first

        assertEquals(listOf("ytm:1", "ytm:2"), room.state(owner).queue.map { it.song.id })

        room.run(owner, Command.AddSongs(listOf(song(3)), QueuePosition.NEXT)) // may reorder: first
        assertEquals("ytm:3", room.state(owner).queue.first().song.id)
    }

    @Test
    fun `turning fair order on puts the queue in rounds`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        val (a, b) = listOf(room.connect("A"), room.connect("B"))
        room.run(owner, Command.PlayNow(song(0)))
        room.run(a, Command.AddSongs((1..3).map { song(it) }, QueuePosition.END))
        room.run(b, Command.AddSongs((4..5).map { song(it) }, QueuePosition.END))
        assertEquals("A A A B B".split(" "), room.queueBy(owner))

        room.run(owner, Command.UpdateSettings(fairOrder = true))

        assertEquals("A B A B A".split(" "), room.queueBy(owner))
    }

    // --- shuffle ---

    @Test
    fun `shuffle mixes the upcoming songs, and keeps the rounds with fair order`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        val people = (1..3).map { room.connect("P$it") }
        room.run(owner, Command.UpdateSettings(fairOrder = true))
        room.run(owner, Command.PlayNow(song(0)))
        people.forEachIndexed { i, p -> room.run(p, Command.AddSongs((1..4).map { song(i * 10 + it) }, QueuePosition.END)) }
        val before = room.state(owner).queue

        var changed = false
        repeat(10) {
            assertNull(room.run(owner, Command.ShuffleQueue))
            val after = room.state(owner).queue
            assertEquals(before.map { it.itemId }.toSet(), after.map { it.itemId }.toSet())
            // Still round by round: every person once per round.
            assertEquals(List(4) { r -> List(3) { r } }.flatten(), rounds(after))
            if (after.map { it.itemId } != before.map { it.itemId }) changed = true
        }
        assertTrue(changed)
    }

    @Test
    fun `listeners can't shuffle`() = runTest {
        val room = newRoom()
        room.connect("Owner", owner = true)
        val guest = room.connect("Guest")
        assertEquals(ErrorCode.PERMISSION_DENIED, room.run(guest, Command.ShuffleQueue)?.code)
    }

    // --- jumping ---

    @Test
    fun `jumping far ahead looks up the new song and the one after it, not each song passed`() = runTest {
        val lookups = mutableListOf<String>()
        val room = Room("ABCD", "Party", "owner-token", RoomVisibility.PUBLIC, { lookups += it; "https://s/$it" }, backgroundScope) { testScheduler.currentTime }
        val owner = room.connect("Owner", owner = true)
        room.run(owner, Command.AddSongs((1..20).map { song(it) }, QueuePosition.END))
        runCurrent()
        assertEquals(listOf("ytm:1", "ytm:2"), lookups)

        lookups.clear()
        val target = room.state(owner).queue[15]
        assertNull(room.run(owner, Command.JumpTo(target.itemId)))
        runCurrent()
        assertEquals(listOf("ytm:17", "ytm:18"), lookups)
        // The one that played and the 15 passed.
        assertEquals(16, room.state(owner).history.count { it.result == QueueItemResult.SKIPPED })
    }

    // --- repeat ---

    @Test
    fun `repeat one plays the song again, and skipping still moves on`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        room.run(owner, Command.AddSongs(listOf(song(1), song(2)), QueuePosition.END))
        runCurrent()
        assertNull(room.run(owner, Command.SetRepeat(RepeatMode.ONE)))
        assertEquals(RepeatMode.ONE, room.state(owner).repeat)

        advanceTimeBy(60_001)
        runCurrent()
        var state = room.state(owner)
        assertEquals("ytm:1", state.nowPlaying?.item?.song?.id)
        assertTrue(state.playback.positionMs < 1_000)
        assertTrue(state.history.isEmpty())

        room.run(owner, Command.Skip())
        state = room.state(owner)
        assertEquals("ytm:2", state.nowPlaying?.item?.song?.id)
    }

    @Test
    fun `repeat queue plays the queue's songs again when it runs out, instead of autoplay`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        room.run(owner, Command.AddSongs((1..3).map { song(it) }, QueuePosition.END))
        runCurrent()
        room.run(owner, Command.SetRepeat(RepeatMode.QUEUE))

        repeat(3) {
            advanceTimeBy(60_001)
            runCurrent()
        }

        val state = room.state(owner)
        assertEquals("ytm:1", state.nowPlaying?.item?.song?.id)
        assertEquals(listOf("ytm:2", "ytm:3"), state.queue.map { it.song.id })

        // And the round after that too.
        repeat(3) {
            advanceTimeBy(60_001)
            runCurrent()
        }
        assertEquals(listOf("ytm:2", "ytm:3"), room.state(owner).queue.map { it.song.id })
    }

    @Test
    fun `listeners can't change repeat`() = runTest {
        val room = newRoom()
        room.connect("Owner", owner = true)
        val guest = room.connect("Guest")
        assertEquals(ErrorCode.PERMISSION_DENIED, room.run(guest, Command.SetRepeat(RepeatMode.ONE))?.code)
    }

    // --- vote to skip ---

    @Test
    fun `listeners skip right away while the room doesn't vote`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        val guest = room.connect("Guest")
        room.run(owner, Command.AddSongs(listOf(song(1), song(2)), QueuePosition.END))

        assertNull(room.run(guest, Command.Skip()))

        assertEquals("ytm:2", room.state(owner).nowPlaying?.item?.song?.id)
        assertNull(room.state(owner).skipVotes)
    }

    @Test
    fun `with voting on, listeners vote and the song is skipped once enough voted`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        val (a, b) = listOf(room.connect("A"), room.connect("B"))
        room.run(owner, Command.UpdateSettings(voteSkip = true, voteSkipPercent = 50))
        room.run(owner, Command.AddSongs(listOf(song(1), song(2)), QueuePosition.END))
        assertEquals(2, room.state(owner).skipVotes?.needed) // half of 3 people, rounded up

        assertNull(room.run(a, Command.Skip())) // a vote, not a skip
        var state = room.state(owner)
        assertEquals("ytm:1", state.nowPlaying?.item?.song?.id)
        assertEquals(listOf(a.id), state.skipVotes?.voters)

        // Taking it back, and voting again.
        room.run(a, Command.VoteSkip(false))
        assertEquals(emptyList(), room.state(owner).skipVotes?.voters)
        room.run(a, Command.VoteSkip(true))

        room.run(b, Command.VoteSkip(true))
        state = room.state(owner)
        assertEquals("ytm:2", state.nowPlaying?.item?.song?.id)
        assertEquals(emptyList(), state.skipVotes?.voters) // a new song, a new vote
        assertTrue(owner.events.any { it is Event.Notice && "enough votes" in it.message })
        assertEquals(QueueItemResult.SKIPPED, state.history.last().result)
    }

    @Test
    fun `fewer people here means fewer votes needed`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        val people = (1..3).map { room.connect("P$it") }
        room.run(owner, Command.UpdateSettings(voteSkip = true, voteSkipPercent = 50))
        room.run(owner, Command.AddSongs(listOf(song(1), song(2)), QueuePosition.END))
        room.run(people[0], Command.VoteSkip(true))
        assertEquals(2, room.state(owner).skipVotes?.needed)

        // Two leave: 2 people here, 1 vote needed, which is there.
        room.disconnect(people[1].id, people[1])
        room.disconnect(people[2].id, people[2])

        assertEquals("ytm:2", room.state(owner).nowPlaying?.item?.song?.id)
    }

    @Test
    fun `DJs and the owner still skip right away`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        room.connect("Guest")
        room.run(owner, Command.UpdateSettings(voteSkip = true))
        room.run(owner, Command.AddSongs(listOf(song(1), song(2)), QueuePosition.END))

        room.run(owner, Command.Skip())

        assertEquals("ytm:2", room.state(owner).nowPlaying?.item?.song?.id)
    }

    // --- limits ---

    @Test
    fun `queue limits leave songs out, and say why`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        val guest = room.connect("Guest")
        room.run(owner, Command.UpdateSettings(maxSongMinutes = 10, maxSongsPerPerson = 3))
        room.run(owner, Command.PlayNow(song(0)))

        val one = room.run(guest, Command.AddSongs(listOf(song(1, minutes = 60)), QueuePosition.END))
        assertEquals("Not added: longer than 10 min", one?.message)

        val some = room.run(guest, Command.AddSongs(listOf(song(2), song(3, minutes = 60), song(4), song(5), song(6)), QueuePosition.END))
        assertEquals("Left out 2 of 5: 1 longer than 10 min; 1 you can have 3 songs in the queue at once", some?.message)
        assertEquals(listOf("ytm:2", "ytm:4", "ytm:5"), room.state(owner).queue.map { it.song.id })

        // Whoever runs the room isn't limited (they could turn the limits off anyway).
        assertNull(room.run(owner, Command.AddSongs(listOf(song(7, minutes = 60)), QueuePosition.END)))
    }

    @Test
    fun `no recent repeats`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        val guest = room.connect("Guest")
        room.run(owner, Command.UpdateSettings(noRepeatMinutes = 30))
        room.run(owner, Command.AddSongs(listOf(song(1), song(2)), QueuePosition.END))
        runCurrent()

        assertEquals("Not added: played in the last 30 min", room.run(guest, Command.AddSongs(listOf(song(1)), QueuePosition.END))?.message)

        advanceTimeBy(31 * 60_000L)
        assertNull(room.run(guest, Command.AddSongs(listOf(song(1)), QueuePosition.END)))
    }

    @Test
    fun `settings are checked`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        assertEquals(ErrorCode.INVALID, room.run(owner, Command.UpdateSettings(voteSkipPercent = 0))?.code)
        assertEquals(ErrorCode.INVALID, room.run(owner, Command.UpdateSettings(maxSongsPerPerson = -1))?.code)
    }

    // --- saving ---

    @Test
    fun `repeat and the new settings survive a restart`() = runTest {
        val room = newRoom()
        val owner = room.connect("Owner", owner = true)
        room.run(owner, Command.UpdateSettings(fairOrder = true, voteSkip = true, voteSkipPercent = 75, maxSongMinutes = 12))
        room.run(owner, Command.SetRepeat(RepeatMode.QUEUE))

        val restored = Room.restore(SavedRoom.fromJson(room.save().toJson()), { "https://s/$it" }, backgroundScope)
        val state = restored.state(restored.connect("Owner", owner = true))

        assertEquals(RepeatMode.QUEUE, state.repeat)
        assertEquals(true, state.settings.fairOrder)
        assertEquals(75, state.settings.voteSkipPercent)
        assertEquals(12, state.settings.maxSongMinutes)
    }

    // --- permissions ---

    @Test
    fun `rooms from before voting, an untouched listener votes now, other roles that skip may vote too`() {
        val known = Permission.entries.toSet() - Permission.VOTE_SKIP - Permission.REPEAT
        val oldListener = Role("listener", "Listener", "#949ba4", listOf(Permission.ADD_SONGS, Permission.REMOVE_OWN, Permission.PLAY_PAUSE, Permission.SKIP, Permission.LISTEN_LOCALLY, Permission.CLONE_ROOM))
        val custom = Role("vip", "VIP", "#ffffff", listOf(Permission.ADD_SONGS, Permission.SKIP))
        val oldDj = DefaultRoles.template.roles.first { it.id == "dj" }.let { it.copy(permissions = it.permissions - Permission.REPEAT) }

        val (listener, vip, dj) = Permissions.upgrade(listOf(oldListener, custom, oldDj), known)

        assertEquals(DefaultRoles.template.roles.first { it.id == "listener" }.permissions.toSet(), listener.permissions.toSet())
        assertTrue(Permission.VOTE_SKIP in vip.permissions && Permission.SKIP in vip.permissions)
        assertTrue(Permission.REPEAT in dj.permissions)
        assertNotEquals(Permission.SKIP in listener.permissions, true)
    }
}
