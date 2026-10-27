package dev.trashpanda.ytmp.host

import dev.trashpanda.ytmp.core.FinishedPlay
import dev.trashpanda.ytmp.protocol.ArtistRef
import dev.trashpanda.ytmp.protocol.AuthServerRef
import dev.trashpanda.ytmp.protocol.ListenedWith
import dev.trashpanda.ytmp.protocol.PlayReport
import dev.trashpanda.ytmp.protocol.QueueItem
import dev.trashpanda.ytmp.protocol.Song
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayReporterTest {
    private val sent = mutableListOf<Pair<String, PlayReport>>()

    private val auth = object : HostAuth {
        override fun servers() = emptyList<AuthServerRef>()
        override fun verify(token: String) = null
        override suspend fun reportPlay(token: String, report: PlayReport) {
            sent += token to report
        }
    }

    private val item = QueueItem("i1", Song("ytm:a", "A", listOf(ArtistRef(null, "Artist")), null, 200_000, emptyList()), "p1", "Anna", 0)

    private fun play(vararg listeners: FinishedPlay.Listener) = FinishedPlay(item, 1_000, 200_000, false, "ABCD", "Room", listeners.toList())

    @Test
    fun `someone in the room from two devices hears the song once, and not with themselves`() = runTest {
        val reporter = PlayReporter(auth, this)
        reporter.report(
            play(
                FinishedPlay.Listener("p1", "Anna", "anna@x", "phone-token", false),
                FinishedPlay.Listener("p2", "Anna", "anna@x", "laptop-token", false),
            ),
        )
        testScheduler.advanceUntilIdle()
        val report = sent.single().second
        assertFalse(report.shared)
        assertEquals(emptyList(), report.listenedWith)
        assertTrue(report.addedByMe)
    }

    @Test
    fun `with someone else it's shared, once each`() = runTest {
        val reporter = PlayReporter(auth, this)
        reporter.report(
            play(
                FinishedPlay.Listener("p1", "Anna", "anna@x", "t1", false),
                FinishedPlay.Listener("p2", "Anna", "anna@x", "t2", false),
                FinishedPlay.Listener("p3", "Bert", "bert@x", "t3", false),
                FinishedPlay.Listener("p4", "Guest", null, null, false),
            ),
        )
        testScheduler.advanceUntilIdle()
        assertEquals(2, sent.size)
        val anna = sent.first { it.first in setOf("t1", "t2") }.second
        assertTrue(anna.shared)
        assertEquals(listOf(ListenedWith("bert@x", "Bert"), ListenedWith(null, "Guest")), anna.listenedWith)
    }
}
