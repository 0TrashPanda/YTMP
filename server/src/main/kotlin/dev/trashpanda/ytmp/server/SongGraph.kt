package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.SongMatch
import dev.trashpanda.ytmp.protocol.HistoryRadio
import dev.trashpanda.ytmp.protocol.PlayReport
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.Song
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.pow

/**
 * The song graph (docs/features/history-and-recommendations.md#song-graph): which songs are
 * played after which, learned from the accounts' listening history, and which songs YouTube
 * Music's radios put together. Edges keep raw counts; their weight is worked out when the graph
 * is read ([similar]), so the formula can change without touching the data. Everything but
 * YouTube Music's edges can be rebuilt from the history ([rebuild]).
 * See docs/implementation/storage.md#song-graph.
 */
class SongGraph(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    /** Rebuilds and learning from radios, off the caller's thread, one at a time. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "song-graph").apply { isDaemon = true } }
    private val rebuildPending = AtomicBoolean(false)
    /** Seeds whose YouTube Music radio was learned lately (song ID → when): a radio asked again isn't written again. */
    private val learnedRadios = ConcurrentHashMap<String, Long>()

    /** One song heard by an account, as far as the graph cares. */
    private class Heard(val song: Song, val playedAt: Long, val heardMs: Long, val skipped: Boolean, val roomCode: String, val shared: Boolean)

    // --- learning from listening ----------------------------------------------------------

    /**
     * [accountId] heard [report], just added to their history: the song before it (in the same
     * room, not long before) gets an edge to it, in their graph and, once per play in a room
     * (several people hear it), in the server's.
     */
    @Synchronized
    fun played(accountId: String, report: PlayReport) = db.transaction { c ->
        val current = Heard(report.song, report.playedAt, report.heardMs, report.skipped, report.roomCode, report.shared)
        val before = c.prepareStatement(
            "SELECT song, played_at, heard_ms, skipped, room_code, shared FROM plays WHERE account_id = ? AND played_at < ? ORDER BY played_at DESC LIMIT 1",
        ).use {
            it.setString(1, accountId)
            it.setLong(2, report.playedAt)
            it.executeQuery().use { rows -> if (rows.next()) rows.toHeard() else null }
        } ?: return@transaction
        if (!follows(before, current)) return@transaction
        val othersHeardIt = c.prepareStatement("SELECT COUNT(*) FROM plays WHERE room_code = ? AND played_at = ? AND song_id = ? AND account_id <> ?").use {
            it.setString(1, report.roomCode.take(16))
            it.setLong(2, report.playedAt)
            it.setString(3, report.song.id.take(256))
            it.setString(4, accountId)
            it.executeQuery().use { rows -> rows.next() && rows.getInt(1) > 0 }
        }
        val from = node(c, before.song)
        val to = node(c, current.song)
        if (from == to) return@transaction
        val counts = counts(current)
        addEdge(c, Edge(from, to, PERSONAL, accountId), counts, current.playedAt)
        if (!othersHeardIt) addEdge(c, Edge(from, to, SERVER, ""), counts, current.playedAt)
    }

    /** Builds the graph from the history again (not YouTube Music's edges): after deleting from it, or when the graph is new. */
    @Synchronized
    fun rebuild() = db.transaction { c ->
        val started = System.currentTimeMillis()
        c.createStatement().use { it.executeUpdate("DELETE FROM song_edges WHERE scope <> '$YTM'") }
        val nodes = HashMap<String, String>()
        val edges = HashMap<Edge, Counts>()
        // A play several accounts heard (same room, same start, same song) counts once for the server.
        val counted = HashSet<String>()
        var plays = 0
        c.prepareStatement("SELECT account_id, song, played_at, heard_ms, skipped, room_code, shared FROM plays ORDER BY account_id, played_at").use { statement ->
            statement.executeQuery().use { rows ->
                var account: String? = null
                var before: Heard? = null
                while (rows.next()) {
                    plays++
                    val id = rows.getString("account_id")
                    val current = rows.toHeard()
                    if (id != account) {
                        account = id
                        before = null
                    }
                    val previous = before
                    before = current
                    if (previous == null || !follows(previous, current)) continue
                    val from = nodes.getOrPut(previous.song.id) { node(c, previous.song, update = false) }
                    val to = nodes.getOrPut(current.song.id) { node(c, current.song, update = false) }
                    if (from == to) continue
                    val counts = counts(current)
                    edges.merge(Edge(from, to, PERSONAL, id), counts, Counts::plus)
                    if (counted.add("${current.roomCode}|${current.playedAt}|${current.song.id}")) edges.merge(Edge(from, to, SERVER, ""), counts, Counts::plus)
                }
            }
        }
        for ((edge, counts) in edges) addEdge(c, edge, counts, counts.lastAt)
        log.info("Song graph built from {} plays: {} edges, in {} ms", plays, edges.size, System.currentTimeMillis() - started)
    }

    /** [rebuild], soon and off this thread (deleting a lot from the history at once asks just once). */
    fun rebuildSoon() {
        if (rebuildPending.compareAndSet(false, true)) {
            worker.execute {
                rebuildPending.set(false)
                runCatching { rebuild() }.onFailure { log.warn("Couldn't rebuild the song graph", it) }
            }
        }
    }

    /** Builds the graph from the history when it's new (or its format changed), off this thread. */
    fun buildIfNeeded(accounts: Accounts) {
        worker.execute {
            if (accounts.setting(VERSION_SETTING) == VERSION.toString()) return@execute
            runCatching {
                rebuild()
                accounts.setSetting(VERSION_SETTING, VERSION.toString())
            }.onFailure { log.warn("Couldn't build the song graph", it) }
        }
    }

    // --- learning from YouTube Music ------------------------------------------------------

    /**
     * A radio YouTube Music made from [seedSongId] (it starts with the seed): edges from the seed
     * to its songs, ranked by their place in it. Written off this thread.
     */
    fun learnRadio(seedSongId: String, songs: List<Song>) {
        val now = clock()
        val last = learnedRadios[seedSongId]
        if (last != null && now - last < RADIO_RELEARN_MS) return
        if (learnedRadios.size > 5_000) learnedRadios.clear()
        learnedRadios[seedSongId] = now
        worker.execute {
            runCatching {
                synchronized(this) {
                    db.transaction { c ->
                        val seed = songs.firstOrNull { it.id == seedSongId }?.let { node(c, it) } ?: nodeOf(c, seedSongId) ?: return@transaction
                        songs.filter { it.id != seedSongId && it.podcast == null }.take(MAX_YTM_EDGES).forEachIndexed { rank, song ->
                            val to = node(c, song)
                            if (to != seed) addYtmEdge(c, seed, to, rank, now)
                        }
                    }
                }
            }.onFailure { log.warn("Couldn't learn from a radio: {}", it.message) }
        }
    }

    /** Waits until what's queued off the caller's thread ([learnRadio], [rebuildSoon]) is done. For tests. */
    internal fun awaitIdle() {
        worker.submit {}.get()
    }

    // --- reading --------------------------------------------------------------------------

    /**
     * Songs that go with [seedSongId], best first: its neighbours in the graph of [accountId]
     * (null: only the server's and YouTube Music's), as far as [use] allows, and their neighbours
     * (weaker). Each in the seed's source when the graph knows it there (a local file stays local).
     */
    fun similar(seedSongId: String, accountId: String?, use: HistoryRadio = HistoryRadio.ALL, limit: Int = SIMILAR_LIMIT): List<Song> = db.use { c ->
        val seed = nodeOf(c, seedSongId) ?: return@use emptyList()
        val personal = accountId?.takeIf { use != HistoryRadio.OFF }
        val now = clock()
        val scores = HashMap<String, Double>()
        for (edge in edges(c, listOf(seed), personal, both = true)) {
            val other = if (edge.from == seed) edge.to else edge.from
            scores.merge(other, edge.weight(use, now) * (if (edge.from == seed) 1.0 else REVERSE), Double::plus)
        }
        scores.remove(seed)
        val near = scores.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(SECOND_HOP_FROM)
        val best = near.firstOrNull()?.value
        if (best != null) {
            val strength = near.associate { it.key to it.value / best }
            for (edge in edges(c, strength.keys.toList(), personal, both = false)) {
                if (edge.to != seed) scores.merge(edge.to, strength.getValue(edge.from) * edge.weight(use, now) * SECOND_HOP, Double::plus)
            }
        }
        val ranked = scores.entries.filter { it.value >= MIN_SCORE }.sortedByDescending { it.value }.take(limit * 2).map { it.key }
        songs(c, ranked, prefer = seedSongId.substringBefore(':')).filter { it.id != seedSongId }.take(limit)
    }

    // --- nodes and edges ------------------------------------------------------------------

    private data class Edge(val from: String, val to: String, val scope: String, val account: String)

    private data class Counts(val solo: Int, val shared: Int, val skips: Int, val lastAt: Long) {
        operator fun plus(other: Counts) = Counts(solo + other.solo, shared + other.shared, skips + other.skips, max(lastAt, other.lastAt))
    }

    private class StoredEdge(val from: String, val to: String, val scope: String, val solo: Int, val shared: Int, val skips: Int, val ytmRank: Int?, val updatedAt: Long) {
        fun weight(use: HistoryRadio, now: Long): Double {
            val ageDays = max(0L, now - updatedAt) / 86_400_000.0
            val decay = 0.5.pow(ageDays / HALF_LIFE_DAYS)
            return when (scope) {
                PERSONAL -> PERSONAL_WEIGHT * (solo + (if (use == HistoryRadio.ALL) shared * SHARED_WEIGHT else 0.0) - skips * SKIP_WEIGHT) * decay
                SERVER -> (solo + shared * SHARED_WEIGHT - skips * SKIP_WEIGHT) * decay
                else -> YTM_WEIGHT * max(0.2, 1 - (ytmRank ?: MAX_YTM_EDGES) / MAX_YTM_EDGES.toDouble()) * decay
            }
        }
    }

    /** Was [current] played right after [before]: same room, not long after, and [before] not skipped right away. */
    private fun follows(before: Heard, current: Heard): Boolean =
        before.roomCode == current.roomCode &&
            current.playedAt > before.playedAt &&
            current.playedAt - (before.playedAt + before.heardMs) <= GAP_MS &&
            !(before.skipped && before.heardMs < EARLY_SKIP_MS) &&
            before.song.podcast == null && current.song.podcast == null

    /** A song skipped before half of it (or 30 s) was heard counts against the edge to it; otherwise it's a play. */
    private fun counts(h: Heard): Counts = when {
        h.skipped && h.heardMs < max(EARLY_SKIP_MS, h.song.durationMs / 2) -> Counts(0, 0, 1, h.playedAt)
        h.shared -> Counts(0, 1, 0, h.playedAt)
        else -> Counts(1, 0, 0, h.playedAt)
    }

    private fun java.sql.ResultSet.toHeard() = Heard(
        ProtocolJson.decodeFromString(Song.serializer(), getString("song")),
        getLong("played_at"), getLong("heard_ms"), getBoolean("skipped"), getString("room_code"), getBoolean("shared"),
    )

    private fun nodeOf(c: Connection, sourceId: String): String? =
        c.prepareStatement("SELECT song_id FROM song_sources WHERE source_id = ?").use {
            it.setString(1, sourceId.take(256))
            it.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
        }

    /**
     * The node of [song]: the one it's a source of, or the same song from another source
     * ([SongMatch]), or a new one. [update]: keep the song's latest title, art, … for this source.
     */
    private fun node(c: Connection, song: Song, update: Boolean = true): String {
        val sourceId = song.id.take(256)
        val data = ProtocolJson.encodeToString(Song.serializer(), song)
        nodeOf(c, sourceId)?.let { id ->
            if (update) {
                c.prepareStatement("UPDATE song_sources SET data = ?, updated_at = ? WHERE source_id = ?").use {
                    it.setString(1, data)
                    it.setLong(2, clock())
                    it.setString(3, sourceId)
                    it.executeUpdate()
                }
            }
            return id
        }
        val key = SongMatch.key(song).take(600)
        val same = c.prepareStatement("SELECT id, duration_ms FROM songs WHERE match_key = ?").use {
            it.setString(1, key)
            it.executeQuery().use { rows ->
                var found: String? = null
                while (found == null && rows.next()) if (SongMatch.sameLength(rows.getLong(2), song.durationMs)) found = rows.getString(1)
                found
            }
        }
        val id = same ?: Ids.random(12).also { id ->
            c.prepareStatement("INSERT INTO songs (id, match_key, duration_ms) VALUES (?, ?, ?)").use {
                it.setString(1, id)
                it.setString(2, key)
                it.setLong(3, song.durationMs)
                it.executeUpdate()
            }
        }
        c.prepareStatement("INSERT INTO song_sources (source_id, song_id, data, updated_at) VALUES (?, ?, ?, ?)").use {
            it.setString(1, sourceId)
            it.setString(2, id)
            it.setString(3, data)
            it.setLong(4, clock())
            it.executeUpdate()
        }
        return id
    }

    private fun addEdge(c: Connection, edge: Edge, counts: Counts, at: Long) {
        c.prepareStatement(
            """INSERT INTO song_edges (from_song, to_song, scope, account_id, solo_plays, shared_plays, skips, ytm_rank, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?)
               ON CONFLICT (from_song, to_song, scope, account_id) DO UPDATE SET
                 solo_plays = song_edges.solo_plays + excluded.solo_plays,
                 shared_plays = song_edges.shared_plays + excluded.shared_plays,
                 skips = song_edges.skips + excluded.skips,
                 updated_at = CASE WHEN excluded.updated_at > song_edges.updated_at THEN excluded.updated_at ELSE song_edges.updated_at END""",
        ).use {
            it.setString(1, edge.from)
            it.setString(2, edge.to)
            it.setString(3, edge.scope)
            it.setString(4, edge.account)
            it.setInt(5, counts.solo)
            it.setInt(6, counts.shared)
            it.setInt(7, counts.skips)
            it.setLong(8, at)
            it.executeUpdate()
        }
    }

    private fun addYtmEdge(c: Connection, from: String, to: String, rank: Int, at: Long) {
        c.prepareStatement(
            """INSERT INTO song_edges (from_song, to_song, scope, account_id, solo_plays, shared_plays, skips, ytm_rank, updated_at)
               VALUES (?, ?, '$YTM', '', 0, 0, 0, ?, ?)
               ON CONFLICT (from_song, to_song, scope, account_id) DO UPDATE SET
                 ytm_rank = CASE WHEN excluded.ytm_rank < song_edges.ytm_rank THEN excluded.ytm_rank ELSE song_edges.ytm_rank END,
                 updated_at = excluded.updated_at""",
        ).use {
            it.setString(1, from)
            it.setString(2, to)
            it.setInt(3, rank)
            it.setLong(4, at)
            it.executeUpdate()
        }
    }

    /** Edges from [nodes] (and, with [both], to them): the server's, YouTube Music's, and [account]'s own. */
    private fun edges(c: Connection, nodes: List<String>, account: String?, both: Boolean): List<StoredEdge> {
        if (nodes.isEmpty()) return emptyList()
        val list = nodes.joinToString(", ") { "?" }
        val sql = buildString {
            append("SELECT from_song, to_song, scope, solo_plays, shared_plays, skips, ytm_rank, updated_at FROM song_edges WHERE ")
            append(if (both) "(from_song IN ($list) OR to_song IN ($list))" else "from_song IN ($list)")
            append(" AND (scope IN ('$SERVER', '$YTM')")
            if (account != null) append(" OR (scope = '$PERSONAL' AND account_id = ?)")
            append(")")
        }
        return c.prepareStatement(sql).use { statement ->
            var i = 1
            repeat(if (both) 2 else 1) { nodes.forEach { node -> statement.setString(i++, node) } }
            if (account != null) statement.setString(i, account)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        val rank = rows.getInt("ytm_rank").takeUnless { rows.wasNull() }
                        add(
                            StoredEdge(
                                rows.getString("from_song"), rows.getString("to_song"), rows.getString("scope"),
                                rows.getInt("solo_plays"), rows.getInt("shared_plays"), rows.getInt("skips"), rank, rows.getLong("updated_at"),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** A song for each of [nodes], in order: from the [prefer]red source (`ytm`, `local`, …) when there is one, else YouTube Music's. */
    private fun songs(c: Connection, nodes: List<String>, prefer: String): List<Song> {
        if (nodes.isEmpty()) return emptyList()
        val sources = HashMap<String, MutableList<Song>>()
        c.prepareStatement("SELECT song_id, data FROM song_sources WHERE song_id IN (${nodes.joinToString(", ") { "?" }})").use {
            nodes.forEachIndexed { i, node -> it.setString(i + 1, node) }
            it.executeQuery().use { rows ->
                while (rows.next()) {
                    val song = runCatching { ProtocolJson.decodeFromString(Song.serializer(), rows.getString("data")) }.getOrNull() ?: continue
                    sources.getOrPut(rows.getString("song_id")) { mutableListOf() } += song
                }
            }
        }
        return nodes.mapNotNull { node ->
            val versions = sources[node] ?: return@mapNotNull null
            versions.firstOrNull { it.id.startsWith("$prefer:") } ?: versions.firstOrNull { it.id.startsWith("ytm:") } ?: versions.first()
        }.filter { it.podcast == null }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SongGraph::class.java)

        /** Bump to build the graph from the history again on the next start (a new way of learning). */
        const val VERSION = 1
        private const val VERSION_SETTING = "song_graph_version"

        // Edge scopes (docs/implementation/storage.md#song-graph).
        const val PERSONAL = "personal"
        const val SERVER = "server"
        const val YTM = "ytm"

        /** A song started this long after the one before ended still follows it. */
        private const val GAP_MS = 30 * 60_000L
        /** A song skipped within this long says nothing about what comes after it. */
        private const val EARLY_SKIP_MS = 30_000L
        /** YouTube Music's radio of a song is written at most this often. */
        private const val RADIO_RELEARN_MS = 6 * 3_600_000L
        /** The first this many songs of a YouTube Music radio become edges. */
        private const val MAX_YTM_EDGES = 25

        // The weights (to tune once there's real data, see docs/open-questions.md).
        private const val PERSONAL_WEIGHT = 2.0
        private const val SHARED_WEIGHT = 0.6
        private const val SKIP_WEIGHT = 1.0
        private const val YTM_WEIGHT = 1.5
        /** An edge to the seed (it's played before it) counts this much of one from it. */
        private const val REVERSE = 0.5
        /** Neighbours of the seed's best neighbours count this much. */
        private const val SECOND_HOP = 0.35
        private const val SECOND_HOP_FROM = 8
        private const val MIN_SCORE = 0.05
        private const val HALF_LIFE_DAYS = 180.0
        const val SIMILAR_LIMIT = 50
    }
}
