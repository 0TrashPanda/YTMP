package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.ResolvedStream
import dev.trashpanda.ytmp.core.StreamResolver
import dev.trashpanda.ytmp.protocol.AlbumRef
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CachePrefillTest {
    private val dir = createTempDirectory("song-cache").toFile().apply { deleteOnExit() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val zone = ZoneOffset.UTC
    /** Thursday 23 October 2025, 18:00. */
    private val now = LocalDateTime.of(2025, 10, 23, 18, 0).toInstant(zone).toEpochMilli()
    private val day = 24L * 3600 * 1000

    @AfterTest
    fun stop() = scope.cancel()

    private fun song(id: String) = Song("ytm:$id", "Song $id", listOf(ArtistRef("a1", "Artist")), AlbumRef("b1", "Album"), 200_000, emptyList())

    private class FakeYoutube(private val size: Int = 50_000) : StreamResolver, AudioFiles {
        val downloads = CopyOnWriteArrayList<String>()

        override suspend fun resolveStream(songId: String) = resolve(songId).url

        override suspend fun resolve(songId: String) = ResolvedStream("https://youtube.example/$songId", null)

        override suspend fun download(songId: String, to: File, started: (type: String, size: Long?) -> Unit): String {
            downloads += songId
            to.writeBytes(ByteArray(size) { i -> if (i in 4..7) "ftyp"[i - 4].code.toByte() else i.toByte() })
            return SongFiles.MP4
        }
    }

    private fun cache(youtube: FakeYoutube, maxBytes: Long = 10_000_000) =
        SongCache(dir, maxBytes, youtube, youtube, scope, coverOf = { null }, clock = { now }, fetchDelayMs = 0, zone = zone)

    private fun prefill(
        cache: SongCache,
        likes: List<Song> = emptyList(),
        history: List<Pair<Song, Int?>> = emptyList(),
        signedIn: Boolean = true,
    ) = CachePrefill(cache, { count -> likes.take(count) }, { history }, { signedIn }, scope, clock = { now }, zone = zone, gapMs = 0, retryMs = 60_000, intervalMs = 60_000)

    private suspend fun eventually(check: suspend () -> Boolean) = withTimeout(10_000) {
        while (!check()) delay(20)
    }

    @Test
    fun `plays from elsewhere count once a day`() {
        val cache = cache(FakeYoutube())
        val a = song("a")
        assertEquals(1, cache.importPlays(listOf(a to now - 2 * day)))
        assertFalse(cache.worth(a.id))
        // The same day again (the history read a 2nd time): not another play.
        assertEquals(0, cache.importPlays(listOf(a to now - 2 * day + 3600_000)))
        assertFalse(cache.worth(a.id))
        // A play here goes into YouTube Music's history too: that day isn't counted twice.
        cache.started(a)
        assertTrue(cache.worth(a.id))
        assertEquals(0, cache.importPlays(listOf(a to now - 1000)))
        // Too long ago: not counted.
        val b = song("b")
        assertEquals(0, cache.importPlays(listOf(b to now - 100 * day)))
    }

    @Test
    fun `the songs liked last are kept`() = runBlocking {
        val youtube = FakeYoutube()
        val cache = cache(youtube)
        val likes = (1..5).map { song("like$it") }
        val prefill = prefill(cache, likes = likes)
        prefill.set(liked = 3, fromHistory = false)
        eventually { (1..3).all { cache.has("ytm:like$it") } }
        assertEquals(listOf("ytm:like1", "ytm:like2", "ytm:like3"), youtube.downloads.toList())
        assertEquals(3, prefill.likedCached)
        assertEquals(3, prefill.likedOf)
    }

    @Test
    fun `only free room is filled, nothing is pushed out for it`() = runBlocking {
        // Room for 3 songs of 3 MB within 90 % of 10 MB.
        val youtube = FakeYoutube(size = 3_000_000)
        val cache = cache(youtube, maxBytes = 10_000_000)
        val prefill = prefill(cache, likes = (1..5).map { song("like$it") })
        prefill.set(liked = 5, fromHistory = false)
        eventually { prefill.waiting == "full" }
        assertEquals(3, youtube.downloads.size)
        assertEquals(3, cache.songCount)
    }

    @Test
    fun `the songs in the history are kept, and their plays count`() = runBlocking {
        val youtube = FakeYoutube()
        val cache = cache(youtube)
        val (often, once) = song("often") to song("once")
        // YouTube Music lists a song once: the earlier day was seen when it was read before.
        cache.importPlays(listOf(often to now - 3 * day))
        val prefill = prefill(cache, history = listOf(often to 0, once to 1))
        prefill.set(liked = 0, fromHistory = true)
        eventually { cache.has(often.id) && cache.has(once.id) }
        assertEquals(listOf(often.id, once.id), youtube.downloads.toList())
        assertTrue(cache.worth(often.id))
        assertFalse(cache.worth(once.id))
    }

    @Test
    fun `signed out it waits`() = runBlocking {
        val youtube = FakeYoutube()
        val prefill = prefill(cache(youtube), likes = listOf(song("a")), signedIn = false)
        prefill.set(liked = 10, fromHistory = true)
        eventually { prefill.waiting == "signed out" }
        assertTrue(youtube.downloads.isEmpty())
        prefill.set(liked = 0, fromHistory = false)
        assertEquals(null, prefill.waiting)
    }
}
