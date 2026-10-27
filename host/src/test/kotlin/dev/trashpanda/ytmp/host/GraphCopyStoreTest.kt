package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.GraphCopy
import dev.trashpanda.ytmp.protocol.GraphCopyRequest
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GraphCopyStoreTest {
    private fun song(id: String) = Song(id, "Song $id", listOf(ArtistRef(null, "Artist")), null, 200_000, emptyList())

    @Test
    fun `the copy gives the phone's songs their neighbours, and stays when the server can't be asked`() = runTest {
        val file = Files.createTempDirectory("ytmp").resolve("graph-copy.json").toFile()
        val asked = mutableListOf<GraphCopyRequest>()
        var reachable = true
        val songs = listOf(song("ytm:a"), song("local:phone:s:b"), song("ytm:c"))
        val store = GraphCopyStore(file, this, { songs }, { request ->
            asked += request
            if (reachable) GraphCopy(mapOf("ytm:a" to listOf("ytm:c", "local:phone:s:b"))) else null
        })
        assertTrue(store.refresh())
        assertEquals(listOf("ytm:a", "local:phone:s:b", "ytm:c"), asked.single().among)
        assertEquals(listOf("ytm:c", "local:phone:s:b"), store.similar("ytm:a").map { it.id })
        assertEquals(emptyList(), store.similar("ytm:c"))

        // Offline: the old copy stays, and a new store reads it from the file.
        reachable = false
        assertFalse(store.refresh())
        val again = GraphCopyStore(file, this, { songs }, { null })
        assertEquals(listOf("ytm:c", "local:phone:s:b"), again.similar("ytm:a").map { it.id })
    }
}
