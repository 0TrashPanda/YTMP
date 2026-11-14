package dev.trashpanda.ytmp.desktop

import dev.trashpanda.ytmp.desktop.PhoneCopy.Which
import dev.trashpanda.ytmp.host.CachedSongFile
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.serialization.builtins.SetSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

/** A phone's songs in three groups that don't overlap: only in its cache, cached and local, only local. */
class PhoneCopyTest {
    private fun song(id: String) = Song("ytm:$id", "Song $id", listOf(ArtistRef(null, "Artist")), null, 200_000, emptyList())

    private val cachedOnly = CachedSongFile(song("c"), 4_000_000, "audio/mp4", local = false)
    private val both = CachedSongFile(song("b"), 4_000_000, "audio/mp4", local = true)
    private val localOnly = CachedSongFile(song("l"), null, null, local = true)
    /** An older phone doesn't say whether a cached song is local too. */
    private val older = CachedSongFile(song("o"), 4_000_000, "audio/mp4")

    @Test
    fun eachSongIsInOneGroup() {
        assertEquals(Which.CACHE, Which.of(cachedOnly))
        assertEquals(Which.BOTH, Which.of(both))
        assertEquals(Which.LOCAL, Which.of(localOnly))
        assertEquals(Which.CACHE, Which.of(older))
        assertEquals(Which.LOCAL, Which.of(CachedSongFile(song("x"), null, null)))
    }

    @Test
    fun byDefaultEverySongInTheCache() {
        val all = listOf(cachedOnly, both, localOnly)
        assertEquals(listOf(cachedOnly, both), all.filter { Which.of(it) in Which.DEFAULT })
    }

    @Test
    fun theWindowSaysItInPlainWords() {
        val set = SetSerializer(Which.serializer())
        assertEquals("[\"cache\",\"local\"]", ProtocolJson.encodeToString(set, setOf(Which.CACHE, Which.LOCAL)))
        assertEquals(setOf(Which.BOTH), ProtocolJson.decodeFromString(set, "[\"both\"]"))
    }
}
