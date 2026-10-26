package dev.trashpanda.ytmp.server

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
}
