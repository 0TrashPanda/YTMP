package dev.trashpanda.ytmp

import dev.trashpanda.ytmp.SyncCorrection.Companion.CORRECTION_RATE
import dev.trashpanda.ytmp.SyncCorrection.Companion.NORMAL
import dev.trashpanda.ytmp.SyncCorrection.Fix
import kotlin.test.Test
import kotlin.test.assertEquals

class SyncCorrectionTest {
    private val faster = NORMAL + CORRECTION_RATE
    private val slower = NORMAL - CORRECTION_RATE

    @Test
    fun `small drift is left alone`() {
        assertEquals(Fix(NORMAL), SyncCorrection().onDrift(driftMs = 30, targetMs = 10_000, speed = NORMAL))
        assertEquals(Fix(NORMAL), SyncCorrection().onDrift(driftMs = -30, targetMs = 10_000, speed = NORMAL))
    }

    @Test
    fun `medium drift plays a little faster or slower instead of jumping`() {
        assertEquals(Fix(faster), SyncCorrection().onDrift(driftMs = -150, targetMs = 10_000, speed = NORMAL))
        assertEquals(Fix(slower), SyncCorrection().onDrift(driftMs = 150, targetMs = 10_000, speed = NORMAL))
    }

    @Test
    fun `a correction keeps going until close, then plays normally again`() {
        val sync = SyncCorrection()
        assertEquals(Fix(faster), sync.onDrift(driftMs = -30, targetMs = 10_000, speed = faster))
        assertEquals(Fix(NORMAL), sync.onDrift(driftMs = -5, targetMs = 10_000, speed = faster))
        // Overshot to the other side: stop rather than flip.
        assertEquals(Fix(NORMAL), sync.onDrift(driftMs = 30, targetMs = 10_000, speed = faster))
    }

    @Test
    fun `large drift seeks a little ahead of the target`() {
        assertEquals(Fix(NORMAL, 10_000 + SyncCorrection.INITIAL_SEEK_LEAD_MS), SyncCorrection().onDrift(driftMs = -600, targetMs = 10_000, speed = faster))
    }

    @Test
    fun `the seek lead learns from where the last seek landed`() {
        val sync = SyncCorrection()
        sync.onDrift(driftMs = -600, targetMs = 10_000, speed = NORMAL)
        // Landed 40 ms behind: next time seek half of that further ahead.
        sync.onDrift(driftMs = -40, targetMs = 20_000, speed = NORMAL)
        assertEquals(SyncCorrection.INITIAL_SEEK_LEAD_MS + 20, sync.seekLeadMs)
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
        assertEquals(Fix(NORMAL), sync.onDrift(300, 10_000, faster, alone = true))
        assertEquals(Fix(NORMAL), sync.onDrift(-1_200, 10_000, NORMAL, alone = true))
        assertEquals(Fix(NORMAL, 10_000 + sync.seekLeadMs), sync.onDrift(4_000, 10_000, NORMAL, alone = true))
    }
}
