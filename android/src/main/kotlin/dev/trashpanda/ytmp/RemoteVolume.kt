package dev.trashpanda.ytmp

/**
 * This phone's media volume in the room it plays, so your other devices can see and set it
 * (docs/features/playback-and-outputs.md#your-devices). Like the web app's DeviceVolume: it
 * says the volume here when the room has another, and turns to the one another device of yours
 * set ([dev.trashpanda.ytmp.protocol.Participant.volumeChanges]).
 */
class RemoteVolume {
    sealed interface Action {
        /** Turn the volume here to this (0–1). */
        data class Apply(val volume: Double) : Action

        /** Tell the room the volume here. */
        data object Report : Action
    }

    /** The participant whose `volumeChanges` were [seen], in its room. */
    private var seenFor: Pair<RoomLink, String>? = null
    private var seen = 0

    /** What to do now that the room changed, with [volume] (0–1) the volume here. */
    fun update(room: FollowedRoom, volume: Double): Action? {
        val me = room.state.participants.firstOrNull { it.id == room.participantId } ?: return null
        // Only devices with an account have devices of their own.
        if (me.accountId == null) return null
        val key = room.link to me.id
        if (seenFor != key) {
            // The first time: what's here counts.
            seenFor = key
            seen = me.volumeChanges
        } else if (me.volumeChanges != seen) {
            seen = me.volumeChanges
            val wanted = me.volume
            if (wanted != null && wanted != volume) return Action.Apply(wanted)
        }
        return if (me.volume == volume) null else Action.Report
    }
}
