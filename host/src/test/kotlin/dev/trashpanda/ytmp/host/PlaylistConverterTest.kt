package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.PersonalCatalog
import dev.trashpanda.ytmp.core.SongSearch
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.ConvertItemState
import dev.trashpanda.ytmp.protocol.ConvertJob
import dev.trashpanda.ytmp.protocol.ConvertJobStatus
import dev.trashpanda.ytmp.protocol.ConvertListChoice
import dev.trashpanda.ytmp.protocol.ConvertPick
import dev.trashpanda.ytmp.protocol.ConvertStartRequest
import dev.trashpanda.ytmp.protocol.ConvertTarget
import dev.trashpanda.ytmp.protocol.ConvertTargetKind
import dev.trashpanda.ytmp.protocol.HomePage
import dev.trashpanda.ytmp.protocol.LikeStatus
import dev.trashpanda.ytmp.protocol.PlaylistPage
import dev.trashpanda.ytmp.protocol.PlaylistSummary
import dev.trashpanda.ytmp.protocol.SearchItem
import dev.trashpanda.ytmp.protocol.SearchPage
import dev.trashpanda.ytmp.protocol.SearchSection
import dev.trashpanda.ytmp.protocol.SearchType
import dev.trashpanda.ytmp.protocol.Song
import dev.trashpanda.ytmp.protocol.YoutubeAccount
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistConverterTest {
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun stop() = scopes.forEach { it.cancel() }

    private fun song(id: String, title: String, artist: String) = Song("ytm:$id", title, listOf(ArtistRef(null, artist)), null, 200_000, emptyList())

    /** YouTube Music as far as these tests go: a few songs to find. */
    private class FakeSearch : SongSearch {
        val asked = CopyOnWriteArrayList<String>()
        var blocked = false
        val songs = mutableMapOf<String, List<Song>>()

        override suspend fun search(query: String, type: SearchType): SearchPage {
            if (blocked) throw SourceBlockedException("Sign in to confirm you're not a bot")
            asked += "$type $query"
            val found = if (type == SearchType.SONGS) songs[query].orEmpty() else emptyList()
            return SearchPage(listOf(SearchSection("Songs", type, found.map { SearchItem.SongResult(it) })))
        }
    }

    /** A YouTube Music account with playlists of (song, item) and likes. */
    private class FakeAccount : PersonalCatalog {
        val playlists = mutableMapOf<String, MutableList<Pair<String, String>>>()
        val titles = mutableMapOf<String, String>()
        val liked = mutableListOf<String>()
        val moves = mutableListOf<Pair<String, String?>>()
        private var n = 0

        override suspend fun account() = YoutubeAccount("Me", null, null)
        override suspend fun home() = HomePage(emptyList())
        override suspend fun library() = HomePage(emptyList())
        override suspend fun playlist(id: String, more: String?): PlaylistPage {
            val rows = playlists.getValue(id)
            return PlaylistPage(id, titles[id] ?: id, null, null, emptyList(), rows.map { Song(it.first, it.first, emptyList(), null, 0, emptyList()) }, owned = true, itemIds = rows.map { it.second })
        }
        override suspend fun liked(songId: String) = songId in liked
        override suspend fun setLiked(songId: String, liked: Boolean) {
            if (liked) this.liked += songId
        }
        override suspend fun ownPlaylists() = playlists.keys.map { PlaylistSummary(it, titles[it] ?: it, emptyList()) }
        override suspend fun addToPlaylist(playlistId: String, songIds: List<String>) {
            val rows = playlists.getValue(playlistId)
            if (songIds.any { id -> rows.any { it.first == id } }) throw SourceException("Already in this playlist")
            songIds.forEach { rows += it to "item${n++}" }
        }
        override suspend fun createPlaylist(title: String, songIds: List<String>): String {
            val id = "PL${playlists.size}"
            titles[id] = title
            playlists[id] = songIds.map { it to "item${n++}" }.toMutableList()
            return id
        }
        override suspend fun movePlaylistItem(playlistId: String, itemId: String, beforeItemId: String?) {
            moves += itemId to beforeItemId
            val rows = playlists.getValue(playlistId)
            val row = rows.first { it.second == itemId }
            rows.remove(row)
            val at = beforeItemId?.let { b -> rows.indexOfFirst { it.second == b } } ?: rows.size
            rows.add(at, row)
        }
        override suspend fun signOut() {}
    }

    private val export = """
        {"playlists":[{"name":"Road trip","items":[
          {"track":{"trackName":"Halo","artistName":"Beyoncé","albumName":"A","trackUri":"spotify:track:1"}},
          {"track":{"trackName":"Mystery","artistName":"Nobody","albumName":"B","trackUri":"spotify:track:2"}},
          {"track":{"trackName":"Stay","artistName":"Rihanna","albumName":"C","trackUri":"spotify:track:3"}},
          {"track":{"trackName":"Halo","artistName":"Beyoncé","albumName":"A","trackUri":"spotify:track:1"}}
        ]}]}
    """.trimIndent()

    private fun converter(dir: File, search: FakeSearch, account: FakeAccount): PlaylistConverter {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        return PlaylistConverter(dir, search, { account }, scope, gapMs = 0, writeGapMs = 0, likeGapMs = 0, blockedPauseMs = 300, failedPauseMs = 300)
    }

    private suspend fun PlaylistConverter.waitFor(id: String, check: (ConvertJob) -> Boolean): ConvertJob = withTimeout(10_000) {
        while (true) {
            val job = job("me", id)
            if (check(job)) return@withTimeout job
            delay(20)
        }
        @Suppress("UNREACHABLE_CODE") error("")
    }

    private fun searchWithSongs() = FakeSearch().apply {
        songs["Beyoncé Halo"] = listOf(song("halo", "Halo", "Beyoncé"))
        // Only a cover: not sure.
        songs["Nobody Mystery"] = listOf(song("cover", "Mystery", "Somebody Else"))
        songs["Rihanna Stay"] = listOf(song("stay", "Stay", "Rihanna"))
    }

    private suspend fun PlaylistConverter.startRoadTrip(): ConvertJob {
        val preview = read("me", SpotifyExport.READER, export.byteInputStream())
        assertEquals(4, preview.lists.single().count)
        return start("me", ConvertStartRequest(preview.readId, listOf(ConvertListChoice(0, ConvertTarget(ConvertTargetKind.NEW_PLAYLIST, "From Spotify")))))
    }

    @Test
    fun `looks each song up once, makes the playlist in order and waits for the unsure one`() = runBlocking {
        val search = searchWithSongs()
        val account = FakeAccount()
        val converter = converter(createTempDirectory().toFile(), search, account)
        converter.start()
        val started = converter.startRoadTrip()
        assertEquals(3, started.counts.total)

        val job = converter.waitFor(started.id) { it.status == ConvertJobStatus.NEEDS_YOU }
        // The unsure one is also looked for as a music video.
        assertEquals(4, search.asked.size)
        assertEquals(2, job.counts.matched)
        assertEquals(1, job.counts.unsure)
        val list = job.lists.single()
        assertEquals("PL0", list.playlistId)
        assertEquals("From Spotify", account.titles["PL0"])
        // Halo once, though it's in the list twice.
        assertEquals(listOf("ytm:halo", "ytm:stay"), account.playlists.getValue("PL0").map { it.first })

        // Picked by hand: added, and put in its place between Halo and Stay.
        val unsure = converter.items("me", started.id, ConvertItemState.UNSURE, null).items.single()
        assertEquals("ytm:cover", unsure.song?.id)
        converter.pick("me", started.id, unsure.key, ConvertPick(song("mystery", "Mystery", "Nobody")))
        val done = converter.waitFor(started.id) { it.status == ConvertJobStatus.DONE }
        assertEquals(listOf("ytm:halo", "ytm:mystery", "ytm:stay"), account.playlists.getValue("PL0").map { it.first })
        assertEquals(1, account.moves.size)
        assertEquals(4, done.lists.single().written)
    }

    @Test
    fun `a second import uses what was found and picked, without searching`() = runBlocking {
        val search = searchWithSongs()
        val account = FakeAccount()
        val dir = createTempDirectory().toFile()
        val converter = converter(dir, search, account)
        converter.start()
        val first = converter.startRoadTrip()
        converter.waitFor(first.id) { it.status == ConvertJobStatus.NEEDS_YOU }
        val unsure = converter.items("me", first.id, ConvertItemState.UNSURE, null).items.single()
        converter.pick("me", first.id, unsure.key, ConvertPick(leftOut = true))
        converter.waitFor(first.id) { it.status == ConvertJobStatus.DONE }
        val asked = search.asked.size

        // Also after a restart: the memory is kept in the folder.
        val again = converter(dir, search, account)
        again.start()
        val second = again.startRoadTrip()
        assertEquals(0, second.counts.pending)
        assertEquals(1, second.counts.leftOut)
        again.waitFor(second.id) { it.status == ConvertJobStatus.DONE }
        assertEquals(asked, search.asked.size)
        assertEquals(listOf("ytm:halo", "ytm:stay"), account.playlists.getValue("PL1").map { it.first })
    }

    @Test
    fun `the bot check pauses it, then it goes on`() = runBlocking {
        val search = searchWithSongs().apply { blocked = true }
        val account = FakeAccount()
        val converter = converter(createTempDirectory().toFile(), search, account)
        converter.start()
        val started = converter.startRoadTrip()
        val held = converter.waitFor(started.id) { it.waitingUntil != null }
        assertEquals("YouTube asked to confirm this isn't a bot", held.waiting)
        assertEquals(3, held.counts.pending)
        search.blocked = false
        val job = converter.waitFor(started.id) { it.status == ConvertJobStatus.NEEDS_YOU }
        assertNull(job.waiting)
    }

    @Test
    fun `an existing playlist gets only what isn't in it, liked songs can become likes`() = runBlocking {
        val search = searchWithSongs()
        val account = FakeAccount().apply {
            playlists["PLmine"] = mutableListOf("ytm:stay" to "old")
        }
        val converter = converter(createTempDirectory().toFile(), search, account)
        converter.start()
        val preview = converter.read("me", SpotifyExport.READER, export.byteInputStream())
        val job = converter.start("me", ConvertStartRequest(preview.readId, listOf(ConvertListChoice(0, ConvertTarget(ConvertTargetKind.EXISTING_PLAYLIST, playlistId = "PLmine")))))
        converter.waitFor(job.id) { it.status == ConvertJobStatus.NEEDS_YOU }
        assertEquals(listOf("ytm:stay", "ytm:halo"), account.playlists.getValue("PLmine").map { it.first })

        val library = """{"tracks":[{"artist":"Beyoncé","album":"A","track":"Halo","uri":"spotify:track:1"}]}"""
        val likes = converter.read("me", SpotifyExport.READER, library.byteInputStream())
        val liking = converter.start("me", ConvertStartRequest(likes.readId, listOf(ConvertListChoice(0, ConvertTarget(ConvertTargetKind.LIKES)))))
        converter.waitFor(liking.id) { it.status == ConvertJobStatus.DONE }
        assertEquals(listOf("ytm:halo"), account.liked)
    }

    @Test
    fun `imports are per person, and wrong picks are refused`() = runBlocking {
        val converter = converter(createTempDirectory().toFile(), searchWithSongs(), FakeAccount())
        val job = converter.startRoadTrip()
        assertTrue(converter.jobs("someone else").isEmpty())
        val e = runCatching { converter.job("someone else", job.id) }.exceptionOrNull()
        assertTrue(e is ConvertException && e.notFound)
        val read = converter.read("me", SpotifyExport.READER, export.byteInputStream())
        val shows = runCatching { converter.start("me", ConvertStartRequest(read.readId, listOf(ConvertListChoice(0, ConvertTarget(ConvertTargetKind.FOLLOW))))) }.exceptionOrNull()
        assertTrue(shows is ConvertException)
        val unused = runCatching { converter.start("someone else", ConvertStartRequest(read.readId, listOf(ConvertListChoice(0, ConvertTarget(ConvertTargetKind.LIKES))))) }.exceptionOrNull()
        assertTrue(unused is ConvertException)
    }
}
