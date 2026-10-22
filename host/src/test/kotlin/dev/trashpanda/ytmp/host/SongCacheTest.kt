package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.ResolvedStream
import dev.trashpanda.ytmp.core.RoomManager
import dev.trashpanda.ytmp.core.StreamResolver
import dev.trashpanda.ytmp.protocol.AlbumRef
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.HostKind
import dev.trashpanda.ytmp.protocol.LocalLibraryKind
import dev.trashpanda.ytmp.protocol.PodcastRef
import dev.trashpanda.ytmp.protocol.Song
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assumptions.assumeFalse
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongCacheTest {
    private val dir = createTempDirectory("song-cache").toFile().apply { deleteOnExit() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var now = 1_000_000_000_000L

    private fun song(id: String, durationMs: Long = 200_000) =
        Song("ytm:$id", "Song $id", listOf(ArtistRef("a1", "Artist")), AlbumRef("b1", "Album"), durationMs, emptyList())

    /** A song's audio: an .m4a's first bytes, then [size] in all. */
    private fun bytesOf(id: String, size: Int = 50_000) = ByteArray(size) { i ->
        if (i in 4..7) "ftyp"[i - 4].code.toByte() else (i * 31 + id.hashCode()).toByte()
    }

    /** YouTube, as far as the cache is concerned: lookups and whole songs, both counted. */
    private inner class FakeYoutube(private val pieceDelayMs: Long = 0) : StreamResolver, AudioFiles {
        val lookups = CopyOnWriteArrayList<String>()
        val downloads = CopyOnWriteArrayList<String>()

        override suspend fun resolveStream(songId: String) = resolve(songId).url

        override suspend fun resolve(songId: String): ResolvedStream {
            lookups += songId
            return ResolvedStream("https://youtube.example/$songId", null)
        }

        override suspend fun download(songId: String, to: File, started: (type: String, size: Long?) -> Unit): String {
            downloads += songId
            val bytes = bytesOf(songId.removePrefix("ytm:"))
            started(SongFiles.MP4, bytes.size.toLong())
            to.outputStream().use { out ->
                for (piece in bytes.toList().chunked(10_000)) {
                    out.write(piece.toByteArray())
                    out.flush()
                    if (pieceDelayMs > 0) delay(pieceDelayMs)
                }
            }
            return SongFiles.MP4
        }
    }

    private fun cache(youtube: FakeYoutube = FakeYoutube(), maxBytes: Long = 10_000_000, fetchDelayMs: Long = 0, at: File = dir) =
        SongCache(at, maxBytes, youtube, youtube, scope, coverOf = { null }, clock = { now }, fetchDelayMs = fetchDelayMs)

    /** What the phone's player reads of a song, kept as it plays: [pieces] as (from, to) in this order. */
    private fun play(cache: SongCache, song: Song, pieces: List<Pair<Int, Int>>, size: Int = 50_000) {
        val bytes = bytesOf(song.id.removePrefix("ytm:"), size)
        val copy = cache.copy(song)!!
        for ((from, to) in pieces) {
            copy.open(from.toLong(), (size - from).toLong())
            copy.write(bytes, from, to - from)
            copy.close()
        }
    }

    private suspend fun eventually(check: suspend () -> Boolean) = withTimeout(10_000) {
        while (!check()) delay(20)
    }

    @Test
    fun `a song the player read all of is kept, and then needs no lookup`() = runBlocking {
        val youtube = FakeYoutube()
        val cache = cache(youtube)
        val song = song("a")
        assertEquals("https://youtube.example/ytm:a", cache.streams.resolve(song.id).url)

        // Read out of order (a seek back to the start), in pieces.
        play(cache, song, listOf(0 to 1_000, 20_000 to 50_000, 1_000 to 20_000))

        assertTrue(cache.has(song.id))
        val stream = cache.streams.resolve(song.id)
        assertEquals("/api/audio/ytm%3Aa", stream.url)
        assertEquals(200_000, stream.durationMs)
        assertEquals(listOf("ytm:a"), youtube.lookups)
        assertContentEquals(bytesOf("a"), File(cache.localUri(song.id)!!.removePrefix("file:")).readBytes())
    }

    @Test
    fun `a song only partly played is dropped when it moves on`() {
        val cache = cache()
        val song = song("b")
        play(cache, song, listOf(0 to 10_000))
        cache.leave(song.id)
        assertFalse(cache.has(song.id))
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("ytm_b") })
    }

    @Test
    fun `podcast episodes and local music aren't kept`() {
        val cache = cache()
        assertNull(cache.copy(song("c").copy(podcast = PodcastRef("p1", "A podcast"))))
        assertNull(cache.copy(song("d").copy(id = "local:phone:s:12")))
        assertNull(cache.copy(song("e", durationMs = 3 * 3600_000L)))
    }

    @Test
    fun `from the second play a song is fetched, one-off songs aren't`() = runBlocking {
        val youtube = FakeYoutube()
        val cache = cache(youtube)
        val once = song("once")
        val twice = song("twice")
        cache.started(once)
        cache.started(twice)
        delay(200)
        assertTrue(youtube.downloads.isEmpty())

        now += 24 * 3600_000L
        cache.started(twice)
        eventually { cache.has(twice.id) }
        assertEquals(listOf(twice.id), youtube.downloads.toList())
        assertFalse(cache.has(once.id))
    }

    @Test
    fun `a play skipped within 30 s doesn't count`() = runBlocking {
        val youtube = FakeYoutube()
        val cache = cache(youtube, fetchDelayMs = 300)
        val song = song("skip")
        cache.started(song)
        cache.finished(song, counts = false)
        cache.started(song)
        cache.finished(song, counts = false)
        delay(600)
        assertTrue(youtube.downloads.isEmpty())
        assertFalse(cache.worth(song.id))
    }

    @Test
    fun `over its size the least played songs go first, and the playing one stays`() = runBlocking {
        val cache = cache(maxBytes = 180_000)
        val old = song("old")
        val often = song("often")
        val recent = song("recent")
        val playing = song("playing")
        // Played often a while ago: a week more recent for each play.
        repeat(4) {
            cache.started(often)
            now += 3600_000L
        }
        play(cache, often, listOf(0 to 50_000))
        cache.started(old)
        play(cache, old, listOf(0 to 50_000))
        now += 5 * 24 * 3600_000L
        cache.started(recent)
        play(cache, recent, listOf(0 to 50_000))
        cache.playingHere = old.id
        now += 24 * 3600_000L
        cache.started(playing)
        play(cache, playing, listOf(0 to 50_000))

        // 200 KB > 180 KB: down to under 162 KB, so one goes: the recent one played once
        // (the old one plays now, the often played one counts as weeks more recent).
        eventually { cache.totalBytes <= 162_000 }
        assertFalse(cache.has(recent.id))
        assertTrue(cache.has(old.id))
        assertTrue(cache.has(often.id))
        assertTrue(cache.has(playing.id))
    }

    @Test
    fun `clear cache and a smaller size`() = runBlocking {
        val cache = cache()
        for (id in listOf("x", "y", "z")) play(cache, song(id), listOf(0 to 50_000))
        assertEquals(3, cache.songCount)
        cache.setMaxBytes(100_000)
        assertTrue(cache.totalBytes <= 90_000)
        cache.clear()
        assertEquals(0, cache.songCount)
        cache.setMaxBytes(0)
        assertNull(cache.copy(song("w")))
    }

    @Test
    fun `what's cached is there again after a restart, other files in the folder stay`() = runBlocking {
        val other = File(dir, "notes.txt").apply { writeText("mine") }
        val first = cache()
        play(first, song("kept"), listOf(0 to 50_000))
        File(dir, "ytm_cut.part").writeBytes(ByteArray(10))
        first.saveNow()

        val again = cache()
        assertTrue(again.has("ytm:kept"))
        assertEquals("/api/audio/ytm%3Akept", again.streams.resolve("ytm:kept").url)
        assertFalse(File(dir, "ytm_cut.part").exists())
        assertTrue(other.exists())
    }

    @Test
    fun `moving it takes the songs along`() = runBlocking {
        val cache = cache()
        play(cache, song("m"), listOf(0 to 50_000))
        val to = File(dir, "elsewhere")
        cache.moveTo(to)!!.join()
        assertEquals(to, cache.dir)
        assertTrue(cache.has("ytm:m"))
        assertTrue(File(to, "ytm_m.m4a").isFile)
        assertTrue(cache(at = to).has("ytm:m"))
    }

    @Test
    fun `the audio route keeps what it streams, and answers from the file after`() = testApplication {
        // Keeping it renames the file while it's still being read, which Windows refuses
        // (the server and the phone don't run there).
        assumeFalse(System.getProperty("os.name").startsWith("Windows"))
        val youtube = FakeYoutube(pieceDelayMs = 30)
        val cache = cache(youtube)
        val inner = AudioProxy { call, _, _ -> call.respondText("from youtube") }
        application {
            val rooms = RoomManager(cache.streams, CoroutineScope(SupervisorJob()))
            ytmpModule(rooms, HostSources(search = { _, _ -> error("no search") }, audio = cache.audio(inner), cache = cache), HostOptions(kind = HostKind.SERVER))
        }
        val song = song("s")
        // Unknown to the rooms: streamed as before, not kept.
        assertEquals("from youtube", String(client.get("/api/audio/ytm:s").bodyAsBytes()))

        cache.started(song)
        // A range while it's still coming in, and the whole file at the same time.
        val part = client.get("/api/audio/ytm:s") { header(HttpHeaders.Range, "bytes=30000-39999") }
        assertEquals(HttpStatusCode.PartialContent, part.status)
        assertEquals("bytes 30000-39999/50000", part.headers[HttpHeaders.ContentRange])
        assertContentEquals(bytesOf("s").copyOfRange(30_000, 40_000), part.bodyAsBytes())
        assertContentEquals(bytesOf("s"), client.get("/api/audio/ytm:s").bodyAsBytes())

        eventually { cache.has(song.id) }
        assertContentEquals(bytesOf("s").copyOfRange(100, 200), client.get("/api/audio/ytm:s") { header(HttpHeaders.Range, "bytes=100-199") }.bodyAsBytes())
        assertEquals(listOf(song.id), youtube.downloads.toList())
    }

    @Test
    fun `a device that may not stream through the host gets YouTube's link for a cached song`() = testApplication {
        val youtube = FakeYoutube()
        val cache = cache(youtube)
        play(cache, song("r"), listOf(0 to 50_000))
        application {
            val rooms = RoomManager(cache.streams, CoroutineScope(SupervisorJob()))
            ytmpModule(
                rooms,
                HostSources(search = { _, _ -> error("no search") }, audio = cache.audio(null), cache = cache),
                HostOptions(kind = HostKind.SERVER, mayProxyAudio = { it.request.headers["X-Test-Lan"] == "1" }),
            )
        }
        val outside = createClient { followRedirects = false }
        val answer = outside.get("/api/audio/ytm:r")
        assertEquals(HttpStatusCode.Found, answer.status)
        assertEquals("https://youtube.example/ytm:r", answer.headers[HttpHeaders.Location])
        assertContentEquals(bytesOf("r"), client.get("/api/audio/ytm:r") { header("X-Test-Lan", "1") }.bodyAsBytes())
        // Not cached: refused as before.
        assertEquals(HttpStatusCode.Forbidden, outside.get("/api/audio/ytm:other").status)
    }

    @Test
    fun `cached on this phone lists the songs with their own IDs`() = runBlocking {
        val cache = cache()
        play(cache, song("l1"), listOf(0 to 50_000))
        play(cache, song("l2"), listOf(0 to 50_000))
        val local = LocalMusic()
        local.add(cache.library { "http://phone" })
        val page = local.page(SongCache.LIBRARY_ID)!!
        assertEquals(LocalLibraryKind.CACHE, page.kind)
        assertEquals(setOf("ytm:l1", "ytm:l2"), page.songs.map { it.id }.toSet())
        assertEquals("http://phone/api/local/art/ytm%3Al1?size=120", page.songs.first { it.id == "ytm:l1" }.thumbnails.first().url)
        // One album, though its songs are in the folder Cached; its page works without YouTube.
        assertEquals(1, page.albums.size)
        assertEquals(listOf(SongCache.CACHED), page.folders.map { it.name })
        val album = (page.albums.single() as dev.trashpanda.ytmp.protocol.SearchItem.AlbumResult).album
        assertEquals(2, local.album(album.id)!!.songs.size)
        assertEquals(1, local.summaries().libraries.size)
    }

    @Test
    fun `an empty cache isn't a library in the Local tab`() = runBlocking {
        val local = LocalMusic()
        local.add(cache().library { "http://phone" })
        assertTrue(local.summaries().libraries.isEmpty())
    }
}
