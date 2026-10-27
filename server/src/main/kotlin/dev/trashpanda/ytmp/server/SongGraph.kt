package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.GraphRank
import dev.trashpanda.ytmp.core.SongMatch
import dev.trashpanda.ytmp.protocol.EdgeKind
import dev.trashpanda.ytmp.protocol.GraphBuildStatus
import dev.trashpanda.ytmp.protocol.GraphEdge
import dev.trashpanda.ytmp.protocol.GraphFull
import dev.trashpanda.ytmp.protocol.GraphFullNode
import dev.trashpanda.ytmp.protocol.GraphNode
import dev.trashpanda.ytmp.protocol.GraphTuning
import dev.trashpanda.ytmp.protocol.GraphView
import dev.trashpanda.ytmp.protocol.HistoryRadio
import dev.trashpanda.ytmp.protocol.PlayReport
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.RadioHints
import dev.trashpanda.ytmp.protocol.RadioOptions
import dev.trashpanda.ytmp.protocol.RadioWay
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The song graph (docs/features/history-and-recommendations.md#song-graph): which songs are
 * played after which, learned from the accounts' listening history (also imported from Google
 * Takeout), and which songs YouTube Music's radios put together. What it learns from is kept raw
 * (the `plays`, and every radio fetched in `radio_fetches`: when, how, as whom); the edges are
 * built from that and keep counts, and their weight is worked out when the graph is read, with
 * the account's [GraphTuning]. So the graph can be weighed and built again without fetching
 * anything ([rebuild]). See docs/implementation/storage.md#song-graph.
 */
