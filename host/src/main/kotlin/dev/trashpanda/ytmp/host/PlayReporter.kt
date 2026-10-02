package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.FinishedPlay
import dev.trashpanda.ytmp.protocol.ListenedWith
import dev.trashpanda.ytmp.protocol.PlayReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Sends finished songs to the listening history of every account holder who heard them,
 * at their own auth server. The auth server keeps it only if they turned tracking on.
 * See docs/features/history-and-recommendations.md.
 */
class PlayReporter(private val auth: HostAuth, private val scope: CoroutineScope) {
    fun report(play: FinishedPlay) {
        val shared = play.listeners.size > 1
        for (me in play.listeners) {
            val token = me.accountToken ?: continue
            val report = PlayReport(
                song = play.item.song,
                playedAt = play.startedAt,
                heardMs = play.heardMs,
                skipped = play.skipped,
                roomCode = play.roomCode,
                roomName = play.roomName,
                addedByMe = play.item.addedBy == me.participantId,
                addedByName = play.item.addedByName,
                listenedWith = play.listeners
                    .filter { it.participantId != me.participantId && !it.hideFromHistory }
                    .map { ListenedWith(it.accountId, it.name) },
                shared = shared,
            )
            scope.launch { auth.reportPlay(token, report) }
        }
    }
}
