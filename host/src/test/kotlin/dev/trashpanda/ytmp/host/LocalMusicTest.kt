package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.LocalLibraryKind
import dev.trashpanda.ytmp.protocol.SearchItem
import io.ktor.server.application.ApplicationCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalMusicTest {
    private class Shelf(override val id: String, private val list: List<LocalTrack>) : LocalLibrary {
        override val name = "Shelf"
        override val kind = LocalLibraryKind.MPD
        override suspend fun tracks() = list
        override fun fileUrl(key: String) = "http://pi/music/$key"
        override fun artUrl(key: String, size: Int) = "http://pi/art/$key?size=$size"
        override suspend fun respond(call: ApplicationCall, key: String, range: String?) = Unit
    }

    private fun track(key: String, title: String, artist: String, album: String, track: Int, albumArtist: String = artist) =
        LocalTrack(key, title, artist, albumArtist, album, disc = null, track = track, year = "2002", durationMs = 200_000, folder = key.substringBeforeLast('/'))

    private val tracks = listOf(
        track("Avril/Let Go/02.mp3", "Losing Grip", "Avril Lavigne", "Let Go", 1),
        track("Avril/Let Go/03.mp3", "Sk8er Boi", "Avril Lavigne", "Let Go", 3),
        track("Avril/Let Go/01.mp3", "Complicated", "Avril Lavigne", "Let Go", 2),
        track("Adele/21/01.mp3", "Rolling in the Deep", "Adele", "21", 1),
        track("Mix/01.mp3", "Duet", "Guest Singer", "Mix", 1, albumArtist = "Various Artists"),
        // A compilation without album artists: one album (its folder), by various artists.
        track("Hits/01.mp3", "One", "Singer A", "Hits", 1),
        track("Hits/02.mp3", "Two", "Singer B", "Hits", 2),
        // The same album name in another folder: another album.
        track("Live/Let Go/01.mp3", "Complicated (live)", "Avril Lavigne", "Let Go", 1),
        // A copy of a song in another folder: shown once.
        track("Backup/Music/Avril/Let Go/03.mp3", "Sk8er Boi", "Avril Lavigne", "Let Go", 3),
    )

    @Test
    fun `a library becomes songs, albums and artists with pages, links and autoplay`() = runBlocking {
        val local = LocalMusic()
        local.add(Shelf("pi", tracks))
        assertEquals(listOf(9), local.summaries().libraries.map { it.songCount })

        val page = local.page("pi")!!
        assertEquals(listOf("21", "Hits", "Let Go", "Let Go", "Mix"), page.albums.map { (it as SearchItem.AlbumResult).album.title })
        assertEquals(listOf("Various Artists"), page.albums.filter { (it as SearchItem.AlbumResult).album.title == "Hits" }.map { (it as SearchItem.AlbumResult).artists.single().name })
        assertEquals(listOf("Adele", "Avril Lavigne", "Guest Singer", "Singer A", "Singer B", "Various Artists"), page.artists.map { (it as SearchItem.ArtistResult).name })

        // The album in track order, with its cover (the folder "Avril/Let Go", not the copy).
        val letGo = page.albums.map { (it as SearchItem.AlbumResult).album }.first { it.title == "Let Go" && "Avril%2FLet+Go" in it.id }
        val album = local.album(letGo.id)!!
        assertEquals(listOf("Losing Grip", "Complicated", "Sk8er Boi"), album.songs.map { it.title })
        assertEquals("http://pi/art/Avril/Let Go/02.mp3?size=544", album.thumbnails.last().url)
        assertEquals("2002", album.year)

        // An artist: their albums, and the songs they sing on (also on someone else's album).
        val guest = page.artists.map { it as SearchItem.ArtistResult }.first { it.name == "Guest Singer" }
        assertEquals(listOf("Duet"), local.artist(guest.id)!!.songs.map { it.title })
        val avril = local.artist(page.artists.map { it as SearchItem.ArtistResult }.first { it.name == "Avril Lavigne" }.id)!!
        assertEquals(listOf("Let Go", "Let Go"), avril.albums.map { it.title })

        // Played through the Pi's web server; YouTube songs aren't local music.
        val song = album.songs.first()
        assertEquals("local:pi:s:Avril/Let Go/02.mp3", song.id)
        assertEquals("http://pi/music/Avril/Let Go/02.mp3", local.stream(song.id)!!.url)
        assertNull(local.stream("ytm:abc"))
        assertNull(local.album("MPREb_abc"))

        // Autoplay goes on with the library, never the song itself.
        val radio = local.radio(song.id)!!
        assertEquals(7, radio.size)
        assertEquals(8, page.songs.size)
        assertTrue(radio.none { it.id == song.id })
        assertNull(local.radio("ytm:abc"))
    }

    @Test
    fun `an MPD's tags become tracks`() {
        val t = MpdLibrary.track(mapOf("file" to "A/B/03 Song.mp3", "Artist" to "Band", "Album" to "Record", "Track" to "3/12", "Date" to "2004-05-01", "duration" to "201.5"))!!
        assertEquals(LocalTrack("A/B/03 Song.mp3", "03 Song", "Band", "Band", "Record", null, 3, "2004", 201_500, folder = "A/B"), t)
        assertNull(MpdLibrary.track(mapOf("file" to "http://radio.example/stream")))
        assertEquals("Unknown artist", MpdLibrary.track(mapOf("file" to "x.mp3"))!!.artist)
    }

    @Test
    fun `a zones file shares the Pi's music`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        FakeMpd().use { mpd ->
            FakeAmp().use { amp ->
                mpd.library = listOf(
                    mapOf("file" to "Adele/21/01.mp3", "Title" to "Rolling in the Deep", "Artist" to "Adele", "Album" to "21", "Track" to "1", "duration" to "228.0"),
                    mapOf("file" to "Adele/21/02.mp3", "Title" to "Rumour Has It", "Artist" to "Adele", "Album" to "21", "Track" to "2", "duration" to "223.0"),
                )
                amp.zonesFile = """{"player": {"port": ${mpd.port}}, "music": {"name": "Music on the Pi"}}"""
                val local = LocalMusic()
                val zones = ZoneOutputs(scope, { "http://127.0.0.1:8765" }, null, local)
                zones.addSource("test", "http://127.0.0.1:${amp.port}/ytmp-zones.json")
                withTimeout(10_000) { while (local.all.value.isEmpty()) delay(50) }
                val library = local.all.value.values.single()
                assertEquals("Music on the Pi", library.name)
                val page = local.page(library.id)!!
                assertEquals(listOf("Rolling in the Deep", "Rumour Has It"), page.songs.map { it.title })
                // Files and covers from the same web server as the file.
                assertEquals("http://127.0.0.1:${amp.port}/music/Adele/21/01.mp3", local.stream(page.songs.first().id)!!.url)
                assertNotNull(page.songs.first().thumbnails.last().url.startsWith("http://127.0.0.1:${amp.port}/art/Adele/21/01.mp3"))
                // No amp: no zones, just the music.
                assertTrue(zones.devices.value.isEmpty())

                zones.removeSource("test")
                assertTrue(local.all.value.isEmpty())
            }
        }
        scope.cancel()
    }
}
