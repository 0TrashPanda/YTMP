package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.protocol.AlbumRef
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.PodcastRef
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GenreLookupTest {
    private fun song(title: String, vararg artists: String, durationMs: Long = 200_000) =
        Song("ytm:x", title, artists.map { ArtistRef("", it) }, AlbumRef("", "Album"), durationMs, emptyList())

    private fun apple(vararg tracks: Triple<String, String, String>, ms: Long = 201_000) =
        """{"resultCount":${tracks.size},"results":[${tracks.joinToString(",") { (t, a, g) -> """{"trackName":"$t","artistName":"$a","primaryGenreName":"$g","trackTimeMillis":$ms}""" }}]}"""

    private class Fake(val answers: Map<String, String>) {
        val asked = mutableListOf<String>()
        suspend fun get(url: String): String {
            asked += url
            return answers.entries.firstOrNull { url.startsWith(it.key) }?.value ?: throw IOException("offline")
        }
    }

    @Test
    fun `Apple's genre for the same artist and title, about as long`() = runBlocking {
        val fake = Fake(mapOf("https://itunes.apple.com/" to apple(
            Triple("Something Else", "Beyoncé", "R&B/Soul"),
            Triple("Halo (Live)", "Beyoncé", "Pop"),
            Triple("Halo", "Beyoncé", "Pop"),
        )))
        assertEquals("Pop", OnlineGenres(fake::get).genre(song("Halo", "Beyonce")))
        assertEquals(1, fake.asked.size)
    }

    @Test
    fun `Deezer's album genre when Apple doesn't have the song`() = runBlocking {
        val fake = Fake(mapOf(
            "https://itunes.apple.com/" to apple(Triple("Other Song", "Band", "Rock")),
            "https://api.deezer.com/search" to """{"data":[{"title":"Song (feat. Guest)","duration":199,"artist":{"name":"Band"},"album":{"id":42}}]}""",
            "https://api.deezer.com/album/42" to """{"id":42,"genres":{"data":[{"id":113,"name":"Dance"}]}}""",
        ))
        assertEquals("Dance", OnlineGenres(fake::get).genre(song("Song [Remastered]", "Band")))
        assertEquals(3, fake.asked.size)
    }

    @Test
    fun `none found, or a podcast`() = runBlocking {
        val fake = Fake(mapOf(
            "https://itunes.apple.com/" to apple(Triple("Halo", "Someone Else", "Pop")),
            "https://api.deezer.com/search" to """{"data":[]}""",
        ))
        assertEquals("", OnlineGenres(fake::get).genre(song("Halo", "Beyoncé")))
        val episode = song("Episode 1", "Host").copy(podcast = PodcastRef("p", "Show"))
        assertEquals("Podcast", OnlineGenres(fake::get).genre(episode))
    }

    @Test
    fun `offline or too many lookups - tried again later`() {
        runBlocking {
            assertFailsWith<IOException> { OnlineGenres(Fake(emptyMap())::get).genre(song("Halo", "Beyoncé")) }
            val limited = Fake(mapOf("https://itunes.apple.com/" to apple(), "https://api.deezer.com/" to """{"error":{"type":"Exception","message":"Quota limit exceeded","code":4}}"""))
            assertFailsWith<IOException> { OnlineGenres(limited::get).genre(song("Halo", "Beyoncé")) }
        }
    }

    @Test
    fun `titles as stores write them`() {
        assertEquals("Song", OnlineGenres.clean("Song (feat. Guest) [Remastered 2011]"))
        assertEquals("Song", OnlineGenres.clean("Song - Radio Edit"))
        assertEquals("Song", OnlineGenres.clean("Song feat. Guest"))
        assertTrue(OnlineGenres.norm("Beyoncé & Jay-Z") == OnlineGenres.norm("beyonce and jay z"))
    }
}
