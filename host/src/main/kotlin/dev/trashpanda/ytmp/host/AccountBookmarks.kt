package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.EpisodeBookmarks
import dev.trashpanda.ytmp.protocol.EpisodeProgress
import dev.trashpanda.ytmp.protocol.EpisodeProgressReport
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Episode progress kept on people's accounts, at their auth servers ([HostAuth]), for the rooms here. */
class AccountBookmarks(private val auth: HostAuth, private val scope: CoroutineScope) : EpisodeBookmarks {
    override suspend fun progress(accountToken: String, episodeId: String): EpisodeProgress? = auth.episodeProgress(accountToken, episodeId)

    override fun report(accountToken: String, episode: Song, positionMs: Long) {
        scope.launch { auth.reportEpisode(accountToken, EpisodeProgressReport(episode.id, positionMs, episode.durationMs)) }
    }
}
