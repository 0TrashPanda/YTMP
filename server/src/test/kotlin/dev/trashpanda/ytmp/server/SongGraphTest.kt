package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.core.SongMatch
import dev.trashpanda.ytmp.host.TakeoutSong
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.EdgeKind
import dev.trashpanda.ytmp.protocol.GraphTuning
import dev.trashpanda.ytmp.protocol.RadioOptions
import dev.trashpanda.ytmp.protocol.RadioWay
import dev.trashpanda.ytmp.protocol.HistoryRadio
import dev.trashpanda.ytmp.protocol.PlayReport
import dev.trashpanda.ytmp.protocol.PodcastRef
import dev.trashpanda.ytmp.protocol.Song
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SongGraphTest {
    private val db = Database.open(DatabaseConfig(path = Files.createTempDirectory("ytmp").resolve("ytmp.db").toString()))
    private val history = History(db)
    private var now = 1_000_000_000_000L
    private val graph = SongGraph(db) { now }

    private fun song(id: String, title: String = "Song $id", artist: String = "Artist", podcast: Boolean = false) =
        Song(id, title, listOf(ArtistRef(null, artist)), null, 200_000, emptyList(), podcast = if (podcast) PodcastRef("p", "Podcast") else null)

    /** Clock of the plays, minutes apart unless said otherwise. */
    private var at = 1_000_000_000_000L

    /** [account] hears [song] in [room] (as the hosts report it), and the graph learns it. */
    private fun play(account: String, song: Song, room: String = "SOLO", skippedAt: Long? = null, shared: Boolean = false, gapMs: Long = 0, playedAt: Long? = null) {
        val start = playedAt ?: (at + gapMs).also { at = it + (skippedAt ?: song.durationMs) }
        val report = PlayReport(song, start, skippedAt ?: song.durationMs, skippedAt != null, room, "Room", true, "Me", emptyList(), shared)
        synchronized(graph) {
            history.add(account, report)
            graph.played(account, report)
        }
    }

    private fun similar(seed: String, account: String? = "anna", use: HistoryRadio = HistoryRadio.ALL) = graph.similar(seed, account, use).map { it.id }

    private fun edges(scope: String): Map<String, List<Int>> = db.use { c ->
        c.prepareStatement(
            """SELECT a.source_id AS f, b.source_id AS t, solo_plays, shared_plays, skips FROM song_edges e
               JOIN song_sources a ON a.song_id = e.from_song JOIN song_sources b ON b.song_id = e.to_song WHERE scope = ? ORDER BY f, t""",
        ).use {
            it.setString(1, scope)
            it.executeQuery().use { rows ->
                buildMap { while (rows.next()) put("${rows.getString("f")}>${rows.getString("t")}", listOf(rows.getInt(3), rows.getInt(4), rows.getInt(5))) }
            }
        }
    }

    @Test
    fun `songs played after each other become neighbours, skips count against them`() {
        play("anna", song("ytm:a"))
        play("anna", song("ytm:b"))
        play("anna", song("ytm:a"))
        play("anna", song("ytm:c"), skippedAt = 10_000)
        play("anna", song("ytm:a"))
        play("anna", song("ytm:b"))

        assertEquals(mapOf("ytm:a>ytm:b" to listOf(2, 0, 0), "ytm:b>ytm:a" to listOf(1, 0, 0), "ytm:a>ytm:c" to listOf(0, 0, 1)), edges(SongGraph.PERSONAL))
        // b follows a; c was skipped right away, and nothing follows a skip like that.
        assertEquals(listOf("ytm:b"), similar("ytm:a"))
        // Played before it counts too (a bit less).
        assertEquals(listOf("ytm:a"), similar("ytm:b"))
        assertEquals(emptyList(), similar("ytm:unknown"))
    }

    @Test
    fun `only songs in the same room, not long after each other, and no podcast episodes`() {
        play("anna", song("ytm:a"))
        play("anna", song("ytm:b"), room = "OTHER")
        play("anna", song("ytm:c"), room = "OTHER", gapMs = 2 * 3_600_000)
        play("anna", song("ytm:d", podcast = true), room = "OTHER")
        play("anna", song("ytm:e"), room = "OTHER")
        assertEquals(emptyMap(), edges(SongGraph.PERSONAL))
    }

    @Test
    fun `a play several accounts heard counts once for the server, and a party counts less`() {
        val partyStart = at
        play("anna", song("ytm:a"), room = "PARTY", shared = true, playedAt = partyStart)
        play("bob", song("ytm:a"), room = "PARTY", shared = true, playedAt = partyStart)
        play("anna", song("ytm:b"), room = "PARTY", shared = true, playedAt = partyStart + 200_000)
        play("bob", song("ytm:b"), room = "PARTY", shared = true, playedAt = partyStart + 200_000)

        assertEquals(mapOf("ytm:a>ytm:b" to listOf(0, 1, 0)), edges(SongGraph.SERVER))
        assertEquals(2, db.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM song_edges WHERE scope = 'personal'").use { it.next(); it.getInt(1) } } })
        // Solo only: Anna's party doesn't count for her, but everyone's listening still does.
        assertEquals(listOf("ytm:b"), similar("ytm:a", use = HistoryRadio.SOLO))
        assertEquals(listOf("ytm:b"), similar("ytm:a", account = null))
    }

    @Test
    fun `rebuilding from the history gives the same graph`() {
        play("anna", song("ytm:a"))
        play("anna", song("ytm:b"))
        play("anna", song("ytm:c"), skippedAt = 60_000)
        play("bob", song("ytm:a"))
        play("bob", song("ytm:b"))
        val personal = edges(SongGraph.PERSONAL)
        val server = edges(SongGraph.SERVER)
        graph.rebuild()
        assertEquals(personal, edges(SongGraph.PERSONAL))
        assertEquals(server, edges(SongGraph.SERVER))
        assertEquals(mapOf("ytm:a>ytm:b" to listOf(2, 0, 0), "ytm:b>ytm:c" to listOf(0, 0, 1)), server)
    }

    @Test
    fun `a local file is the same song as on YouTube Music, and YouTube Music's radio counts`() {
        graph.learnRadio("ytm:a", listOf(song("ytm:a", "Café (Remastered)", "Band"), song("ytm:x"), song("ytm:y")))
        graph.awaitIdle()
        // Played from the music folder: the same song.
        play("anna", song("local:pi:song:a.flac", "Cafe", "band"))
        play("anna", song("local:pi:song:b.flac"))

        val fromLocal = similar("local:pi:song:a.flac")
        assertEquals("local:pi:song:b.flac", fromLocal.first())
        assertTrue("ytm:x" in fromLocal && "ytm:y" in fromLocal)
        // From the YouTube Music song: the same neighbours.
        assertEquals(fromLocal.toSet(), similar("ytm:a").toSet())
    }

    @Test
    fun `old listening counts less`() {
        play("anna", song("ytm:a"))
        play("anna", song("ytm:old"))
        now += 2 * 365 * 86_400_000L
        at = now
        play("anna", song("ytm:a"))
        play("anna", song("ytm:new"))
        assertEquals(listOf("ytm:new", "ytm:old"), similar("ytm:a"))
    }

    @Test
    fun `radios are kept raw, an account's radio only counts for that account, and the graph can be built again from them`() {
        graph.learnRadio("ytm:a", listOf(song("ytm:a"), song("ytm:x"), song("ytm:y")))
        graph.learnRadio("ytm:a", listOf(song("ytm:a"), song("ytm:z")), accountId = "anna")
        graph.awaitIdle()
        assertEquals(listOf("ytm:x", "ytm:y"), similar("ytm:a", account = "bert"))
        assertTrue("ytm:z" in similar("ytm:a", account = "anna"))
        assertEquals(2, db.use { c -> c.createStatement().use { it.executeQuery("SELECT COUNT(*) FROM radio_fetches").use { r -> r.next(); r.getInt(1) } } })

        graph.rebuild()
        assertEquals(listOf("ytm:x", "ytm:y"), similar("ytm:a", account = "bert"))
        assertTrue("ytm:z" in similar("ytm:a", account = "anna"))
        // Tuned to nothing from YouTube Music, and only the first place of a radio: no songs, or only the first.
        assertEquals(emptyList(), graph.similar("ytm:a", "bert", tuning = GraphTuning(ytm = 0.0)).map { it.id })
        assertEquals(listOf("ytm:x"), graph.similar("ytm:a", "bert", tuning = GraphTuning(radioDepth = 1)).map { it.id })
        // The crawl keeps every radio it fetched, also right after another.
        graph.learnRadio("ytm:a", listOf(song("ytm:x")), way = RadioWay.CRAWL)
        graph.awaitIdle()
        assertEquals(now, graph.lastFetched(listOf("ytm:a"))["ytm:a"])
    }

    @Test
    fun `a Takeout import joins the history without what's already there, and the graph learns it as imported`() {
        play("anna", song("ytm:a"), playedAt = now - 50_000_000)
        val takeout = listOf(
            // YTMP put this play in YouTube Music's history when it ended: it's the same one.
            TakeoutSong("a", "Song ytm:a", "Artist", listOf(now - 50_000_000 + 200_000)),
            TakeoutSong("b", "B", "Artist", listOf(now - 40_000_000, now - 30_000_000)),
            TakeoutSong("c", "C", "Artist", listOf(now - 40_000_000 + 180_000, now - 30_000_000 + 10_000)),
        )
        val made = history.importTakeout("anna", takeout)
        assertEquals(4, made.plays)
        assertEquals(1, made.duplicates)
        assertEquals(made.id, history.imports("anna").single().id)
        graph.rebuild()
        // b > c twice, the second time c came 10 s later: b was skipped right away, so that one says nothing.
        assertEquals(mapOf("ytm:b>ytm:c" to listOf(1, 0, 0)), edges(SongGraph.IMPORTED))
        assertEquals(emptyMap(), edges(SongGraph.PERSONAL))
        assertEquals(listOf("ytm:c"), similar("ytm:b"))
        assertTrue(history.page("anna", null, 10, null).first.any { it.imported })

        val hints = graph.hints("ytm:b", "anna", listOf("ytm:c", "ytm:q"), RadioOptions(), HistoryRadio.ALL, GraphTuning(), 10)
        assertEquals(listOf("ytm:c"), hints.heard)

        history.deleteImport("anna", made.id)
        graph.rebuild()
        assertEquals(emptyMap(), edges(SongGraph.IMPORTED))
    }

    @Test
    fun `songs close together in a radio go together, and confirm a Takeout link that happened once`() {
        // Imported: b → c once (a one-off), d → e once.
        history.importTakeout(
            "anna",
            listOf(
                TakeoutSong("b", "B", "Artist", listOf(now - 10_000_000)),
                TakeoutSong("c", "C", "Artist", listOf(now - 10_000_000 + 180_000)),
                TakeoutSong("d", "D", "Artist", listOf(now - 5_000_000)),
                TakeoutSong("e", "E", "Artist", listOf(now - 5_000_000 + 180_000)),
            ),
        )
        graph.rebuild()
        assertEquals(listOf("ytm:c"), similar("ytm:b"))
        /** Whether the imported link around [seed] (there's one) is confirmed. */
        fun confirmed(seed: String) = graph.view("anna", seed, GraphTuning()).edges.single { it.kind == EdgeKind.IMPORTED }.confirmed
        assertEquals(false, confirmed("ytm:b"))

        // A radio of something else has b and c next to each other: that confirms b → c, not d → e.
        graph.learnRadio("ytm:z", listOf(song("ytm:z"), song("ytm:b", "B"), song("ytm:c", "C"), song("ytm:q")))
        graph.awaitIdle()
        assertEquals(true, confirmed("ytm:b"))
        assertEquals(false, confirmed("ytm:d"))
        // b and q are 2 apart in that radio: neighbours, also the other way round.
        assertTrue("ytm:q" in similar("ytm:b"))
        assertTrue("ytm:b" in similar("ytm:q"))
        // Without near links and one-offs counting nothing, d has no songs left; b still has c, confirmed.
        val none = GraphTuning(radioNear = 0.0, oneOff = 0.0)
        assertEquals(emptyList(), graph.similar("ytm:d", "anna", tuning = none).map { it.id })
        assertEquals(listOf("ytm:c"), graph.similar("ytm:b", "anna", tuning = none.copy(ytm = 0.0)).map { it.id })

        // Built again from what's kept: the same.
        graph.rebuild()
        assertEquals(true, confirmed("ytm:b"))
        assertTrue("ytm:q" in similar("ytm:b"))
    }

    /** The "soon after" edges of [scope]: from>to to how often, distances added up. */
    private fun nearEdges(scope: String): Map<String, List<Int>> = db.use { c ->
        c.prepareStatement(
            """SELECT a.source_id AS f, b.source_id AS t, seen, rank_sum FROM song_edges e
               JOIN song_sources a ON a.song_id = e.from_song JOIN song_sources b ON b.song_id = e.to_song WHERE scope = ? ORDER BY f, t""",
        ).use {
            it.setString(1, scope)
            it.executeQuery().use { rows -> buildMap { while (rows.next()) put("${rows.getString("f")}>${rows.getString("t")}", listOf(rows.getInt(3), rows.getInt(4))) } }
        }
    }

    @Test
    fun `songs played 2 or 3 later go together too, weaker, in a session, and confirm one-off Takeout links`() {
        // a b c d in one session, then e much later (another session).
        listOf("a", "b", "c", "d").forEach { play("anna", song("ytm:$it")) }
        play("anna", song("ytm:e"), gapMs = 3 * 3_600_000L)
        val live = nearEdges(SongGraph.PERSONAL_NEAR)
        assertEquals(mapOf("ytm:a>ytm:c" to listOf(1, 2), "ytm:a>ytm:d" to listOf(1, 3), "ytm:b>ytm:d" to listOf(1, 2)), live)
        graph.rebuild()
        assertEquals(live, nearEdges(SongGraph.PERSONAL_NEAR))
        // c is a neighbour of a (2 later), weaker than b (right after); off with the slider.
        assertEquals(listOf("ytm:b", "ytm:c", "ytm:d"), similar("ytm:a").take(3))
        assertTrue("ytm:c" !in graph.similar("ytm:a", "anna", tuning = GraphTuning(listenNear = 0.0, secondHop = 0.0)).map { it.id })

        // A one-off Takeout link x → z, and z twice 2 songs after x elsewhere in Takeout: confirmed.
        val t = now - 20_000_000
        history.importTakeout(
            "anna",
            listOf(
                TakeoutSong("x", "X", "Artist", listOf(t, t + 3_600_000, t + 7_200_000)),
                TakeoutSong("z", "Z", "Artist", listOf(t + 180_000, t + 3_600_000 + 360_000, t + 7_200_000 + 360_000)),
                TakeoutSong("y", "Y", "Artist", listOf(t + 3_600_000 + 180_000, t + 7_200_000 + 180_000)),
            ),
        )
        graph.rebuild()
        assertEquals(listOf(2, 4), nearEdges(SongGraph.IMPORTED_NEAR)["ytm:x>ytm:z"])
        assertEquals(true, graph.view("anna", "ytm:x", GraphTuning()).edges.single { it.kind == EdgeKind.IMPORTED && it.to != it.from && it.solo == 1 && it.confirmed }.confirmed)
    }

    @Test
    fun `a phone's copy is the whole graph as the account sees it, compact`() {
        listOf("a", "b", "c").forEach { play("anna", song("ytm:$it")) }
        play("bert", song("ytm:x"))
        play("bert", song("ytm:y"))
        val full = graph.full("anna", HistoryRadio.ALL, GraphTuning())
        val ids = full.nodes.map { it.sources.single() }
        fun kinds(from: String, to: String) =
            full.edges.filter { ids[it[0].toInt()] == from && ids[it[1].toInt()] == to }.map { EdgeKind.entries[it[2].toInt()] }.toSet()
        assertEquals(setOf(EdgeKind.PERSONAL, EdgeKind.SERVER), kinds("ytm:a", "ytm:b"))
        assertEquals(setOf(EdgeKind.PERSONAL_NEAR), kinds("ytm:a", "ytm:c"))
        // Bert's own links aren't in Anna's copy; everyone's are.
        assertEquals(setOf(EdgeKind.SERVER), kinds("ytm:x", "ytm:y"))
        assertEquals(SongMatch.key(song("ytm:a")), full.nodes[ids.indexOf("ytm:a")].key)
    }

    @Test
    fun `a play reported twice is kept once, and the graph learns it once`() {
        play("anna", song("ytm:a"))
        val report = PlayReport(song("ytm:b"), at + 1_000, 200_000, false, "SOLO", "Room", true, "Me", emptyList(), false)
        assertTrue(history.add("anna", report))
        graph.played("anna", report)
        assertFalse(history.add("anna", report))
        assertEquals(2, history.page("anna", null, 10, null).first.size)
        assertEquals(mapOf("ytm:a>ytm:b" to listOf(1, 0, 0)), edges(SongGraph.PERSONAL))
    }

    /** Rock (r0–r4) and pop (p0–p4) played round and round, and once from r0 straight to p0. */
    private fun twoKindsOfMusic() {
        repeat(3) {
            for (i in 0 until 5) play("anna", song("ytm:r$i", artist = "Rock band"))
            play("anna", song("ytm:r0", artist = "Rock band"), gapMs = 3_600_000)
        }
        repeat(3) {
            for (i in 0 until 5) play("anna", song("ytm:p$i", artist = "Pop star"), gapMs = if (i == 0) 3_600_000 else 0)
        }
        play("anna", song("ytm:r0", artist = "Rock band"), gapMs = 3_600_000)
        play("anna", song("ytm:p0", artist = "Pop star"))
    }

    @Test
    fun `the whole graph is laid out in groups, with how much each link counts from your listening, everyone else's and radios`() {
        twoKindsOfMusic()
        // Bob hears two of the rock songs after each other, and a song Anna never played.
        play("bob", song("ytm:r1", artist = "Rock band"), gapMs = 3_600_000)
        play("bob", song("ytm:r2", artist = "Rock band"))
        play("bob", song("ytm:x", artist = "Someone else"))
        val map = graph.map("anna", GraphTuning())
        assertEquals(11, map.nodes.size)
        assertEquals(map.from.size, map.mine.size)
        val groupOf = map.nodes.associate { it.id to it.group }
        assertEquals(1, (0 until 5).map { groupOf["ytm:r$it"] }.distinct().size)
        assertTrue(groupOf["ytm:r0"] != groupOf["ytm:p0"])
        assertEquals("Rock band", map.groups[groupOf.getValue("ytm:r0")].artists.first())
        assertTrue(map.nodes.all { it.x.isFinite() && it.y.isFinite() })
        fun link(a: String, b: String): Int {
            val i = map.nodes.indexOfFirst { it.id == a }
            val j = map.nodes.indexOfFirst { it.id == b }
            return map.from.indices.first { (map.from[it] == i && map.to[it] == j) || (map.from[it] == j && map.to[it] == i) }
        }
        // r1 → r2: Anna's and Bob's; r2 → x: only Bob's; r3 → r4: only Anna's (not again as the server's). No radios.
        assertTrue(map.mine[link("ytm:r1", "ytm:r2")] > 0 && map.others[link("ytm:r1", "ytm:r2")] > 0)
        assertEquals(0.0, map.mine[link("ytm:r2", "ytm:x")])
        assertTrue(map.others[link("ytm:r2", "ytm:x")] > 0)
        assertEquals(0.0, map.others[link("ytm:r3", "ytm:r4")])
        assertTrue(map.radios.all { it == 0.0 })

        // A radio from r4: its own group's songs, and the song of the one jump away after them, if at all.
        graph.findGroupsNow("anna")
        val radio = similar("ytm:r4")
        assertEquals((0 until 4).map { "ytm:r$it" }.toSet(), radio.filter { it.startsWith("ytm:r") }.toSet())
        assertTrue("ytm:p0" !in radio || radio.indexOf("ytm:p0") > (0 until 4).maxOf { radio.indexOf("ytm:r$it") })
    }

    @Test
    fun `groups are kept over a restart and made again once the graph changed, the map is reused till then`() {
        twoKindsOfMusic()
        val seed = "ytm:r0"
        // Asked for the first time: none yet, worked out off the caller's thread.
        assertEquals(null, graph.view("anna", seed, GraphTuning()).nodes.first().group)
        graph.awaitIdle()
        assertTrue(graph.view("anna", seed, GraphTuning()).nodes.all { it.group != null })
        // A restart: they're there at once.
        val again = SongGraph(db) { now }
        assertTrue(again.view("anna", seed, GraphTuning()).nodes.all { it.group != null })

        val map = again.map("anna", GraphTuning())
        assertTrue(map === again.map("anna", GraphTuning()))
        // A full rebuild (an import, a deletion): a new map, the songs where they were.
        again.rebuild()
        val next = again.map("anna", GraphTuning())
        assertTrue(map !== next)
        assertEquals(map.nodes.associate { it.id to (it.x to it.y) }.keys, next.nodes.map { it.id }.toSet())
        val moved = map.nodes.zip(next.nodes).maxOf { (a, b) -> kotlin.math.hypot(a.x - b.x, a.y - b.y) }
        val size = map.nodes.maxOf { kotlin.math.hypot(it.x, it.y) }
        assertTrue(moved < size / 2, "moved $moved on a map of $size")

        // About the same: no rebuild, and at most 2% (or 50) more plays and radios.
        val made = SongGraph.MadeFrom(3, 10_000)
        assertTrue(made.stillFor(SongGraph.MadeFrom(3, 10_200)))
        assertFalse(made.stillFor(SongGraph.MadeFrom(3, 10_201)))
        assertFalse(made.stillFor(SongGraph.MadeFrom(4, 10_000)))
        assertTrue(SongGraph.MadeFrom(3, 100).stillFor(SongGraph.MadeFrom(3, 150)))
        assertEquals(made, SongGraph.MadeFrom.parse(made.toString()))
    }
}
