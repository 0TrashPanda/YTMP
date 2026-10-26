package dev.trashpanda.ytmp.protocol

/**
 * The room after [event]: how a client keeps its copy of the room up to date between snapshots
 * (docs/implementation/protocol.md). The app's player (RoomFollower) and the tests use this; the
 * web app has the same in `frontend/src/lib/room.svelte.ts`. Every event is handled here, so a
 * new one doesn't compile until it is (the web app's check is the same).
 */
fun RoomState.apply(event: Event): RoomState = when (event) {
    is Event.ParticipantJoined -> withParticipant(event.participant)
    is Event.ParticipantUpdated -> withParticipant(event.participant)
    is Event.ParticipantLeft -> copy(participants = participants.filter { it.id != event.participantId })
    is Event.QueueItemsAdded -> copy(queue = queue.toMutableList().apply { addAll(event.index.coerceIn(0, size), event.items) })
    is Event.QueueItemRemoved -> copy(queue = queue.filter { it.itemId != event.itemId })
    is Event.QueueItemMoved -> {
        val item = queue.firstOrNull { it.itemId == event.itemId }
        if (item == null) this else copy(queue = (queue - item).toMutableList().apply { add(event.toIndex.coerceIn(0, size), item) })
    }
    is Event.QueueReplaced -> copy(queue = event.items)
    is Event.HistoryAppended -> copy(history = history + event.item)
    is Event.HistoryItemRemoved -> copy(history = history.filter { it.itemId != event.itemId })
    is Event.HistoryReplaced -> copy(history = event.items)
    is Event.NowPlayingChanged -> copy(nowPlaying = event.item?.let { NowPlaying(it, null) })
    is Event.StreamReady -> {
        val now = nowPlaying
        if (now?.item?.itemId == event.itemId) copy(nowPlaying = now.copy(streamUrl = event.streamUrl)) else this
    }
    is Event.PlaybackChanged -> copy(playback = event.playback)
    is Event.OutputsChanged -> copy(outputs = event.outputs)
    is Event.RoomUpdated -> copy(room = event.room)
    is Event.AutoplayChanged -> copy(autoplay = event.items, autoplaySeed = event.seed)
    is Event.RadioOptionsChanged -> copy(radioOptions = event.options)
    is Event.RolesChanged -> copy(roles = event.roles)
    is Event.SettingsChanged -> copy(settings = event.settings)
    is Event.BansChanged -> copy(bans = event.bans)
    is Event.MigrationChanged -> copy(migration = event.migration)
    is Event.AdminlessChanged -> copy(adminless = event.adminless)
    is Event.RepeatChanged -> copy(repeat = event.mode)
    is Event.SkipVotesChanged -> copy(skipVotes = event.skipVotes)
    // Shown, not kept.
    is Event.Notice -> this
}

private fun RoomState.withParticipant(p: Participant): RoomState {
    val index = participants.indexOfFirst { it.id == p.id }
    return copy(participants = if (index >= 0) participants.toMutableList().also { it[index] = p } else participants + p)
}