class SongGraph(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    /** Rebuilds and learning from radios, off the caller's thread, one at a time. */
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "song-graph").apply { isDaemon = true } }
    private val rebuildPending = AtomicBoolean(false)

    /** A rebuild under way: what it does now, and how far it got in all (0–1). */
    private class Building(val startedAt: Long) {
        @Volatile var step = "Reading your listening"
        @Volatile var fraction = 0.0
    }
    @Volatile private var building: Building? = null
    @Volatile private var builtAt: Long? = null

    /** Whether the graph is being built again (or will be soon), and how far it got: the pages' progress bar. */
    fun buildStatus(): GraphBuildStatus {
        val b = building
        return GraphBuildStatus(building = b != null, waiting = rebuildPending.get(), step = b?.step, fraction = b?.fraction ?: 0.0, startedAt = b?.startedAt, builtAt = builtAt)
    }
    /** Radios learned lately (seed and account → when): a radio asked again isn't written again. */
    private val learnedRadios = ConcurrentHashMap<String, Long>()
    private val ids = ListSerializer(String.serializer())

    /** One song heard by an account, as far as the graph cares. */
    private class Heard(val song: Song, val playedAt: Long, val heardMs: Long, val skipped: Boolean, val roomCode: String, val shared: Boolean, val imported: Boolean)

    // --- learning from listening ----------------------------------------------------------

    /**
     * [accountId] heard [report], just added to their history: the song before it (in the same
     * room, not long before) gets an edge to it, in their graph and, once per play in a room
     * (several people hear it), in the server's.
     */
    @Synchronized
    fun played(accountId: String, report: PlayReport) = db.transaction { c ->
        val current = Heard(report.song, report.playedAt, report.heardMs, report.skipped, report.roomCode, report.shared, imported = false)
        // The songs before it, newest first: the one right before, and for "soon after" the two before that.
        val earlier = c.prepareStatement(
            "SELECT song, played_at, heard_ms, skipped, room_code, shared, import_id FROM plays WHERE account_id = ? AND played_at < ? ORDER BY played_at DESC LIMIT $NEAR_LISTEN",
        ).use {
            it.setString(1, accountId)
            it.setLong(2, report.playedAt)
            it.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.toHeard()) } }
        }
        val chain = ArrayList<Heard>()
        for (h in earlier) if (inSession(h, chain.lastOrNull() ?: current)) chain += h else break
        val soon = soonAfter(chain.reversed(), current)
        for ((h, distance) in soon) {
            val from = node(c, h.song)
            val to = node(c, current.song)
            if (from != to) addNearEdge(c, Edge(from, to, if (current.imported) IMPORTED_NEAR else PERSONAL_NEAR, accountId), NearCounts.of(current, distance))
        }
        // Heard twice soon after each other now: that may confirm a one-off Takeout link.
        if (soon.isNotEmpty()) confirm(c)
        val before = earlier.firstOrNull() ?: return@transaction
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

    /**
     * Builds the graph again from what it learned from (not the radios from before they were kept
     * raw): after deleting from the history, an import, or when the graph is new.
     */
    @Synchronized
    fun rebuild() {
        val b = Building(clock())
        building = b
        try {
            rebuild(b)
        } finally {
            building = null
            builtAt = clock()
        }
    }

    private fun rebuild(b: Building) = db.transaction { c ->
        val started = System.currentTimeMillis()
        val total = c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM plays").use { if (it.next()) it.getInt(1) else 0 } }
        c.createStatement().use { it.executeUpdate("DELETE FROM song_edges WHERE scope NOT IN ('$YTM', '$YTM_ACCOUNT') OR seen > 0") }
        val nodes = HashMap<String, String>()
        val edges = HashMap<Edge, Counts>()
        val near = HashMap<Edge, NearCounts>()
        // A play several accounts heard (same room, same start, same song) counts once for the server.
        val counted = HashSet<String>()
        var plays = 0
        c.prepareStatement("SELECT account_id, song, played_at, heard_ms, skipped, room_code, shared, import_id FROM plays ORDER BY account_id, played_at").use { statement ->
            statement.executeQuery().use { rows ->
                var account: String? = null
                var before: Heard? = null
                // The last few songs of this listening session, oldest first.
                val chain = ArrayDeque<Heard>()
                while (rows.next()) {
                    plays++
                    // Reading the plays is the first third or so.
                    if (plays % PROGRESS_EVERY == 0) b.fraction = READ_SHARE * plays / total.coerceAtLeast(1)
                    val id = rows.getString("account_id")
                    val current = rows.toHeard()
                    if (id != account) {
                        account = id
                        before = null
                        chain.clear()
                    }
                    if (chain.isNotEmpty() && !inSession(chain.last(), current)) chain.clear()
                    for ((h, distance) in soonAfter(chain, current)) {
                        val from = nodes.getOrPut(h.song.id) { node(c, h.song, update = false) }
                        val to = nodes.getOrPut(current.song.id) { node(c, current.song, update = false) }
                        if (from != to) near.merge(Edge(from, to, if (current.imported) IMPORTED_NEAR else PERSONAL_NEAR, id), NearCounts.of(current, distance), NearCounts::plus)
                    }
                    chain.addLast(current)
                    if (chain.size > NEAR_LISTEN) chain.removeFirst()
                    val previous = before
                    before = current
                    if (previous == null || !follows(previous, current)) continue
                    val from = nodes.getOrPut(previous.song.id) { node(c, previous.song, update = false) }
                    val to = nodes.getOrPut(current.song.id) { node(c, current.song, update = false) }
                    if (from == to) continue
                    val counts = counts(current)
                    edges.merge(Edge(from, to, if (current.imported) IMPORTED else PERSONAL, id), counts, Counts::plus)
                    // Everyone's imported links are each account's, added up when read (see [edges]).
                    if (!current.imported && counted.add("${current.roomCode}|${current.playedAt}|${current.song.id}")) {
                        edges.merge(Edge(from, to, SERVER, ""), counts, Counts::plus)
                    }
                }
            }
        }
        b.step = "Saving what goes together"
        val saving = (edges.size + near.size).coerceAtLeast(1)
        var saved = 0
        fun savedOne() {
            if (++saved % PROGRESS_EVERY == 0) b.fraction = READ_SHARE + SAVE_SHARE * saved / saving
        }
        for ((edge, counts) in edges) addEdge(c, edge, counts, counts.lastAt).also { savedOne() }
        for ((edge, counts) in near) addNearEdge(c, edge, counts).also { savedOne() }
        b.step = "Adding YouTube Music's radios"
        b.fraction = READ_SHARE + SAVE_SHARE
        val radios = rebuildRadios(c, nodes, b)
        log.info("Song graph built from {} plays and {} radios: {} edges and {} soon after, in {} ms", plays, radios, edges.size, near.size, System.currentTimeMillis() - started)
    }

    /** The edges of every kept radio fetch. Returns how many fetches. */
    private fun rebuildRadios(c: Connection, nodes: HashMap<String, String>, b: Building): Int {
        val total = c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM radio_fetches").use { if (it.next()) it.getInt(1) else 0 } }.coerceAtLeast(1)
        class Ytm(var seen: Int = 0, var best: Int = Int.MAX_VALUE, var rankSum: Int = 0, var at: Long = 0)
        val edges = HashMap<Edge, Ytm>()
        var fetches = 0
        c.prepareStatement("SELECT seed, fetched_at, account_id, songs FROM radio_fetches").use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    fetches++
                    if (fetches % 20 == 0) b.fraction = READ_SHARE + SAVE_SHARE + (1 - READ_SHARE - SAVE_SHARE) * fetches / total
                    val seed = nodeOf(c, rows.getString("seed")) ?: continue
                    val account = rows.getString("account_id")
                    val at = rows.getLong("fetched_at")
                    val songIds = runCatching { ProtocolJson.decodeFromString(ids, rows.getString("songs")) }.getOrDefault(emptyList())
                    val placed = songIds.map { sourceId -> nodes[sourceId] ?: nodeOf(c, sourceId)?.also { nodes[sourceId] = it } }
                    fun count(edge: Edge, rank: Int, value: Int) {
                        val e = edges.getOrPut(edge) { Ytm() }
                        e.seen++
                        e.best = min(e.best, rank)
                        e.rankSum += value
                        e.at = max(e.at, at)
                    }
                    placed.forEachIndexed { rank, to ->
                        if (to != null && to != seed) count(Edge(seed, to, if (account.isEmpty()) YTM else YTM_ACCOUNT, account), rank, rank)
                    }
                    for ((edge, rank, distance) in nearLinks(placed, account)) count(edge, rank, distance)
                }
            }
        }
        for ((edge, e) in edges) addYtmEdge(c, edge, e.seen, e.best, e.rankSum, e.at)
        confirm(c)
        return fetches
    }

    /**
     * Songs close together in a radio ([placed]: its songs' nodes, in order) go together too:
     * each to the next [NEAR_WINDOW], with the later one's place and how far apart. Weak links.
     */
    private fun nearLinks(placed: List<String?>, account: String): List<Triple<Edge, Int, Int>> = buildList {
        for (i in placed.indices) {
            val a = placed[i] ?: continue
            for (j in i + 1..min(placed.lastIndex, i + NEAR_WINDOW)) {
                val b = placed[j] ?: continue
                // Its place is the later song's: both are within "how far down a radio counts".
                if (a != b) add(Triple(Edge(a, b, RADIO_NEAR, account), j, j - i))
            }
        }
    }

    /**
     * Takeout links that happened once are only "confirmed" (count fully) when a YouTube Music
     * radio also puts the two songs together: a one-off from metal to lo-fi rarely is.
     */
    private fun confirm(c: Connection) {
        c.createStatement().use {
            it.executeUpdate(
                """UPDATE song_edges SET confirmed = 1 WHERE confirmed = 0 AND scope = '$IMPORTED' AND EXISTS (
                     SELECT 1 FROM song_edges r WHERE (r.scope IN ('$YTM', '$YTM_ACCOUNT', '$RADIO_NEAR')
                       OR (r.scope IN ('$PERSONAL_NEAR', '$IMPORTED_NEAR') AND r.account_id = song_edges.account_id AND r.seen >= 2))
                     AND ((r.from_song = song_edges.from_song AND r.to_song = song_edges.to_song) OR (r.from_song = song_edges.to_song AND r.to_song = song_edges.from_song)))""",
            )
        }
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
     * A radio YouTube Music made from [seedSongId] (it may start with the seed), as [accountId]
     * (their sign-in; null: without an account), fetched [way]: kept as it is, and edges from the
     * seed to its songs, ranked by their place in it. [reportedBy]: the account whose phone sent
     * it (null: this server fetched it). Written off this thread.
     */
    fun learnRadio(seedSongId: String, songs: List<Song>, accountId: String? = null, way: RadioWay = RadioWay.ROOM, reportedBy: String? = null) {
        val now = clock()
        val key = "$seedSongId|${accountId.orEmpty()}"
        val last = learnedRadios[key]
        // The radio crawl always keeps what it fetched: it asks again only when it's time to.
        if (way != RadioWay.CRAWL && last != null && now - last < RADIO_RELEARN_MS) return
        if (learnedRadios.size > 5_000) learnedRadios.clear()
        learnedRadios[key] = now
        val music = songs.filter { it.podcast == null && it.id != seedSongId }.take(MAX_RADIO_SONGS)
        if (music.isEmpty()) return
        worker.execute {
            runCatching {
                synchronized(this) {
                    db.transaction { c ->
                        // A seed the graph doesn't know yet (only its ID): the radio is kept, its edges come with a rebuild.
                        val seed = songs.firstOrNull { it.id == seedSongId }?.let { node(c, it) } ?: nodeOf(c, seedSongId)
                        val sourceIds = music.map { song -> song.id.take(256).also { node(c, song) } }
                        c.prepareStatement("INSERT INTO radio_fetches (id, seed, fetched_at, way, account_id, reported_by, songs) VALUES (?, ?, ?, ?, ?, ?, ?)").use {
                            it.setString(1, Ids.random(16))
                            it.setString(2, seedSongId.take(256))
                            it.setLong(3, now)
                            it.setString(4, way.name.lowercase())
                            it.setString(5, accountId.orEmpty())
                            it.setString(6, reportedBy.orEmpty())
                            it.setString(7, ProtocolJson.encodeToString(ids, sourceIds))
                            it.executeUpdate()
                        }
                        val placed = music.map { nodeOf(c, it.id) }
                        for ((edge, rank, distance) in nearLinks(placed, accountId.orEmpty())) addYtmEdge(c, edge, 1, rank, distance, now)
                        if (seed != null) {
                            val scope = if (accountId == null) YTM else YTM_ACCOUNT
                            placed.forEachIndexed { rank, to ->
                                if (to != null && to != seed) addYtmEdge(c, Edge(seed, to, scope, accountId.orEmpty()), 1, rank, rank, now)
                            }
                        }
                        confirm(c)
                    }
                }
            }.onFailure { log.warn("Couldn't learn from a radio: {}", it.message) }
        }
    }

    /** When each of [seeds] last had a radio fetched without an account (the radio crawl). */
    fun lastFetched(seeds: Collection<String>): Map<String, Long> = db.use { c ->
        val out = HashMap<String, Long>()
        c.prepareStatement("SELECT seed, MAX(fetched_at) FROM radio_fetches WHERE account_id = '' GROUP BY seed").use {
            it.executeQuery().use { rows -> while (rows.next()) out[rows.getString(1)] = rows.getLong(2) }
        }
        out.filterKeys { it in seeds }
    }

    /** Waits until what's queued off the caller's thread ([learnRadio], [rebuildSoon]) is done. For tests. */
    internal fun awaitIdle() {
        worker.submit {}.get()
    }

    // --- reading --------------------------------------------------------------------------

    /**
     * Songs that go with [seedSongId], best first: its neighbours in the graph of [accountId]
     * (null: only the server's and YouTube Music's), as far as [use] allows, weighed with
     * [tuning], and their neighbours (weaker). Each in the seed's source when the graph knows it
     * there (a local file stays local).
     */
    fun similar(
        seedSongId: String,
        accountId: String?,
        use: HistoryRadio = HistoryRadio.ALL,
        limit: Int = SIMILAR_LIMIT,
        tuning: GraphTuning = GraphTuning(),
    ): List<Song> = db.use { c -> similar(c, seedSongId, accountId, use, limit, tuning) }

    private fun similar(c: Connection, seedSongId: String, accountId: String?, use: HistoryRadio, limit: Int, tuning: GraphTuning): List<Song> {
        val seed = nodeOf(c, seedSongId) ?: return emptyList()
        val ranked = ranked(c, seed, accountId, use, tuning).take(limit * 2)
        return songs(c, ranked, prefer = seedSongId.substringBefore(':')).filter { it.id != seedSongId }.take(limit)
    }

    /**
     * The whole graph as [accountId] sees it (their own links, everyone's, YouTube Music's), for a
     * phone's copy ([GraphFull]): every song with its versions, title-and-artist key and length.
     */
    fun full(accountId: String?, use: HistoryRadio, tuning: GraphTuning): GraphFull = db.use { c ->
        val edges = edges(c, null, accountId, use, both = false)
        val used = HashSet<String>()
        for (e in edges) {
            used += e.from
            used += e.to
        }
        val sources = HashMap<String, MutableList<String>>()
        c.createStatement().use { s -> s.executeQuery("SELECT source_id, song_id FROM song_sources").use { rows -> while (rows.next()) if (rows.getString(2) in used) sources.getOrPut(rows.getString(2)) { mutableListOf() } += rows.getString(1) } }
        val nodes = ArrayList<GraphFullNode>()
        val index = HashMap<String, Int>()
        c.createStatement().use { s ->
            s.executeQuery("SELECT id, match_key, duration_ms FROM songs").use { rows ->
                while (rows.next()) {
                    val id = rows.getString(1)
                    if (id !in used) continue
                    index[id] = nodes.size
                    nodes += GraphFullNode(sources[id].orEmpty(), rows.getString(2), rows.getLong(3))
                }
            }
        }
        val kinds = EdgeKind.entries
        GraphFull(
            now = clock(),
            tuning = tuning,
            use = use,
            nodes = nodes,
            edges = edges.mapNotNull { e ->
                val from = index[e.from] ?: return@mapNotNull null
                val to = index[e.to] ?: return@mapNotNull null
                listOf(
                    from.toLong(), to.toLong(), kinds.indexOf(kindOf(e.scope)).toLong(), e.solo.toLong(), e.shared.toLong(), e.skips.toLong(),
                    e.seen.toLong(), (e.ytmRank ?: -1).toLong(), e.rankSum.toLong(), e.updatedAt, if (e.confirmed) 1L else 0L,
                )
            },
        )
    }

    /** The nodes that go with [seed], best first (enough to pass [MIN_SCORE]). */
    private fun ranked(c: Connection, seed: String, accountId: String?, use: HistoryRadio, tuning: GraphTuning): List<String> =
        GraphRank.rank(seed, { nodes -> edges(c, nodes, accountId, use, both = true).map { it.toGraphEdge() } }, use, tuning, clock())

    /**
     * What the graph says about a radio from [seed] for [accountId]: its songs, and of those and
     * [candidates] (YouTube Music's radio) which they heard and which to leave out ([RadioOptions]).
     */
    fun hints(seed: String, accountId: String?, candidates: List<String>, options: RadioOptions, use: HistoryRadio, tuning: GraphTuning, limit: Int): RadioHints = db.use { c ->
        val similar = similar(c, seed, accountId?.takeIf { options.myTaste && use != HistoryRadio.OFF }, use, limit, tuning)
        if (accountId == null) return@use RadioHints(similar)
        val ids = (candidates + similar.map { it.id }).distinct().take(MAX_HINT_SONGS)
        val stats = playStats(c, accountId, ids)
        val now = clock()
        val avoid = stats.filter { (_, s) ->
            (options.leaveOutSkipped && s.skips >= 2 && s.skips > s.plays - s.skips) ||
                (options.recentHours > 0 && now - s.lastAt < options.recentHours * 3_600_000L)
        }.keys
        RadioHints(similar, heard = stats.filterValues { it.plays > it.skips }.keys.toList(), avoid = avoid.toList())
    }

    private class PlayStats(var plays: Int = 0, var skips: Int = 0, var lastAt: Long = 0)

    /** How often [accountId] played and skipped each of [songIds] (also as the same song from another source). */
    private fun playStats(c: Connection, accountId: String, songIds: List<String>): Map<String, PlayStats> {
        if (songIds.isEmpty()) return emptyMap()
        // The sources of the same songs: a local file heard counts for its YouTube Music song too.
        val nodeOfSource = HashMap<String, String>()
        inList(c, "SELECT source_id, song_id FROM song_sources WHERE source_id", songIds) { nodeOfSource[it.getString(1)] = it.getString(2) }
        val sourcesOfNode = HashMap<String, MutableList<String>>()
        inList(c, "SELECT source_id, song_id FROM song_sources WHERE song_id", nodeOfSource.values.distinct()) {
            sourcesOfNode.getOrPut(it.getString(2)) { mutableListOf() } += it.getString(1)
        }
        val all = (songIds + sourcesOfNode.values.flatten()).distinct()
        val bySource = HashMap<String, PlayStats>()
        c.prepareStatement(
            "SELECT song_id, COUNT(*), SUM(CASE WHEN skipped THEN 1 ELSE 0 END), MAX(played_at) FROM plays WHERE account_id = ? AND song_id IN (${all.joinToString(", ") { "?" }}) GROUP BY song_id",
        ).use {
            it.setString(1, accountId)
            all.forEachIndexed { i, id -> it.setString(i + 2, id) }
            it.executeQuery().use { rows -> while (rows.next()) bySource[rows.getString(1)] = PlayStats(rows.getInt(2), rows.getInt(3), rows.getLong(4)) }
        }
        return songIds.mapNotNull { id ->
            val versions = nodeOfSource[id]?.let { sourcesOfNode[it] } ?: listOf(id)
            val stats = versions.mapNotNull(bySource::get)
            if (stats.isEmpty()) null
            else id to PlayStats(stats.sumOf { it.plays }, stats.sumOf { it.skips }, stats.maxOf { it.lastAt })
        }.toMap()
    }

    /**
     * The graph as [accountId] sees it, for the graph view: around [seedSongId] (its neighbours
     * and theirs), or without one their most played songs and the edges between them. Raw counts:
     * the page weighs them with the sliders.
     */
    fun view(accountId: String, seedSongId: String?, tuning: GraphTuning, use: HistoryRadio = HistoryRadio.ALL): GraphView = db.use { c ->
        val now = clock()
        val seed = seedSongId?.let { nodeOf(c, it) }
        val nodes: List<String>
        val edges: List<StoredEdge>
        if (seed != null) {
            val first = edges(c, listOf(seed), accountId, use, both = true)
            val score = HashMap<String, Double>()
            for (e in first) score.merge(if (e.from == seed) e.to else e.from, weight(e, use, tuning, now), Double::plus)
            val near = score.entries.sortedByDescending { it.value }.take(VIEW_NEIGHBOURS).map { it.key }
            val second = edges(c, near.take(VIEW_SECOND_FROM), accountId, use, both = false)
            val score2 = HashMap<String, Double>()
            for (e in second) if (e.to != seed && e.to !in near) score2.merge(e.to, weight(e, use, tuning, now), Double::plus)
            val far = score2.entries.sortedByDescending { it.value }.take(VIEW_SECOND).map { it.key }
            nodes = listOf(seed) + near + far
            val keep = nodes.toSet()
            edges = (first + second).filter { it.from in keep && it.to in keep }
        } else {
            val top = mostPlayed(c, accountId, VIEW_TOP)
            nodes = top
            val keep = top.toSet()
            edges = edges(c, top, accountId, use, both = false).filter { it.to in keep }
        }
        val songs = nodeSongs(c, nodes)
        val plays = nodePlays(c, accountId, nodes)
        GraphView(
            seed = seed,
            nodes = nodes.mapNotNull { n -> songs[n]?.let { (song, sources) -> GraphNode(n, song, plays[n] ?: 0, sources.any { it.startsWith("local:") }, sources) } },
            edges = edges.distinctBy { listOf(it.from, it.to, it.scope) }.map { it.toGraphEdge() },
            tuning = tuning,
            now = now,
        )
    }

    /** [accountId]'s most played songs (nodes), most first. */
    private fun mostPlayed(c: Connection, accountId: String, limit: Int): List<String> {
        val counts = HashMap<String, Int>()
        c.prepareStatement("SELECT song_id, COUNT(*) FROM plays WHERE account_id = ? AND skipped = ? GROUP BY song_id").use {
            it.setString(1, accountId)
            it.setBoolean(2, false)
            it.executeQuery().use { rows -> while (rows.next()) counts[rows.getString(1)] = rows.getInt(2) }
        }
        val byNode = HashMap<String, Int>()
        val top = counts.entries.sortedByDescending { it.value }.take(limit * 3)
        val nodes = HashMap<String, String>()
        inList(c, "SELECT source_id, song_id FROM song_sources WHERE source_id", top.map { it.key }) { nodes[it.getString(1)] = it.getString(2) }
        for ((source, n) in top) nodes[source]?.let { byNode.merge(it, n, Int::plus) }
        return byNode.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    /** How often [accountId] heard each of [nodes] (any of its sources). */
    private fun nodePlays(c: Connection, accountId: String, nodes: List<String>): Map<String, Int> {
        val sources = HashMap<String, String>()
        inList(c, "SELECT source_id, song_id FROM song_sources WHERE song_id", nodes) { sources[it.getString(1)] = it.getString(2) }
        if (sources.isEmpty()) return emptyMap()
        val out = HashMap<String, Int>()
        c.prepareStatement("SELECT song_id, COUNT(*) FROM plays WHERE account_id = ? AND song_id IN (${sources.keys.joinToString(", ") { "?" }}) GROUP BY song_id").use {
            it.setString(1, accountId)
            sources.keys.forEachIndexed { i, s -> it.setString(i + 2, s) }
            it.executeQuery().use { rows -> while (rows.next()) sources[rows.getString(1)]?.let { n -> out.merge(n, rows.getInt(2), Int::plus) } }
        }
        return out
    }

    /** A song for each node (YouTube Music's version with a cover first), and all its versions' IDs. */
    private fun nodeSongs(c: Connection, nodes: List<String>): Map<String, Pair<Song, List<String>>> {
        val versions = HashMap<String, MutableList<Song>>()
        inList(c, "SELECT song_id, data FROM song_sources WHERE song_id", nodes) { rows ->
            runCatching { ProtocolJson.decodeFromString(Song.serializer(), rows.getString(2)) }.getOrNull()
                ?.let { versions.getOrPut(rows.getString(1)) { mutableListOf() } += it }
        }
        return versions.mapValues { (_, songs) ->
            val best = songs.firstOrNull { it.id.startsWith("ytm:") && it.thumbnails.isNotEmpty() }
                ?: songs.firstOrNull { it.thumbnails.isNotEmpty() } ?: songs.firstOrNull { it.id.startsWith("ytm:") } ?: songs.first()
            best to songs.map { it.id }
        }
    }

    // --- nodes and edges ------------------------------------------------------------------

    private data class Edge(val from: String, val to: String, val scope: String, val account: String)

    /** "Soon after" links: how often alone and in a party, the distances added up. */
    private data class NearCounts(val solo: Int, val shared: Int, val distance: Int, val lastAt: Long) {
        operator fun plus(other: NearCounts) = NearCounts(solo + other.solo, shared + other.shared, distance + other.distance, max(lastAt, other.lastAt))

        companion object {
            fun of(h: Heard, distance: Int) = if (h.shared) NearCounts(0, 1, distance, h.playedAt) else NearCounts(1, 0, distance, h.playedAt)
        }
    }

    private data class Counts(val solo: Int, val shared: Int, val skips: Int, val lastAt: Long) {
        operator fun plus(other: Counts) = Counts(solo + other.solo, shared + other.shared, skips + other.skips, max(lastAt, other.lastAt))
    }

    private data class StoredEdge(
        val from: String, val to: String, val scope: String, val solo: Int, val shared: Int, val skips: Int,
        val ytmRank: Int?, val seen: Int, val rankSum: Int, val updatedAt: Long, val confirmed: Boolean,
    ) {
        fun toGraphEdge() = GraphEdge(from, to, kindOf(scope), solo, shared, skips, seen, ytmRank, rankSum, updatedAt, confirmed)
    }

    /** What [e] counts, the way [tuning] says. Kept the same as the graph view's (frontend/src/lib/graph.ts). */
    private fun weight(e: StoredEdge, use: HistoryRadio, t: GraphTuning, now: Long) = GraphRank.weight(e.toGraphEdge(), use, t, now)

    /** In the same listening session: the same room, [current] not long after [before] ended, no podcast episodes. */
    private fun inSession(before: Heard, current: Heard): Boolean =
        before.roomCode == current.roomCode &&
            current.playedAt > before.playedAt &&
            current.playedAt - (before.playedAt + before.heardMs) <= GAP_MS &&
            before.song.podcast == null && current.song.podcast == null

    /**
     * The songs of [chain] (this session's last few, oldest first, ending right before
     * [current]) that [current] came "soon after": 2 or 3 songs later, with how far. Not
     * when either was skipped right away: that says nothing about how they go together.
     */
    private fun soonAfter(chain: List<Heard>, current: Heard): List<Pair<Heard, Int>> {
        if (current.skipped && current.heardMs < max(EARLY_SKIP_MS, current.song.durationMs / 2)) return emptyList()
        return chain.withIndex().mapNotNull { (i, h) ->
            val distance = chain.size - i
            (h to distance).takeIf { distance in 2..NEAR_LISTEN && !(h.skipped && h.heardMs < EARLY_SKIP_MS) }
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
        imported = getString("import_id").isNotEmpty(),
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

    /** A "soon after" edge: [counts] more (seen: how often in all; rank_sum: the distances). */
    private fun addNearEdge(c: Connection, edge: Edge, counts: NearCounts) {
        c.prepareStatement(
            """INSERT INTO song_edges (from_song, to_song, scope, account_id, solo_plays, shared_plays, skips, ytm_rank, seen, rank_sum, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, 0, NULL, ?, ?, ?)
               ON CONFLICT (from_song, to_song, scope, account_id) DO UPDATE SET
                 solo_plays = song_edges.solo_plays + excluded.solo_plays,
                 shared_plays = song_edges.shared_plays + excluded.shared_plays,
                 seen = song_edges.seen + excluded.seen,
                 rank_sum = song_edges.rank_sum + excluded.rank_sum,
                 updated_at = CASE WHEN excluded.updated_at > song_edges.updated_at THEN excluded.updated_at ELSE song_edges.updated_at END""",
        ).use {
            it.setString(1, edge.from)
            it.setString(2, edge.to)
            it.setString(3, edge.scope)
            it.setString(4, edge.account)
            it.setInt(5, counts.solo)
            it.setInt(6, counts.shared)
            it.setInt(7, counts.solo + counts.shared)
            it.setInt(8, counts.distance)
            it.setLong(9, counts.lastAt)
            it.executeUpdate()
        }
    }

    /** A YouTube Music edge seen [seen] more times, at best place [best], places adding up to [rankSum]. */
    private fun addYtmEdge(c: Connection, edge: Edge, seen: Int, best: Int, rankSum: Int, at: Long) {
        c.prepareStatement(
            """INSERT INTO song_edges (from_song, to_song, scope, account_id, solo_plays, shared_plays, skips, ytm_rank, seen, rank_sum, updated_at)
               VALUES (?, ?, ?, ?, 0, 0, 0, ?, ?, ?, ?)
               ON CONFLICT (from_song, to_song, scope, account_id) DO UPDATE SET
                 ytm_rank = CASE WHEN song_edges.ytm_rank IS NULL OR excluded.ytm_rank < song_edges.ytm_rank THEN excluded.ytm_rank ELSE song_edges.ytm_rank END,
                 seen = song_edges.seen + excluded.seen,
                 rank_sum = song_edges.rank_sum + excluded.rank_sum,
                 updated_at = CASE WHEN excluded.updated_at > song_edges.updated_at THEN excluded.updated_at ELSE song_edges.updated_at END""",
        ).use {
            it.setString(1, edge.from)
            it.setString(2, edge.to)
            it.setString(3, edge.scope)
            it.setString(4, edge.account)
            it.setInt(5, best)
            it.setInt(6, seen)
            it.setInt(7, rankSum)
            it.setLong(8, at)
            it.executeUpdate()
        }
    }

    /**
     * Edges from [nodes] (and, with [both], to them): the server's and YouTube Music's, and
     * [account]'s own (their listening as far as [use] allows, and their account's radios).
     */
    private fun edges(c: Connection, nodes: List<String>?, account: String?, use: HistoryRadio, both: Boolean): List<StoredEdge> {
        // Null: all of them (the phone's copy).
        if (nodes != null && nodes.isEmpty()) return emptyList()
        val list = nodes.orEmpty().joinToString(", ") { "?" }
        val own = buildList {
            if (use != HistoryRadio.OFF) addAll(listOf(PERSONAL, PERSONAL_NEAR, IMPORTED_NEAR))
            add(YTM_ACCOUNT)
            add(RADIO_NEAR)
        }
        val sql = buildString {
            append("SELECT from_song, to_song, scope, account_id, solo_plays, shared_plays, skips, ytm_rank, seen, rank_sum, updated_at, confirmed FROM song_edges WHERE ")
            append(
                when {
                    nodes == null -> "1 = 1"
                    both -> "(from_song IN ($list) OR to_song IN ($list))"
                    else -> "from_song IN ($list)"
                },
            )
            // Every account's imported links: they're everyone's too (and the account's own, when it may).
            append(" AND ((scope IN ('$SERVER', '$YTM', '$RADIO_NEAR') AND account_id = '') OR scope = '$IMPORTED'")
            if (account != null) append(" OR (scope IN (${own.joinToString(", ") { "'$it'" }}) AND account_id = ?)")
            append(")")
        }
        val out = ArrayList<StoredEdge>()
        val everyoneImported = HashMap<Pair<String, String>, StoredEdge>()
        c.prepareStatement(sql).use { statement ->
            var i = 1
            if (nodes != null) repeat(if (both) 2 else 1) { nodes.forEach { node -> statement.setString(i++, node) } }
            if (account != null) statement.setString(i, account)
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    val rank = rows.getInt("ytm_rank").takeUnless { rows.wasNull() }
                    val e = StoredEdge(
                        rows.getString("from_song"), rows.getString("to_song"), rows.getString("scope"),
                        rows.getInt("solo_plays"), rows.getInt("shared_plays"), rows.getInt("skips"), rank,
                        rows.getInt("seen"), rows.getInt("rank_sum"), rows.getLong("updated_at"), rows.getInt("confirmed") != 0,
                    )
                    if (e.scope != IMPORTED) {
                        out += e
                        continue
                    }
                    if (account != null && use != HistoryRadio.OFF && rows.getString("account_id") == account) out += e
                    everyoneImported.merge(e.from to e.to, e.copy(scope = SERVER_IMPORTED)) { a, b ->
                        a.copy(solo = a.solo + b.solo, skips = a.skips + b.skips, updatedAt = max(a.updatedAt, b.updatedAt), confirmed = a.confirmed || b.confirmed)
                    }
                }
            }
        }
        return out + everyoneImported.values
    }

    /** A song for each of [nodes], in order: from the [prefer]red source (`ytm`, `local`, …) when there is one, else YouTube Music's. */
    private fun songs(c: Connection, nodes: List<String>, prefer: String): List<Song> {
        if (nodes.isEmpty()) return emptyList()
        val sources = HashMap<String, MutableList<Song>>()
        inList(c, "SELECT song_id, data FROM song_sources WHERE song_id", nodes) { rows ->
            runCatching { ProtocolJson.decodeFromString(Song.serializer(), rows.getString("data")) }.getOrNull()
                ?.let { sources.getOrPut(rows.getString("song_id")) { mutableListOf() } += it }
        }
        return nodes.mapNotNull { node ->
            val versions = sources[node] ?: return@mapNotNull null
            versions.firstOrNull { it.id.startsWith("$prefer:") } ?: versions.firstOrNull { it.id.startsWith("ytm:") } ?: versions.first()
        }.filter { it.podcast == null }
    }

    /** Runs "[select] IN (…)" for [values], a few hundred at a time, calling [row] for each row. */
    private fun inList(c: Connection, select: String, values: List<String>, row: (java.sql.ResultSet) -> Unit) {
        for (chunk in values.distinct().chunked(500)) {
            if (chunk.isEmpty()) continue
            c.prepareStatement("$select IN (${chunk.joinToString(", ") { "?" }})").use {
                chunk.forEachIndexed { i, v -> it.setString(i + 1, v) }
                it.executeQuery().use { rows -> while (rows.next()) row(rows) }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(SongGraph::class.java)

        /** Bump to build the graph from the history again on the next start (a new way of learning). */
        const val VERSION = 4
        private const val VERSION_SETTING = "song_graph_version"

        // Edge scopes (`song_edges.scope`). account_id is '' for the server's and YouTube Music's without an account.
        const val PERSONAL = "personal"
        const val SERVER = "server"
        const val IMPORTED = "imported"
        const val SERVER_IMPORTED = "server_imported"
        const val YTM = "ytm"
        const val YTM_ACCOUNT = "ytm_account"
        /** Two songs close together in a YouTube Music radio (account_id: whose account made it, '' none). */
        const val RADIO_NEAR = "radio_near"
        /** A song 2 or 3 songs after another in your listening in YTMP, or imported from Takeout. */
        const val PERSONAL_NEAR = "personal_near"
        const val IMPORTED_NEAR = "imported_near"

        fun kindOf(scope: String) = when (scope) {
            PERSONAL -> EdgeKind.PERSONAL
            SERVER -> EdgeKind.SERVER
            IMPORTED -> EdgeKind.IMPORTED
            SERVER_IMPORTED -> EdgeKind.SERVER_IMPORTED
            YTM_ACCOUNT -> EdgeKind.YTM_ACCOUNT
            RADIO_NEAR -> EdgeKind.RADIO_NEAR
            PERSONAL_NEAR -> EdgeKind.PERSONAL_NEAR
            IMPORTED_NEAR -> EdgeKind.IMPORTED_NEAR
            else -> EdgeKind.YTM
        }

        /** A song started this long after the one before ended still follows it. */
        private const val GAP_MS = 30 * 60_000L
        /** A song skipped within this long says nothing about what comes after it. */
        private const val EARLY_SKIP_MS = 30_000L
        /** The same radio (seed and account) is written at most this often. */
        private const val RADIO_RELEARN_MS = 6 * 3_600_000L
        /** A YouTube Music radio's first this many songs are kept (all it sends at once). */
        const val MAX_RADIO_SONGS = 50
        /** In a radio, each song goes with this many after it. */
        const val NEAR_WINDOW = 3
        /** In listening, a song goes with the ones up to this many songs after it ("soon after": 2 and 3). */
        const val NEAR_LISTEN = 3
        // The progress bar: how much of a rebuild reading the plays and saving the links take.
        private const val READ_SHARE = 0.35
        private const val SAVE_SHARE = 0.5
        private const val PROGRESS_EVERY = 500
        private const val SECOND_HOP_FROM = 8
        private const val MIN_SCORE = 0.05
        private const val MAX_HINT_SONGS = 200
        const val SIMILAR_LIMIT = 50
        private const val VIEW_NEIGHBOURS = 24
        private const val VIEW_SECOND_FROM = 8
        private const val VIEW_SECOND = 24
        private const val VIEW_TOP = 60
    }
}
