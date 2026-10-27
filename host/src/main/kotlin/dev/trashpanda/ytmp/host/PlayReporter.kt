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
        // One person can be in the room more than once (their phone and their laptop, or two tabs):
        // their account hears the song once, and they didn't listen "with" themselves.
        val people = play.listeners.distinctBy { it.accountId ?: "guest:${it.participantId}" }
        val shared = people.size > 1
        for ((account, mine) in play.listeners.filter { it.accountToken != null && it.accountId != null }.groupBy { it.accountId!! }) {
            val me = mine.first()
            val report = PlayReport(
                song = play.item.song,
                playedAt = play.startedAt,
                heardMs = play.heardMs,
                skipped = play.skipped,
                roomCode = play.roomCode,
                roomName = play.roomName,
                addedByMe = mine.any { play.item.addedBy == it.participantId },
                addedByName = play.item.addedByName,
                listenedWith = people
                    .filter { it.accountId != account && !it.hideFromHistory }
                    .map { ListenedWith(it.accountId, it.name) },
                shared = shared,
            )
            scope.launch { auth.reportPlay(me.accountToken!!, report) }
        }
    }
}
