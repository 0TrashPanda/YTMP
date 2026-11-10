package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.ResolvedStream
import dev.trashpanda.ytmp.core.StreamResolver
import dev.trashpanda.ytmp.protocol.AlbumRef
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.PlaylistPage
import dev.trashpanda.ytmp.protocol.PodcastRef
import dev.trashpanda.ytmp.protocol.SearchItem
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeptPlaylistsTest {
    private val dir = createTempDirectory("song-cache").toFile().apply { deleteOnExit() }
    private val file = createTempDirectory("playlists").toFile().resolve("offline-playlists.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val now = 1_760_000_000_000L

    @AfterTest
    fun stop() = scope.cancel()

    private fun song(id: String) = Song("ytm:$id", "Song $id", listOf(ArtistRef("a1", "Artist")), AlbumRef("b1", "Album"), 200_000, emptyList())

    private class FakeYoutube(private val size: Int = 50_000) : StreamResolver, AudioFiles {
        val downloads = CopyOnWriteArrayList<String>()
        @Volatile
        var offline = false

        override suspend fun resolveStream(songId: String) = resolve(songId).url

        override suspend fun resolve(songId: String) = ResolvedStream("https://youtube.example/$songId", null)

        override suspend fun download(songId: String, to: File, started: (type: String, size: Long?) -> Unit): String {
            if (offline) throw java.io.IOException("No internet")
            downloads += songId
            to.writeBytes(ByteArray(size) { i -> if (i in 4..7) "ftyp"[i - 4].code.toByte() else i.toByte() })
            return SongFiles.MP4
        }
    }

    /** The account: its playlists and their songs (in parts of [partSize]). */
    private class FakeAccount(val partSize: Int = 100) {
        val playlists = LinkedHashMap<String, MutableList<Song>>()
        val reads = CopyOnWriteArrayList<String>()
        @Volatile
        var offline = false
        @Volatile
        var signedIn = true

        fun library(): List<SearchItem.PlaylistResult>? {
            if (offline) throw java.io.IOException("No internet")
            if (!signedIn) return null
            return playlists.map { (id, songs) -> SearchItem.PlaylistResult(id, "List $id", null, songs.size, emptyList()) }
        }

        fun part(id: String, more: String?): PlaylistPage {
            if (offline) throw java.io.IOException("No internet")
            reads += id
            val songs = playlists[id] ?: throw SourceException("Couldn't find this playlist")
            val from = more?.toInt() ?: 0
            val to = minOf(songs.size, from + partSize)
            return PlaylistPage(id, if (more == null) "List $id" else "", null, null, emptyList(), songs.subList(from, to).toList(), more = if (to < songs.size) "$to" else null)
        }
    }

    private fun cache(youtube: FakeYoutube, maxBytes: Long = 10_000_000) =
        SongCache(dir, maxBytes, youtube, youtube, scope, coverOf = { null }, clock = { now }, fetchDelayMs = 0)

    private fun kept(cache: SongCache, account: FakeAccount) =
        KeptPlaylists(cache, file, { account.library() }, { id, more -> account.part(id, more) }, scope, clock = { now }, gapMs = 0, readGapMs = 0, changeDelayMs = 0).also {
            cache.kept = it::keptSongs
            cache.playlists = { it.complete() }
        }

    private suspend fun eventually(check: suspend () -> Boolean) = withTimeout(10_000) {
        while (!check()) delay(20)
    }

    @Test
    fun `a playlist kept offline comes in, shows as complete, and keeps up with new songs`() = runBlocking {
        val youtube = FakeYoutube()
        val cache = cache(youtube)
        val account = FakeAccount(partSize = 2)
        account.playlists["PL1"] = mutableListOf(song("a"), song("b"), song("c"))
        account.playlists["PL2"] = mutableListOf(song("x"))
        val playlists = kept(cache, account)
        playlists.round()
        // Read, but nothing kept yet: no songs fetched, nothing complete.
        assertTrue(youtube.downloads.isEmpty())
        assertTrue(playlists.complete().isEmpty())

        playlists.keep("PL1", true)
        playlists.round()
        assertEquals(listOf("ytm:a", "ytm:b", "ytm:c"), youtube.downloads.toList())
        assertEquals(listOf("PL1"), playlists.complete().map { it.id })
        assertEquals(listOf("ytm:a", "ytm:b", "ytm:c"), playlists.complete().single().keys)
        val status = playlists.status().single()
        assertTrue(status.kept)
        assertEquals(3 to 3, status.songs to status.here)

        // A song added (in the app, or elsewhere: the library's count changes): not complete until it's here too.
        account.playlists.getValue("PL1") += song("d")
        youtube.offline = true
        playlists.round()
        assertTrue(playlists.complete().isEmpty())
        assertEquals(3 to 4, playlists.status().single().let { it.here to it.songs })
        youtube.offline = false
        playlists.round()
        assertEquals(listOf("PL1"), playlists.complete().map { it.id })
    }

    @Test
    fun `offline it keeps what it read, also after a restart`() = runBlocking {
        val youtube = FakeYoutube()
        val cache = cache(youtube)
        val account = FakeAccount()
        account.playlists["PL1"] = mutableListOf(song("a"), song("b"))
        kept(cache, account).apply { keep("PL1", true); round() }
        account.offline = true
        val again = kept(cache, account)
        assertFailsWith<java.io.IOException> { again.round() }
        assertEquals(listOf("PL1"), again.complete().map { it.id })
        assertTrue(again.isKept("PL1"))
    }

    @Test
    fun `a playlist whose songs are all here anyway shows, one that's almost there doesn't`() = runBlocking {
        val youtube = FakeYoutube()
        val cache = cache(youtube)
        val account = FakeAccount()
        account.playlists["full"] = mutableListOf(song("a"), song("b"))
        account.playlists["almost"] = mutableListOf(song("a"), song("c"))
        val playlists = kept(cache, account)
        // Played (and so cached) without any playlist kept.
        cache.fetch(song("a"))
        cache.fetch(song("b"))
        playlists.round()
        assertEquals(listOf("full"), playlists.complete().map { it.id })
        val local = LocalMusic().apply { add(cache.library { "http://phone" }) }
        val page = local.page(SongCache.LIBRARY_ID)!!
        val list = page.playlists.single()
        assertEquals("List full", list.title)
        assertEquals(listOf("ytm:a", "ytm:b"), list.songs.map { page.songs[it].id })
    }

    @Test
    fun `songs of kept playlists go last when room is needed, and Clear cache leaves them`() = runBlocking {
        // 3 MB songs, 10 MB: three fit under 90 %.
        val youtube = FakeYoutube(size = 3_000_000)
        val cache = cache(youtube, maxBytes = 10_000_000)
        val account = FakeAccount()
        account.playlists["PL1"] = mutableListOf(song("a"), song("b"))
        val playlists = kept(cache, account)
        playlists.keep("PL1", true)
        playlists.round()
        cache.fetch(song("other1"))
        cache.fetch(song("other2"))
        eventually { cache.songCount == 3 }
        assertTrue(cache.has("ytm:a") && cache.has("ytm:b"))
        cache.clear()
        assertEquals(setOf("ytm:a", "ytm:b"), cache.songIds().toSet())
        // No longer kept: they're ordinary cached songs, going like the others.
        playlists.keep("PL1", false)
        cache.clear()
        assertEquals(0, cache.songCount)
    }

    @Test
    fun `a kept playlist that doesn't fit says so`() = runBlocking {
        val youtube = FakeYoutube(size = 3_000_000)
        val cache = cache(youtube, maxBytes = 10_000_000)
        val account = FakeAccount()
        account.playlists["big"] = (1..6).map { song("s$it") }.toMutableList()
        val playlists = kept(cache, account)
        playlists.keep("big", true)
        playlists.round()
        assertEquals("full", playlists.waiting)
        assertEquals("List big", playlists.full)
        assertEquals(3, youtube.downloads.size)
    }

    @Test
    fun `the most played playlists are kept, not Liked music, only ones that fit`() = runBlocking {
        val youtube = FakeYoutube(size = 1_000_000)
        val cache = cache(youtube, maxBytes = 10_000_000)
        // So a song here is known to be about 1 MB.
        cache.fetch(song("seed"))
        val account = FakeAccount()
        account.playlists["LM"] = (1..3).map { song("liked$it") }.toMutableList()
        account.playlists["often"] = mutableListOf(song("o1"), song("o2"))
        account.playlists["sometimes"] = mutableListOf(song("s1"))
        account.playlists["never"] = mutableListOf(song("n1"))
        account.playlists["huge"] = (1..20).map { song("h$it") }.toMutableList()
        fun played(id: String, times: Int) = repeat(times) { cache.importPlays(listOf(song(id) to now - it * 86_400_000L)) }
        played("liked1", 9)
        played("h1", 8)
        played("o1", 5)
        played("o2", 2)
        played("s1", 3)
        val playlists = kept(cache, account)
        playlists.set(2)
        playlists.round()
        // "huge" is played more but doesn't fit; Liked music has its own setting.
        assertEquals(setOf("often", "sometimes"), playlists.status().filter { it.mostPlayed }.map { it.id }.toSet())
        assertEquals(setOf("ytm:seed", "ytm:o1", "ytm:o2", "ytm:s1"), youtube.downloads.toSet())
        assertEquals(setOf("often", "sometimes"), playlists.complete().map { it.id }.toSet())
        playlists.set(1)
        playlists.round()
        assertEquals(listOf("often"), playlists.status().filter { it.mostPlayed }.map { it.id })
        // Off the list: its song stays cached like any other song (and the playlist is still all here).
        assertTrue(cache.has("ytm:s1"))
    }

    @Test
    fun `podcast episodes can't be kept in the cache, so such a playlist isn't complete`() = runBlocking {
        val youtube = FakeYoutube()
        val cache = cache(youtube)
        val account = FakeAccount()
        val episode = song("ep").copy(podcast = PodcastRef("p1", "A podcast"))
        account.playlists["mixed"] = mutableListOf(song("a"), episode)
        val playlists = kept(cache, account)
        playlists.keep("mixed", true)
        playlists.round()
        assertEquals(listOf("ytm:a"), youtube.downloads.toList())
        val status = playlists.status().single()
        assertEquals(1, status.here)
        assertEquals(1, status.notKept)
        assertTrue(playlists.complete().isEmpty())
    }

    @Test
    fun `a playlist the app shows whole or changes is up to date at once`() = runBlocking {
        val youtube = FakeYoutube()
        val cache = cache(youtube)
        val account = FakeAccount()
        account.playlists["PL1"] = mutableListOf(song("a"))
        val playlists = kept(cache, account)
        playlists.keep("PL1", true)
        playlists.round()
        assertEquals(listOf("PL1"), playlists.complete().map { it.id })
        // The page shows it with a new song: known at once (not complete now).
        playlists.seen(PlaylistPage("PL1", "List PL1", null, null, emptyList(), listOf(song("a"), song("b"))))
        assertTrue(playlists.complete().isEmpty())
        // A song removed in the app: read again (the library's count is the same as before).
        account.playlists["PL1"] = mutableListOf(song("a"))
        playlists.changed("PL1")
        val reads = account.reads.size
        playlists.round()
        assertTrue(account.reads.size > reads)
        assertEquals(listOf("PL1"), playlists.complete().map { it.id })
    }
}
