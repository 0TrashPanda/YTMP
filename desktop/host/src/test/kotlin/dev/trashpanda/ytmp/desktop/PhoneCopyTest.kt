package dev.trashpanda.ytmp.desktop

import dev.trashpanda.ytmp.desktop.PhoneCopy.Which
import dev.trashpanda.ytmp.host.CachedSongFile
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ProtocolJson
import dev.trashpanda.ytmp.protocol.Song
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which of a phone's songs a copy takes: its song cache, its local songs, or both. */
class PhoneCopyTest {
    private fun song(id: String) = Song("ytm:$id", "Song $id", listOf(ArtistRef(null, "Artist")), null, 200_000, emptyList())

    /** The phone lists cached songs with their size, its downloads and own files without. */
    private val cached = CachedSongFile(song("c"), 4_000_000, "audio/mp4")
    private val local = CachedSongFile(song("l"), null, null)

    @Test
    fun picksTheSongsAsked() {
        val all = listOf(cached, local)
        assertEquals(listOf(cached), all.filter(Which.CACHE::takes))
        assertEquals(listOf(local), all.filter(Which.LOCAL::takes))
        assertEquals(all, all.filter(Which.BOTH::takes))
    }

    @Test
    fun theWindowSaysItInPlainWords() {
        assertEquals("\"local\"", ProtocolJson.encodeToString(Which.serializer(), Which.LOCAL))
        assertEquals(Which.BOTH, ProtocolJson.decodeFromString(Which.serializer(), "\"both\""))
    }
}
