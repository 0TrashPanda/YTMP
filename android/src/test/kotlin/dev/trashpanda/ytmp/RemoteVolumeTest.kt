package dev.trashpanda.ytmp

import dev.trashpanda.ytmp.protocol.Participant
import dev.trashpanda.ytmp.protocol.PlaybackStatus
import dev.trashpanda.ytmp.protocol.RoomInfo
import dev.trashpanda.ytmp.protocol.RoomState
import dev.trashpanda.ytmp.protocol.RoomVisibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RemoteVolumeTest {
    private val link = RoomLink("https://ytmp.example.com", "ABCD", "token")

    private fun room(volume: Double?, changes: Int, accountId: String? = "anna@x") = FollowedRoom(
        link, "p1",
        RoomState(
            RoomInfo("ABCD", "Room", RoomVisibility.PUBLIC),
            listOf(Participant("p1", "Anna", isOwner = false, online = true, listening = true, accountId = accountId, volume = volume, volumeChanges = changes)),
            emptyList(), emptyList(), nowPlaying = null, playback = PlaybackStatus(false, 0, 0), outputs = emptyList(),
        ),
        clockOffset = 0.0,
    )

    @Test
    fun `says the volume here when the room has another`() {
        val volume = RemoteVolume()
        assertEquals(RemoteVolume.Action.Report, volume.update(room(null, 0), 0.6))
        // Set from elsewhere before this phone followed the room: what's here counts.
        assertEquals(RemoteVolume.Action.Report, RemoteVolume().update(room(0.2, 3), 0.6))
        assertNull(RemoteVolume().update(room(0.6, 3), 0.6))
    }

    @Test
    fun `turns to a volume another device set`() {
        val volume = RemoteVolume()
        volume.update(room(0.6, 0), 0.6)
        assertEquals(RemoteVolume.Action.Apply(0.3), volume.update(room(0.3, 1), 0.6))
        // The phone has 15 steps: it got 0.33, which the room then hears.
        assertEquals(RemoteVolume.Action.Report, volume.update(room(0.3, 1), 5 / 15.0))
        assertNull(volume.update(room(5 / 15.0, 1), 5 / 15.0))
    }

    @Test
    fun `nothing without an account`() {
        assertNull(RemoteVolume().update(room(null, 0, accountId = null), 0.6))
    }
}
