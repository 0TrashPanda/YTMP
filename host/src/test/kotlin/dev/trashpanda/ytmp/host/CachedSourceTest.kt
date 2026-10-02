package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.minutes

class CachedSourceTest {
    @Test
    fun `remembers, shares fetches in flight, expires, and doesn't remember failures`() = runTest {
        var now = 0L
        var loads = 0
        val cache = TtlCache<String, String>(10, 30.minutes) { now }

        val results = (1..3).map { async { cache.get("a") { loads++; delay(100); "A" } } }.awaitAll()
        assertEquals(listOf("A", "A", "A"), results)
        assertEquals(1, loads)

        assertEquals("A", cache.get("a") { loads++; "A2" })
        now += 31.minutes.inWholeMilliseconds
        assertEquals("A2", cache.get("a") { loads++; "A2" })
        assertEquals(2, loads)

        assertFailsWith<IllegalStateException> { cache.get("b") { error("YouTube said no") } }
        assertEquals("B", cache.get("b") { "B" })
    }

    @Test
    fun `search is cached by its words, ignoring case`() = runTest {
        var searches = 0
        val song = Song("ytm:1", "One", listOf(ArtistRef(null, "A")), null, 1, emptyList())
        val source = CachedSource({ searches++; listOf(song) }, { emptyList() }, object : dev.trashpanda.ytmp.core.CatalogSource {
            override suspend fun artist(id: String) = error("no")
            override suspend fun album(id: String) = error("no")
        })
        source.search("Daft Punk")
        source.search(" daft punk ")
        assertEquals(1, searches)
    }
}
