package dev.trashpanda.ytmp.server

import dev.trashpanda.ytmp.protocol.ArtistRef
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
}
