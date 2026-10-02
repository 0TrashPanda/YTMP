package dev.trashpanda.ytmp.core

import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ClientMessage
import dev.trashpanda.ytmp.protocol.Command
import dev.trashpanda.ytmp.protocol.PROTOCOL_VERSION
import dev.trashpanda.ytmp.protocol.QueuePosition
import dev.trashpanda.ytmp.protocol.RoomVisibility
import dev.trashpanda.ytmp.protocol.ServerMessage
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FinishedPlayTest {
    private fun song(n: Int) = Song("ytm:$n", "Song $n", listOf(ArtistRef(null, "Artist")), null, 60_000, emptyList())

    @Test
    fun `finished and skipped songs are reported with who was there`() = runTest {
        val plays = mutableListOf<FinishedPlay>()
        val room = Room("ABCD", "Party", "owner", RoomVisibility.PUBLIC, { "https://s/$it" }, backgroundScope) { testScheduler.currentTime }
        room.onPlayFinished = { plays += it }

        var me = ""
        room.join(ClientMessage.Hello(PROTOCOL_VERSION, "ABCD", "Me", null, "owner", accountToken = "tok"), { if (it is ServerMessage.Welcome) me = it.participantId }, account = AccountIdentity("me@x", "Me"))
        room.join(ClientMessage.Hello(PROTOCOL_VERSION, "ABCD", "Shy", null, null, hideFromHistory = true), {})
        suspend fun run(c: Command) = room.handle(me, ClientMessage.CommandMessage("c", c))

        run(Command.AddSongs(listOf(song(1), song(2), song(3)), QueuePosition.END))
        runCurrent()
        advanceTimeBy(60_001) // song 1 plays to the end
        runCurrent()
        advanceTimeBy(20_000)
        run(Command.Skip) // song 2 skipped after 20 s
        runCurrent()

        assertEquals(listOf("ytm:1" to false, "ytm:2" to true), plays.map { it.item.song.id to it.skipped })
        assertEquals(60_000L, plays[0].heardMs)
        assertTrue(plays[1].heardMs in 20_000L..20_001L, "heard ${plays[1].heardMs}")
        assertTrue(plays[1].startedAt - plays[0].startedAt in 60_000L..60_001L)
        val listeners = plays[0].listeners
        assertEquals(listOf("Me" to "tok", "Shy" to null), listeners.map { it.name to it.accountToken })
        assertEquals(listOf(false, true), listeners.map { it.hideFromHistory })
    }
}
