package dev.trashpanda.ytmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncCorrectionTest {
    @Test
    fun `small drift is left alone`() {
        assertNull(SyncCorrection().onSettledDrift(driftMs = 40, targetMs = 10_000))
        assertNull(SyncCorrection().onSettledDrift(driftMs = -40, targetMs = 10_000))
    }

    @Test
    fun `larger drift seeks a little ahead of the target`() {
        val sync = SyncCorrection()
        assertEquals(10_000 + SyncCorrection.INITIAL_SEEK_LEAD_MS, sync.onSettledDrift(driftMs = -300, targetMs = 10_000))
    }

    @Test
    fun `the seek lead learns from where the last seek landed`() {
        val sync = SyncCorrection()
        sync.onSettledDrift(driftMs = -300, targetMs = 10_000)
        // Landed 40 ms behind: next time seek half of that further ahead.
        assertNull(sync.onSettledDrift(driftMs = -40, targetMs = 20_000))
        assertEquals(SyncCorrection.INITIAL_SEEK_LEAD_MS + 20, sync.seekLeadMs)
    }

    @Test
    fun `decisions use the median of several readings`() {
        val sync = SyncCorrection()
        val readings = listOf(-900L, -1050, -1130, -1040, -900, -1000, -1060, -930)
        val results = readings.map { sync.addSample(it) }
        assertEquals(List(SyncCorrection.SAMPLES - 1) { null }, results.dropLast(1))
        assertEquals(-1000L, results.last())
    }

    @Test
    fun `sync adjustment moves the target earlier`() {
        val target = PlaybackTarget(
            enabled = true, item = null, streamUrl = null, proxyUrl = null,
            playing = true, positionMs = 10_000, hostTimeMs = 1_000, clockOffset = 0.0,
            syncOffsetMs = 200, volume = 1f,
        )
        assertEquals(10_000 + 500 + 200, SyncCorrection.targetPosition(target, nowMs = 1_500))
    }

    @Test
    fun `listening alone, small drift is left alone and only big drift is fixed`() {
        val sync = SyncCorrection()
        assertNull(sync.onSettledDrift(300, 10_000, alone = true))
        assertNull(sync.onSettledDrift(-1_200, 10_000, alone = true))
        assertEquals(10_000 + sync.seekLeadMs, sync.onSettledDrift(4_000, 10_000, alone = true))
    }
}
