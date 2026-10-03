package dev.trashpanda.ytmp

import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.Event
import dev.trashpanda.ytmp.protocol.OutputInfo
import dev.trashpanda.ytmp.protocol.OutputKind
import dev.trashpanda.ytmp.protocol.Participant
import dev.trashpanda.ytmp.protocol.PlaybackStatus
import dev.trashpanda.ytmp.protocol.QueueItem
import dev.trashpanda.ytmp.protocol.RoomInfo
import dev.trashpanda.ytmp.protocol.RoomState
import dev.trashpanda.ytmp.protocol.RoomVisibility
import dev.trashpanda.ytmp.protocol.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RoomFollowerTest {
    private val song = Song("ytm:a", "Song", listOf(ArtistRef(null, "Anna"), ArtistRef(null, "Ben")), null, 200_000, emptyList())
    private val item = QueueItem("i1", song, "p1", "Me", 0)
    private val me = Participant("p1", "Me", isOwner = true, online = true, listening = true)
    private val empty = RoomState(
        RoomInfo("ABCD", "Room", RoomVisibility.PRIVATE), listOf(me), emptyList(), emptyList(),
        nowPlaying = null, playback = PlaybackStatus(false, 0, 0), outputs = emptyList(),
    )
    private val link = RoomLink("http://127.0.0.1:8765", "ABCD", "token")

    @Test
    fun `next song then its stream`() {
        var s = RoomFollower.apply(empty, Event.NowPlayingChanged(item))
        assertEquals("i1", s.nowPlaying?.item?.itemId)
        assertNull(s.nowPlaying?.streamUrl)
        // A stream for another (old) item is ignored.
        s = RoomFollower.apply(s, Event.StreamReady("old", "https://old"))
        assertNull(s.nowPlaying?.streamUrl)
        s = RoomFollower.apply(s, Event.StreamReady("i1", "https://stream"))
        assertEquals("https://stream", s.nowPlaying?.streamUrl)
        s = RoomFollower.apply(s, Event.PlaybackChanged(PlaybackStatus(true, 10, 99)))
        assertEquals(PlaybackStatus(true, 10, 99), s.playback)
    }

    @Test
    fun `alone unless someone else listens or a speaker is on`() {
        assertEquals(true, FollowedRoom(link, "p1", empty, 0.0).alone)
        val friend = Participant("p2", "Friend", isOwner = false, online = true, listening = true)
        val withFriend = RoomFollower.apply(empty, Event.ParticipantJoined(friend))
        assertEquals(false, FollowedRoom(link, "p1", withFriend, 0.0).alone)
        val friendLeft = RoomFollower.apply(withFriend, Event.ParticipantLeft("p2"))
        assertEquals(true, FollowedRoom(link, "p1", friendLeft, 0.0).alone)
        val speaker = RoomFollower.apply(empty, Event.OutputsChanged(listOf(OutputInfo("cast:x", "TV", OutputKind.entries.first(), active = true, volume = null))))
        assertEquals(false, FollowedRoom(link, "p1", speaker, 0.0).alone)
    }

    @Test
    fun `target item is made like the web app makes it`() {
        val target = TargetItem.of("i1", song)
        assertEquals("Anna, Ben", target.artist)
        assertNull(target.artUrl)
    }
}
