package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.SongMatch
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.EdgeKind
import dev.trashpanda.ytmp.protocol.GraphFull
import dev.trashpanda.ytmp.protocol.GraphFullNode
import dev.trashpanda.ytmp.protocol.GraphTuning
import dev.trashpanda.ytmp.protocol.HistoryRadio
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphCopyStoreTest {
    private val now = 1_000_000_000_000L

    private fun song(id: String, title: String = "Song $id") = Song(id, title, listOf(ArtistRef(null, "Artist")), null, 200_000, emptyList())

    /** a → b → c, played after each other; c's only version on the server is YouTube Music's. */
    private val graph = GraphFull(
        now, GraphTuning(), HistoryRadio.ALL,
        nodes = listOf("ytm:a", "ytm:b", "ytm:c").map { GraphFullNode(listOf(it), SongMatch.key(song(it)), 200_000) },
        edges = listOf(
            listOf(0L, 1L, EdgeKind.entries.indexOf(EdgeKind.PERSONAL).toLong(), 3, 0, 0, 0, -1, 0, now, 0),
            listOf(1L, 2L, EdgeKind.entries.indexOf(EdgeKind.PERSONAL).toLong(), 3, 0, 0, 0, -1, 0, now, 0),
        ),
    )

    @Test
    fun `the phone's songs are matched to the copy by ID or by title, artist and length, and radios rank like the server`() = runTest {
        val file = Files.createTempDirectory("ytmp").resolve("graph-copy.json").toFile()
        var reachable = true
        // On the phone: a, b, and a music file of c that was never played (only its title, artist and length say what it is).
        val songs = listOf(song("ytm:a"), song("ytm:b"), song("local:phone:s:c.m4a", "Song ytm:c"))
        val asked = mutableListOf<String?>()
        val store = GraphCopyStore(file, this, { songs }, { known -> asked += known; if (reachable) GraphCopyAnswer.New(graph) else null }, clock = { now })
        assertTrue(store.refresh())
        assertEquals(listOf("ytm:b", "local:phone:s:c.m4a"), store.similar("ytm:a").map { it.id })
        // From c: b (played before it), and a before that, further along the walk.
        assertEquals(listOf("ytm:b", "ytm:a"), store.similar("local:phone:s:c.m4a").map { it.id })

        // Offline: the old copy stays, also for a new store reading it from the file.
        reachable = false
        assertFalse(store.refresh())
        val again = GraphCopyStore(file, this, { songs }, { _ -> null }, clock = { now })
        assertEquals(listOf("ytm:b", "local:phone:s:c.m4a"), again.similar("ytm:a").map { it.id })

        // Switched off: the copy is gone, and radios get nothing from it.
        again.setEnabled(false)
        assertFalse(file.exists())
        assertEquals(emptyList(), again.similar("ytm:a"))
    }

    @Test
    fun `the copy is fetched again only when the server says it changed, and uses the server's groups`() = runTest {
        val file = Files.createTempDirectory("ytmp").resolve("graph-copy.json").toFile()
        // The server put a and c in one group, b in another: a radio from a then has c before b.
        val served = graph.copy(groups = listOf(0, 1, 0), version = "1:10/ALL|7", edges = graph.edges + listOf(listOf(0L, 2L, EdgeKind.entries.indexOf(EdgeKind.PERSONAL).toLong(), 3, 0, 0, 0, -1, 0, now, 0)))
        val asked = mutableListOf<String?>()
        var answer: GraphCopyAnswer = GraphCopyAnswer.New(served)
        val songs = listOf(song("ytm:a"), song("ytm:b"), song("ytm:c"))
        val store = GraphCopyStore(file, this, { songs }, { known -> asked += known; answer }, clock = { now })
        assertTrue(store.refresh())
        assertEquals(listOf("ytm:c", "ytm:b"), store.similar("ytm:a").map { it.id })
        answer = GraphCopyAnswer.Unchanged
        assertTrue(store.refresh())
        assertEquals(listOf(null, "1:10/ALL|7"), asked)
        assertEquals(listOf("ytm:c", "ytm:b"), store.similar("ytm:a").map { it.id })
    }
}
