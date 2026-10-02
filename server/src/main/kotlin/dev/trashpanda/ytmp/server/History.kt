package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.protocol.ListenedWith
import dev.trashpanda.ytmp.protocol.HistoryEntry
import dev.trashpanda.ytmp.protocol.PlayReport
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.serialization.builtins.ListSerializer
import java.sql.ResultSet

/** Accounts' listening history: one row per song heard, in the order played. */
class History(private val db: Database) {
    private val listenedWith = ListSerializer(ListenedWith.serializer())

    fun add(accountId: String, report: PlayReport) = db.use { c ->
        c.prepareStatement(
            """INSERT INTO plays (id, account_id, played_at, song_id, title, artists, song, heard_ms, skipped, room_code, room_name,
               added_by_me, added_by_name, listened_with, shared) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        ).use {
            it.setString(1, Ids.random(16))
            it.setString(2, accountId)
            it.setLong(3, report.playedAt)
            it.setString(4, report.song.id.take(256))
            it.setString(5, report.song.title.take(512))
            it.setString(6, report.song.artists.joinToString(", ") { a -> a.name }.take(512))
            it.setString(7, ProtocolJson.encodeToString(Song.serializer(), report.song))
            it.setLong(8, report.heardMs.coerceAtLeast(0))
            it.setBoolean(9, report.skipped)
            it.setString(10, report.roomCode.take(16))
            it.setString(11, report.roomName.take(128))
            it.setBoolean(12, report.addedByMe)
            it.setString(13, report.addedByName.take(128))
            it.setString(14, ProtocolJson.encodeToString(listenedWith, report.listenedWith.take(MAX_LISTENED_WITH)))
            it.setBoolean(15, report.shared)
            it.executeUpdate()
        }
        Unit
    }

    /** Newest first, played before [before] (ms), matching [query] in title or artists. */
    fun page(accountId: String, before: Long?, limit: Int, query: String?): Pair<List<HistoryEntry>, Boolean> = db.use { c ->
        val q = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val sql = buildString {
            append("SELECT * FROM plays WHERE account_id = ?")
            if (before != null) append(" AND played_at < ?")
            if (q != null) append(" AND (LOWER(title) LIKE ? OR LOWER(artists) LIKE ?)")
            append(" ORDER BY played_at DESC LIMIT ?")
        }
        c.prepareStatement(sql).use {
            var i = 1
            it.setString(i++, accountId)
            if (before != null) it.setLong(i++, before)
            if (q != null) {
                val like = "%" + q.replace("\\", "").replace("%", "").replace("_", "") + "%"
                it.setString(i++, like)
                it.setString(i++, like)
            }
            it.setInt(i, limit + 1)
            val plays = it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toPlay()) } }
            plays.take(limit) to (plays.size > limit)
        }
    }

    /** Everything, oldest first (for export). */
    fun all(accountId: String): List<HistoryEntry> = db.use { c ->
        c.prepareStatement("SELECT * FROM plays WHERE account_id = ? ORDER BY played_at").use {
            it.setString(1, accountId)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toPlay()) } }
        }
    }

    fun delete(accountId: String, id: String): Boolean = db.use { c ->
        c.prepareStatement("DELETE FROM plays WHERE account_id = ? AND id = ?").use {
            it.setString(1, accountId)
            it.setString(2, id)
            it.executeUpdate() > 0
        }
    }

    /** Deletes plays from [from] (inclusive) to [to] (exclusive); null ends are open. Returns how many. */
    fun deleteRange(accountId: String, from: Long?, to: Long?): Int = db.use { c ->
        val sql = buildString {
            append("DELETE FROM plays WHERE account_id = ?")
            if (from != null) append(" AND played_at >= ?")
            if (to != null) append(" AND played_at < ?")
        }
        c.prepareStatement(sql).use {
            var i = 1
            it.setString(i++, accountId)
            if (from != null) it.setLong(i++, from)
            if (to != null) it.setLong(i, to)
            it.executeUpdate()
        }
    }

    private fun ResultSet.toPlay() = HistoryEntry(
        id = getString("id"),
        song = ProtocolJson.decodeFromString(Song.serializer(), getString("song")),
        playedAt = getLong("played_at"),
        heardMs = getLong("heard_ms"),
        skipped = getBoolean("skipped"),
        roomName = getString("room_name"),
        addedByMe = getBoolean("added_by_me"),
        addedByName = getString("added_by_name"),
        listenedWith = ProtocolJson.decodeFromString(listenedWith, getString("listened_with")),
        shared = getBoolean("shared"),
    )

    private companion object {
        const val MAX_LISTENED_WITH = 100
    }
}
