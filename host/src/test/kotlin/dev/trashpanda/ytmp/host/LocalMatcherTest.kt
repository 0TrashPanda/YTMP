package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.StreamResolver
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocalMatcherTest {
    private fun song(id: String, title: String, artist: String, ms: Long) =
        Song(id = id, title = title, artists = listOf(ArtistRef(null, artist)), album = null, durationMs = ms, thumbnails = emptyList())

    private fun track(key: String, title: String, artist: String, ms: Long, folder: String = "Music/") =
        LocalTrack(key, title, artist, artist, "", null, null, null, ms, folder)

    @Test
    fun `same title, artist and length match, with accents and brackets ignored`() {
        val t = track("1", "Halo (Remastered)", "Beyoncé", 261_000)
        assertNotNull(LocalMatcher.match(t, listOf(song("ytm:a", "Halo", "Beyonce", 262_500))))
    }

    @Test
    fun `another length, title or artist does not match`() {
        val t = track("1", "Halo", "Beyoncé", 261_000)
        assertNull(LocalMatcher.match(t, listOf(song("ytm:a", "Halo", "Beyoncé", 300_000))))
        assertNull(LocalMatcher.match(t, listOf(song("ytm:a", "Halo (Live)", "Beyoncé", 261_000)).map { it.copy(title = "Halo live") }))
        assertNull(LocalMatcher.match(t, listOf(song("ytm:a", "Halo", "Texas", 261_000))))
    }

    @Test
    fun `an artist among several matches, the app's downloads and unknown files are skipped`() {
        assertNotNull(LocalMatcher.match(track("1", "Stay", "Rihanna, Mikky Ekko", 240_000), listOf(song("ytm:a", "Stay", "Rihanna", 240_000))))
        assertEquals(false, LocalMatcher.eligible(track("1", "A", "B", 200_000, "Music/YTMP/")))
        assertEquals(false, LocalMatcher.eligible(track("1", "Unknown song", "B", 200_000)))
        assertEquals(true, LocalMatcher.eligible(track("1", "A", "B", 200_000)))
    }

    @Test
    fun `each file is looked up once and the answer is kept`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = createTempDirectory("matcher").toFile().apply { deleteOnExit() }
        val youtube = object : StreamResolver {
            override suspend fun resolveStream(songId: String): String = error("not used")
        }
        val cache = SongCache(dir, 1_000_000, youtube, null, scope, coverOf = { null })
        val searches = ArrayList<String>()
        val files = listOf(track("1", "Halo", "Beyoncé", 261_000), track("2", "My Own Demo", "Me", 120_000))
        val matcher = LocalMatcher(cache, { files }, { query ->
            searches += query
            listOf(song("ytm:halo", "Halo", "Beyoncé", 261_000))
        }, scope, gapMs = 0)
        matcher.start()
        withTimeout(5_000) { while (matcher.pending > 0 || cache.localMatch("2") == null) delay(10) }
        assertEquals("ytm:halo", matcher.find("ytm:halo")?.second?.id)
        assertEquals("1", matcher.find("ytm:halo")?.first?.key)
        assertNull(cache.localMatch("2")?.song)
        assertEquals(2, searches.size)
        matcher.stop()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
