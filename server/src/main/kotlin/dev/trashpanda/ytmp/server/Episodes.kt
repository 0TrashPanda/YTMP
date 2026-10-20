package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.protocol.EpisodeProgress
import java.sql.Connection

/**
 * How far accounts got in podcast episodes (docs/features/ui.md#podcasts): where they stopped,
 * and which they heard to the end. Hosts report it as episodes play; it's for resuming in any
 * room and the played marks on podcast pages. Not the listening history: it's kept with
 * history paused too.
 */
class Episodes(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    /** [accountId] is at [positionMs] in an episode: near its end, that's played. */
    fun report(accountId: String, episodeId: String, positionMs: Long, durationMs: Long) {
        val played = durationMs > 0 && positionMs >= durationMs - RESUME_END_MS
        save(accountId, episodeId, if (played) 0 else positionMs.coerceAtLeast(0), durationMs, played)
    }

    /** Marked played, or unplayed (from the start). */
    fun mark(accountId: String, episodeId: String, played: Boolean) = db.use { c ->
        val duration = progress(c, accountId, listOf(episodeId)).firstOrNull()?.durationMs ?: 0
        save(c, accountId, episodeId, 0, duration, played)
    }

    /** The progress of those of [episodeIds] that have some. */
    fun progress(accountId: String, episodeIds: List<String>): List<EpisodeProgress> = db.use { c -> progress(c, accountId, episodeIds) }

    /** Where to go on with an episode: where [accountId] stopped, unless it was played, barely started or nearly done. */
    fun resumeAt(accountId: String, episodeId: String): Long? {
        val p = progress(accountId, listOf(episodeId)).firstOrNull() ?: return null
        if (p.played || p.positionMs < RESUME_MIN_MS || (p.durationMs > 0 && p.positionMs > p.durationMs - RESUME_END_MS)) return null
        return p.positionMs
    }

    private fun save(accountId: String, episodeId: String, positionMs: Long, durationMs: Long, played: Boolean) =
        db.use { c -> save(c, accountId, episodeId, positionMs, durationMs, played) }

    private fun save(c: Connection, accountId: String, episodeId: String, positionMs: Long, durationMs: Long, played: Boolean) {
        c.prepareStatement(
            """INSERT INTO episode_progress (account_id, episode_id, position_ms, duration_ms, played, updated_at) VALUES (?, ?, ?, ?, ?, ?)
               ON CONFLICT (account_id, episode_id) DO UPDATE SET
                 position_ms = excluded.position_ms, duration_ms = excluded.duration_ms, played = excluded.played, updated_at = excluded.updated_at""",
        ).use {
            it.setString(1, accountId)
            it.setString(2, episodeId.take(256))
            it.setLong(3, positionMs)
            it.setLong(4, durationMs.coerceAtLeast(0))
            it.setBoolean(5, played)
            it.setLong(6, clock())
            it.executeUpdate()
        }
    }

    private fun progress(c: Connection, accountId: String, episodeIds: List<String>): List<EpisodeProgress> {
        val ids = episodeIds.distinct().take(MAX_IDS)
        if (ids.isEmpty()) return emptyList()
        return c.prepareStatement(
            "SELECT episode_id, position_ms, duration_ms, played FROM episode_progress WHERE account_id = ? AND episode_id IN (${ids.joinToString(", ") { "?" }})",
        ).use {
            it.setString(1, accountId)
            ids.forEachIndexed { i, id -> it.setString(i + 2, id) }
            it.executeQuery().use { rows ->
                buildList { while (rows.next()) add(EpisodeProgress(rows.getString(1), rows.getLong(2), rows.getLong(3), rows.getBoolean(4))) }
            }
        }
    }

    companion object {
        /** Episodes resume from where they were left after this much (like a room's own positions)... */
        const val RESUME_MIN_MS = 30_000L
        /** ...unless less than this was left: then it's played. */
        const val RESUME_END_MS = 60_000L
        /** A podcast page asks for at most this many episodes at once. */
        const val MAX_IDS = 200
    }
}
