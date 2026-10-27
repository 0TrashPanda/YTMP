package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.host.TakeoutSong
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.HistoryImport
import dev.trashpanda.ytmp.protocol.ListenedWith
import dev.trashpanda.ytmp.protocol.Thumbnail
import dev.trashpanda.ytmp.protocol.HistoryEntry
import dev.trashpanda.ytmp.protocol.PlayReport
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.serialization.builtins.ListSerializer
import java.sql.ResultSet
import kotlin.math.min

/** Accounts' listening history: one row per song heard, in the order played. */
class History(private val db: Database) {
    private val listenedWith = ListSerializer(ListenedWith.serializer())

    /** Keeps [report]; false when the history has it already (the same song, room and start: reported twice). */
    fun add(accountId: String, report: PlayReport): Boolean = db.use { c ->
        val already = c.prepareStatement("SELECT COUNT(*) FROM plays WHERE account_id = ? AND played_at = ? AND song_id = ? AND room_code = ?").use {
            it.setString(1, accountId)
            it.setLong(2, report.playedAt)
            it.setString(3, report.song.id.take(256))
            it.setString(4, report.roomCode.take(16))
            it.executeQuery().use { rows -> rows.next() && rows.getInt(1) > 0 }
        }
        if (already) return@use false
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
        true
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

    /**
     * YouTube Music plays from a Google Takeout export ([TakeoutSong]) into [accountId]'s
     * history, marked as imported: the ones it already has (YTMP adds its plays to YouTube Music's
     * history too) are left out. Takeout only says when a song started: how much was heard is
     * the time until the next one (a skip when that's under 30 s).
     */
    fun importTakeout(accountId: String, songs: List<TakeoutSong>): HistoryImport = db.transaction { c ->
        val plays = songs.flatMap { s -> s.plays.map { at -> at to s } }.sortedBy { it.first }
        require(plays.isNotEmpty()) { "There are no YouTube Music plays in this file" }
        // What the history has already, by song: a play YTMP added to YouTube Music shows in
        // Takeout when it ended, so up to a quarter of an hour after it started here.
        val have = HashMap<String, MutableList<Long>>()
        c.prepareStatement("SELECT song_id, played_at FROM plays WHERE account_id = ? AND played_at >= ? AND played_at <= ?").use {
            it.setString(1, accountId)
            it.setLong(2, plays.first().first - DUPLICATE_BEFORE_MS)
            it.setLong(3, plays.last().first + DUPLICATE_AFTER_MS)
            it.executeQuery().use { rows -> while (rows.next()) have.getOrPut(rows.getString(1)) { mutableListOf() } += rows.getLong(2) }
        }
        val id = Ids.random(16)
        var added = 0
        var duplicates = 0
        c.prepareStatement(
            """INSERT INTO plays (id, account_id, played_at, song_id, title, artists, song, heard_ms, skipped, room_code, room_name,
               added_by_me, added_by_name, listened_with, shared, import_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
        ).use { insert ->
            plays.forEachIndexed { i, (at, t) ->
                val songId = "ytm:${t.id}"
                if (have[songId].orEmpty().any { at - it in -DUPLICATE_AFTER_MS..DUPLICATE_BEFORE_MS }) {
                    duplicates++
                    return@forEachIndexed
                }
                val next = plays.getOrNull(i + 1)?.first
                val heard = if (next == null) 0 else min(next - at, MAX_IMPORTED_HEARD_MS)
                val song = Song(
                    songId, t.title, listOfNotNull(t.artist?.let { ArtistRef(null, it) }), null, 0,
                    listOf(Thumbnail("https://i.ytimg.com/vi/${t.id}/hqdefault.jpg", 480, 360)),
                )
                insert.setString(1, Ids.random(16))
                insert.setString(2, accountId)
                insert.setLong(3, at)
                insert.setString(4, songId.take(256))
                insert.setString(5, t.title.take(512))
                insert.setString(6, t.artist.orEmpty().take(512))
                insert.setString(7, ProtocolJson.encodeToString(Song.serializer(), song))
                insert.setLong(8, heard)
                insert.setBoolean(9, next != null && next - at < IMPORTED_SKIP_MS)
                insert.setString(10, IMPORT_ROOM)
                insert.setString(11, IMPORT_ROOM_NAME)
                insert.setBoolean(12, true)
                insert.setString(13, "")
                insert.setString(14, "[]")
                insert.setBoolean(15, false)
                insert.setString(16, id)
                insert.addBatch()
                added++
                if (added % 1_000 == 0) insert.executeBatch()
            }
            insert.executeBatch()
        }
        val made = HistoryImport(id, System.currentTimeMillis(), added, duplicates, plays.first().first, plays.last().first)
        // Nothing new (imported before): no import to list.
        if (added == 0) return@transaction made
        c.prepareStatement("INSERT INTO history_imports (id, account_id, at, plays, duplicates, first_at, last_at) VALUES (?, ?, ?, ?, ?, ?, ?)").use {
            it.setString(1, id)
            it.setString(2, accountId)
            it.setLong(3, made.at)
            it.setInt(4, made.plays)
            it.setInt(5, made.duplicates)
            it.setLong(6, made.firstAt)
            it.setLong(7, made.lastAt)
            it.executeUpdate()
        }
        made
    }

    /** [accountId]'s Takeout imports, newest first. */
    fun imports(accountId: String): List<HistoryImport> = db.use { c ->
        c.prepareStatement("SELECT * FROM history_imports WHERE account_id = ? ORDER BY at DESC").use {
            it.setString(1, accountId)
            it.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        add(HistoryImport(rows.getString("id"), rows.getLong("at"), rows.getInt("plays"), rows.getInt("duplicates"), rows.getLong("first_at"), rows.getLong("last_at")))
                    }
                }
            }
        }
    }

    /** Takes an import out of the history again, with its plays. */
    fun deleteImport(accountId: String, id: String): Boolean = db.transaction { c ->
        c.prepareStatement("DELETE FROM plays WHERE account_id = ? AND import_id = ?").use {
            it.setString(1, accountId)
            it.setString(2, id)
            it.executeUpdate()
        }
        c.prepareStatement("DELETE FROM history_imports WHERE account_id = ? AND id = ?").use {
            it.setString(1, accountId)
            it.setString(2, id)
            it.executeUpdate() > 0
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
        imported = getString("import_id").isNotEmpty(),
    )

    companion object {
        private const val MAX_LISTENED_WITH = 100
        /** The room imported plays are in (so they only follow each other in the song graph). */
        const val IMPORT_ROOM = "takeout"
        const val IMPORT_ROOM_NAME = "YouTube Music"
        private const val DUPLICATE_BEFORE_MS = 15 * 60_000L
        private const val DUPLICATE_AFTER_MS = 2 * 60_000L
        private const val MAX_IMPORTED_HEARD_MS = 15 * 60_000L
        private const val IMPORTED_SKIP_MS = 30_000L
    }
}
