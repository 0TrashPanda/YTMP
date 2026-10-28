package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.FinishedPlay
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.QueueItem
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RadioCrawlTest {
    /** The wall clock: on a phone it goes on while the coroutines' timers stand still (asleep). */
    private var wall = 1_000_000_000_000L
    private val fetched = mutableListOf<String>()
    private val learnedAt = mutableMapOf<String, Long>()
    private var settings = RadioCrawlSettings(enabled = true, paceSeconds = 90)

    private fun TestScope.crawl(overnight: Boolean = false) = RadioCrawl(
        backgroundScope,
        songs = { listOf("ytm:a", "ytm:b", "ytm:c") },
        lastFetched = { seeds -> learnedAt.filterKeys { it in seeds } },
        fetch = { seed -> fetched += seed; emptyList() },
        learned = { seed, _ -> learnedAt[seed] = wall },
        load = { settings },
        save = { settings = it },
        clock = { wall },
        overnight = overnight,
    )

    @Test
    fun `waking up after the pace went by asleep fetches the next radio at once, not a whole pace later`() = runTest {
        val crawl = crawl()
        crawl.start()
        runCurrent()
        assertEquals(1, fetched.size)
        // Asleep for two minutes: the timers didn't move.
        wall += 120_000
        runCurrent()
        assertEquals(1, fetched.size)
        crawl.nudge()
        runCurrent()
        assertEquals(2, fetched.size)
        // Woken again right away: the pace still holds.
        crawl.nudge()
        runCurrent()
        assertEquals(2, fetched.size)
        // Awake, the pace goes by as usual.
        wall += 90_000
        advanceTimeBy(90_001)
        runCurrent()
        assertEquals(3, fetched.size)
    }

    @Test
    fun `overnight is only offered where the host can crawl in the background`() = runTest {
        assertNull(crawl().set(true, 90, 90, overnight = true).overnight)
        assertEquals(true, crawl(overnight = true).set(true, 90, 90, overnight = true).overnight)
        assertEquals(true, settings.overnight)
    }

    @Test
    fun `the songs to learn come as the settings say, and likes are only offered where the host has them`() = runTest {
        val asked = mutableListOf<RadioCrawlSettings>()
        val crawl = RadioCrawl(
            backgroundScope,
            songs = { s -> asked += s; listOf("ytm:a", "ytm:a", "ytm:b") },
            lastFetched = { emptyMap() },
            fetch = { seed -> fetched += seed; emptyList() },
            learned = { _, _ -> },
            load = { settings },
            save = { settings = it },
            clock = { wall },
            likes = true,
        )
        val status = crawl.set(true, 90, 90, historyDays = 9_999, likes = false)
        assertEquals(3650, status.historyDays)
        assertEquals(false, status.likes)
        crawl.start()
        runCurrent()
        assertEquals(3650, asked.last().historyDays)
        // The same song twice is one song to learn.
        assertEquals(2, crawl.status().total)
        assertNull(crawl().status().likes)
    }

    @Test
    fun `a song played here is learned next, once it ends, but not one skipped early`() = runTest {
        val crawl = crawl()
        crawl.start()
        runCurrent()
        assertEquals(listOf("ytm:a"), fetched)
        crawl.played(play("ytm:x", heardMs = 5_000, skipped = true))
        crawl.played(play("ytm:y", heardMs = 200_000, skipped = false))
        // The pace still holds.
        runCurrent()
        assertEquals(1, fetched.size)
        wall += 90_000
        advanceTimeBy(90_001)
        runCurrent()
        assertEquals(listOf("ytm:a", "ytm:y"), fetched)
        wall += 90_000
        advanceTimeBy(90_001)
        runCurrent()
        assertEquals(listOf("ytm:a", "ytm:y", "ytm:b"), fetched)
    }

    private fun play(id: String, heardMs: Long, skipped: Boolean) = FinishedPlay(
        QueueItem("i1", Song(id, "Song", listOf(ArtistRef(null, "Artist")), null, 200_000, emptyList()), "p1", "Anna", 0), startedAt = 0, heardMs = heardMs, skipped = skipped, roomCode = "ABCD", roomName = "Room", listeners = emptyList(),
    )
}
