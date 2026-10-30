package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.ConvertItemType
import dev.trashpanda.ytmp.protocol.ConvertListKind
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SpotifyExportTest {
    private val playlist1 = """
        {"playlists":[
          {"name":"Road trip","lastModifiedDate":"2026-05-01","description":null,"numberOfFollowers":0,"items":[
            {"track":{"trackName":"Halo","artistName":"Beyoncé","albumName":"I Am... Sasha Fierce","trackUri":"spotify:track:4JehYebiI9JE8sR8MisGVb"},"episode":null,"localTrack":null,"audiobook":null,"addedDate":"2026-04-01"},
            {"track":null,"episode":{"episodeName":"Episode 12","showName":"Some Podcast","episodeUri":"spotify:episode:abc"},"localTrack":null,"audiobook":null,"addedDate":"2026-04-02"},
            {"track":null,"episode":null,"localTrack":{"uri":"spotify:local:Daft+Punk:Discovery:One+More+Time:320"},"audiobook":null,"addedDate":"2026-04-03"},
            {"track":null,"episode":null,"localTrack":null,"audiobook":{"name":"A book"},"addedDate":"2026-04-04"}
          ]}
        ]}
    """.trimIndent()

    private val playlist2 = """{"playlists":[{"name":"Second","items":[{"track":{"trackName":"Stay","artistName":"Rihanna","albumName":"Unapologetic","trackUri":"spotify:track:x"}}]}]}"""

    private val library = """
        {"tracks":[{"artist":"Texas","album":"White on Blonde","track":"Say What You Want","uri":"spotify:track:y"},{"artist":"Nobody"}],
         "albums":[{"artist":"A","album":"B","uri":"spotify:album:z"}],
         "shows":[{"name":"The Daily","publisher":"The New York Times","uri":"spotify:show:d"}],
         "episodes":[{"name":"Monday","show":"The Daily","uri":"spotify:episode:m"}],
         "bannedTracks":[], "artists":[]}
    """.trimIndent()

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, text) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `reads the playlists in order, then the library, from the zip`() {
        val read = SpotifyExport.read(
            zip(
                "Spotify Account Data/Userdata.json" to """{"username":"private"}""",
                "Spotify Account Data/YourLibrary.json" to library,
                "Spotify Account Data/Playlist2.json" to playlist2,
                "Spotify Account Data/Playlist1.json" to playlist1,
            ).inputStream(),
        )
        assertEquals(listOf("Road trip", "Second", "Liked songs", "Saved episodes", "Followed podcasts"), read.lists.map { it.name })
        assertEquals(
            listOf(ConvertListKind.PLAYLIST, ConvertListKind.PLAYLIST, ConvertListKind.LIKED_SONGS, ConvertListKind.SAVED_EPISODES, ConvertListKind.FOLLOWED_SHOWS),
            read.lists.map { it.kind },
        )
        // The audiobook and the liked song without a title.
        assertEquals(2, read.skipped)

        val trip = read.lists[0].items
        assertEquals(3, trip.size)
        assertEquals(ConvertItemType.SONG, trip[0].type)
        assertEquals("Halo", trip[0].title)
        assertEquals(listOf("Beyoncé"), trip[0].artists)
        assertEquals("I Am... Sasha Fierce", trip[0].album)
        assertEquals("spotify:track:4JehYebiI9JE8sR8MisGVb", trip[0].sourceId)
        assertEquals(ConvertItemType.EPISODE, trip[1].type)
        assertEquals("Some Podcast", trip[1].show)
        assertEquals(ConvertItemType.LOCAL_FILE, trip[2].type)
        assertEquals("One More Time", trip[2].title)
        assertEquals(listOf("Daft Punk"), trip[2].artists)
        assertEquals(320_000L, trip[2].durationMs)

        assertEquals("Say What You Want", read.lists[2].items.single().title)
        assertEquals("The Daily", read.lists[3].items.single().show)
        assertEquals(ConvertItemType.SHOW, read.lists[4].items.single().type)
    }

    @Test
    fun `one json picked on its own works too`() {
        assertEquals(listOf("Road trip"), SpotifyExport.read(playlist1.byteInputStream()).lists.map { it.name })
        assertEquals("Liked songs", SpotifyExport.read(library.byteInputStream()).lists.first().name)
    }

    @Test
    fun `something else is refused with one message`() {
        val e = assertFailsWith<ConvertReadException> { SpotifyExport.read(zip("Takeout/watch-history.json" to "[]").inputStream()) }
        assertEquals(true, e.message!!.startsWith("This isn't a Spotify data export"))
        assertFailsWith<ConvertReadException> { SpotifyExport.read("not a zip at all".byteInputStream()) }
        assertFailsWith<ConvertReadException> { SpotifyExport.read("""{"playlists":[]}""".byteInputStream()) }
    }

    @Test
    fun `local file uris`() {
        assertNull(SpotifyExport.localFile("spotify:track:x"))
        assertNull(SpotifyExport.localFile("spotify:local:::"))
        val item = SpotifyExport.localFile("spotify:local:::Untitled%3A+Part+2:0")!!
        assertEquals("Untitled: Part 2", item.title)
        assertEquals(emptyList(), item.artists)
        assertNull(item.album)
        assertNull(item.durationMs)
    }
}
