package dev.trashpanda.ytmp.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackFirstTest {
    @Test
    fun `other work waits while a stream is looked up`() = runTest {
        val gate = PlaybackFirst()
        val done = mutableListOf<String>()
        launch { gate.playback { delay(2_000); done += "stream" } }
        launch { delay(100); gate.other { done += "radio" } }
        launch { delay(100); gate.playback { done += "next stream" } }
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("next stream", "stream", "radio"), done)
    }

    @Test
    fun `other work doesn't wait forever`() = runTest {
        val gate = PlaybackFirst(maxWaitMs = 1_000)
        var at = -1L
        launch { gate.playback { delay(10_000) } }
        launch { delay(100); gate.other { at = testScheduler.currentTime } }
        testScheduler.advanceUntilIdle()
        assertEquals(1_100L, at)
    }
}
