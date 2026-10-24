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
    fun `an artist among several matches, the app's downloads are skipped`() {
        assertNotNull(LocalMatcher.match(track("1", "Stay", "Rihanna, Mikky Ekko", 240_000), listOf(song("ytm:a", "Stay", "Rihanna", 240_000))))
        assertEquals(false, LocalMatcher.eligible(track("1", "A", "B", 200_000, "Music/YTMP/")))
        assertEquals(true, LocalMatcher.eligible(track("1", "Unknown song", "Unknown artist", 200_000)))
        assertEquals(true, LocalMatcher.eligible(track("1", "A", "B", 200_000)))
    }

    @Test
    fun `a YouTube ID in the file name or a tag is found`() {
        assertEquals("JsYh2wQVUAI", LocalMatcher.idInName("Love And Truth [JsYh2wQVUAI].opus"))
        assertEquals("QNXyamTN5D0", LocalMatcher.idInName("did you get enough love-QNXyamTN5D0.mp3"))
        assertEquals("_b7OanWbpjU", LocalMatcher.idInName("Stockholm Syndrome [_b7OanWbpjU].mkv"))
        assertNull(LocalMatcher.idInName("A Really Long Time (Rick and Morty Remix).mp3"))
        assertEquals("JsYh2wQVUAI", LocalMatcher.idInBytes("xx PURL=https://www.youtube.com/watch?v=JsYh2wQVUAI\u0000more".toByteArray()))
        assertEquals("JsYh2wQVUAI", LocalMatcher.idInBytes("https://music.youtube.com/watch?v=JsYh2wQVUAI".toByteArray(Charsets.UTF_16LE)))
        assertNull(LocalMatcher.idInBytes("no link".toByteArray()))
    }

    @Test
    fun `an ID is looked up first and beats a search`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = createTempDirectory("matcher-id").toFile().apply { deleteOnExit() }
        val youtube = StreamResolver { error("not used") }
        val cache = SongCache(dir, 1_000_000, youtube, null, scope, coverOf = { null })
        val searches = ArrayList<String>()
        val files = listOf(
            track("1", "Whatever the file says", "", 215_000).copy(file = "Love And Truth [JsYh2wQVUAI].opus"),
            track("2", "Far off", "X", 100_000).copy(file = "Far off [AAAAAAAAAAA].opus"),
        )
        val matcher = LocalMatcher(cache, { files }, { searches += it; emptyList() }, lookup = { id ->
            if (id == "ytm:JsYh2wQVUAI") song(id, "Love And Truth", "Y", 215_700) else song(id, "Other", "Z", 300_000)
        }, scope = scope, gapMs = 0)
        matcher.start()
        withTimeout(5_000) { while (matcher.pending > 0 || cache.localMatch("2") == null) delay(10) }
        assertEquals("ytm:JsYh2wQVUAI", matcher.find("ytm:JsYh2wQVUAI")?.second?.id)
        // The second one's ID was another length: searched, and nothing found.
        assertNull(cache.localMatch("2")?.song)
        assertEquals(1, searches.size)
        matcher.stop()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
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
        }, scope = scope, gapMs = 0)
        matcher.start()
        withTimeout(5_000) { while (matcher.pending > 0 || cache.localMatch("2") == null) delay(10) }
        assertEquals("ytm:halo", matcher.find("ytm:halo")?.second?.id)
        assertEquals("1", matcher.find("ytm:halo")?.first?.key)
        assertNull(cache.localMatch("2")?.song)
        assertEquals(2, searches.size)
        matcher.stop()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun `a file without artist is matched by its ID, asked again and linked by hand`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = createTempDirectory("matcher-manual").toFile().apply { deleteOnExit() }
        val cache = SongCache(dir, 1_000_000, StreamResolver { error("not used") }, null, scope, coverOf = { null })
        val files = listOf(
            track("1", "Unknown song", "Unknown artist", 215_000).copy(file = "Love And Truth [JsYh2wQVUAI].opus"),
            track("2", "Unknown song", "Unknown artist", 100_000).copy(file = "mystery.opus"),
        )
        var known = setOf("ytm:JsYh2wQVUAI")
        val matcher = LocalMatcher(cache, { files }, { emptyList() }, lookup = { id ->
            if (id in known) song(id, "Whatever", "Y", if (id == "ytm:JsYh2wQVUAI") 215_000 else 100_000) else null
        }, scope = scope, gapMs = 0)
        matcher.start()
        withTimeout(5_000) { while (matcher.pending > 0 || cache.localMatch("2") == null) delay(10) }
        assertEquals("ytm:JsYh2wQVUAI", matcher.find("ytm:JsYh2wQVUAI")?.second?.id)
        assertEquals(listOf("2"), matcher.unmatched().map { it.key })
        assertEquals(false, matcher.link("2", "nonsense") == null)
        known = known + "ytm:abcdefghijk"
        assertNull(matcher.link("2", "https://music.youtube.com/watch?v=abcdefghijk"))
        assertEquals("ytm:abcdefghijk", matcher.find("ytm:abcdefghijk")?.second?.id)
        assertEquals(emptyList(), matcher.unmatched())
        // Skipping is the owner's say too: forgetting keeps it, and it shows as skipped.
        assertNull(matcher.link("2", ""))
        matcher.forget()
        withTimeout(5_000) { while (cache.localMatch("1") == null) delay(10) }
        assertEquals("Skipped by you", matcher.unmatched().single().why)
        assertEquals("ytm:JsYh2wQVUAI", matcher.find("ytm:JsYh2wQVUAI")?.second?.id)
        matcher.stop()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun `after the update only files whose tags now give an ID are looked up again`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dir = createTempDirectory("matcher-v3").toFile().apply { deleteOnExit() }
        val cache = SongCache(dir, 1_000_000, StreamResolver { error("not used") }, null, scope, coverOf = { null })
        val found = track("1", "Halo", "Beyoncé", 261_000)
        val tagged = track("2", "The Chapel", "Rock Band", 242_000)
        val nothing = track("3", "My Own Demo", "Me", 120_000)
        // What version 2 found, before tags were read whole.
        cache.setLocalMatch("1", LocalMatch(LocalMatcher.signature(found, 2), song("ytm:halo", "Halo", "Beyoncé", 261_000)))
        cache.setLocalMatch("2", LocalMatch(LocalMatcher.signature(tagged, 2), null, "No result with the same title, artist and length"))
        cache.setLocalMatch("3", LocalMatch(LocalMatcher.signature(nothing, 2), null, "YouTube Music found nothing"))
        val asked = ArrayList<String>()
        val matcher = LocalMatcher(cache, { listOf(found, tagged, nothing) }, { asked += it; emptyList() },
            tagged = { t -> if (t.key == "2") "0yJzontbSYg" else null },
            lookup = { id -> asked += id; song(id, "The Chapel", "Rock Band", 242_000) }, scope = scope, gapMs = 0)
        matcher.start()
        withTimeout(5_000) { while (cache.localMatch("2")?.song == null) delay(10) }
        assertEquals("ytm:0yJzontbSYg", cache.localMatch("2")?.song?.id)
        assertEquals(listOf("ytm:0yJzontbSYg"), asked)
        assertEquals("ytm:halo", cache.localMatch("1")?.song?.id)
        assertEquals(LocalMatcher.signature(nothing), cache.localMatch("3")?.sig)
        matcher.stop()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
