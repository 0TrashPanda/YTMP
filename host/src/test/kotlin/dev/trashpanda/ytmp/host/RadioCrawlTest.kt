package dev.trashpanda.ytmp.host

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
    private var settings = RadioCrawlSettings(enabled = true, paceSeconds = 90)

    private fun TestScope.crawl(overnight: Boolean = false) = RadioCrawl(
        backgroundScope,
        songs = { listOf("ytm:a", "ytm:b", "ytm:c") },
        lastFetched = { emptyMap() },
        fetch = { seed -> fetched += seed; emptyList() },
        learned = { _, _ -> },
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
}
