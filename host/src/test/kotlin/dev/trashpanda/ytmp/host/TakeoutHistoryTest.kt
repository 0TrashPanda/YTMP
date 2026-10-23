package dev.trashpanda.ytmp.host

import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TakeoutHistoryTest {
    private val zone = ZoneOffset.UTC
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) = LocalDateTime.of(y, mo, d, h, mi, s).toInstant(zone).toEpochMilli()

    /** A cell the way Takeout writes it. */
    private fun cell(product: String, url: String, title: String, channel: String?, date: String) =
        """<div class="outer-cell mdl-cell mdl-cell--12-col mdl-shadow--2dp"><div class="mdl-grid"><div class="header-cell mdl-cell mdl-cell--12-col"><p class="mdl-typography--title">$product<br></p></div>""" +
            """<div class="content-cell mdl-cell mdl-cell--6-col mdl-typography--body-1">Watched&nbsp;<a href="$url">$title</a><br>""" +
            (channel?.let { """<a href="https://www.youtube.com/channel/UC1">$it</a><br>""" } ?: "") +
            """$date<br></div><div class="content-cell mdl-cell mdl-cell--6-col mdl-typography--body-1 mdl-typography--text-right"></div>""" +
            """<div class="content-cell mdl-cell mdl-cell--12-col mdl-typography--caption"><b>Products:</b><br>&emsp;YouTube<br></div></div></div>"""

    private val html = "<html><head><style>.x{}</style></head><body><div class=\"mdl-grid\">" +
        cell("YouTube Music", "https://music.youtube.com/watch?v=ts-nSxAApXk", "The Dove", "Baby Bugs - Topic", "23 Oct 2026, 17:30:20 CEST") +
        cell("YouTube", "https://www.youtube.com/watch?v=aaaaaaaaaaa", "A video", "Someone", "23 Oct 2026, 17:20:00 CEST") +
        cell("YouTube Music", "https://music.youtube.com/watch?v=ts-nSxAApXk", "The Dove", "Baby Bugs - Topic", "22 Oct 2026, 09:00:00 CEST") +
        cell("YouTube Music", "https://music.youtube.com/watch?v=GwSSrwryxN0", "Rock &amp; Roll", null, "14 Aug 2024, 22:15:53 CEST") +
        "</div></body></html>"

    @Test
    fun `reads the YouTube Music plays in the HTML history, leaving YouTube out`() {
        val songs = Takeout.read(html.byteInputStream(), zone).associateBy { it.id }
        assertEquals(setOf("ts-nSxAApXk", "GwSSrwryxN0"), songs.keys)
        val dove = songs.getValue("ts-nSxAApXk")
        assertEquals("The Dove", dove.title)
        assertEquals("Baby Bugs", dove.artist)
        assertEquals(listOf(at(2026, 10, 22, 9, 0), at(2026, 10, 23, 17, 30, 20)), dove.plays)
        val rock = songs.getValue("GwSSrwryxN0")
        assertEquals("Rock & Roll", rock.title)
        assertNull(rock.artist)
    }

    @Test
    fun `reads the history in a Takeout zip, and the JSON export`() {
        val json = """[{"header":"YouTube Music","title":"Watched The Dove","titleUrl":"https://music.youtube.com/watch?v=ts-nSxAApXk","subtitles":[{"name":"Baby Bugs - Topic","url":"x"}],"time":"2026-10-23T15:30:20.123Z","products":["YouTube"]},
            {"header":"YouTube","title":"Watched A video","titleUrl":"https://www.youtube.com/watch?v=aaaaaaaaaaa","time":"2026-10-23T15:20:00Z"}]"""
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("Takeout/YouTube and YouTube Music/history/search-history.html"))
                z.write("<html></html>".toByteArray())
                z.putNextEntry(ZipEntry("Takeout/YouTube and YouTube Music/history/watch-history.json"))
                z.write(json.toByteArray())
            }
        }.toByteArray()
        val songs = Takeout.read(zip.inputStream(), zone)
        assertEquals(listOf("ts-nSxAApXk"), songs.map { it.id })
        assertEquals("The Dove", songs[0].title)
        assertEquals("Baby Bugs", songs[0].artist)
        assertEquals(listOf(at(2026, 10, 23, 15, 30, 20) + 123), songs[0].plays)
        // A zip without a history.
        val empty = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("Takeout/archive_browser.html")); z.write(1) } }.toByteArray()
        assertFailsWith<IllegalArgumentException> { Takeout.read(empty.inputStream(), zone) }
        assertFailsWith<IllegalArgumentException> { Takeout.read("<html>nothing</html>".byteInputStream(), zone) }
    }

    @Test
    fun `reads dates in other languages and formats`() {
        assertEquals(at(2026, 10, 23, 17, 30, 20), TakeoutDates.parse("23 Oct 2026, 17:30:20 CEST", zone))
        assertEquals(at(2025, 9, 3, 8, 5, 1), TakeoutDates.parse("3 Sept 2025, 08:05:01 CEST", zone))
        assertEquals(at(2026, 10, 23, 17, 30, 20), TakeoutDates.parse("Oct 23, 2026, 5:30:20 PM CEST", zone))
        assertEquals(at(2026, 10, 23, 0, 30, 0), TakeoutDates.parse("Oct 23, 2026, 12:30:00 AM", zone))
        assertEquals(at(2026, 3, 2, 17, 30, 20), TakeoutDates.parse("2 mrt 2026, 17:30:20 CET", zone))
        assertEquals(at(2026, 5, 2, 17, 30, 20), TakeoutDates.parse("02.05.2026, 17:30:20 MESZ", zone))
        assertEquals(at(2026, 7, 14, 10, 0, 0), TakeoutDates.parse("14 juil. 2026, 10:00:00 UTC+2", zone))
        assertEquals(at(2026, 6, 14, 10, 0, 0), TakeoutDates.parse("14 juin 2026, 10:00:00", zone))
        assertNull(TakeoutDates.parse("yesterday", zone))
    }

    @Test
    fun `picks what was played most in the chosen time, and keeps it`() {
        val file = createTempDirectory("takeout").toFile().resolve("takeout.json")
        val history = TakeoutHistory(file)
        assertNull(history.summary())
        val summary = history.import(html.byteInputStream(), zone)
        assertEquals(2, summary.songs)
        assertEquals(3, summary.plays)
        assertEquals(at(2024, 8, 14, 22, 15, 53), summary.first)
        val now = at(2026, 10, 24, 12, 0)
        assertEquals(listOf("ts-nSxAApXk", "GwSSrwryxN0"), history.pick(0, 1, now).map { it.id })
        assertEquals(listOf("ts-nSxAApXk"), history.pick(0, 2, now).map { it.id })
        assertEquals(listOf("ts-nSxAApXk"), history.pick(30, 1, now).map { it.id })
        // Only one play in the last day.
        assertEquals(emptyList(), history.pick(1, 2, now).map { it.id })
        // Read back from the file.
        assertEquals(summary, TakeoutHistory(file).summary())
        history.forget()
        assertNull(TakeoutHistory(file).summary())
    }
}
