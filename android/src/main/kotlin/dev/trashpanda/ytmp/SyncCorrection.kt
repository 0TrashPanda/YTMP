package dev.trashpanda.ytmp

import kotlin.math.abs

/**
 * Keeping ExoPlayer in sync with the room.
 *
 * Unlike the browser (frontend/src/lib/sync.ts), this never changes the playback speed:
 * after every speed change ExoPlayer's reported position jumps by ~200 ms (audio already
 * buffered at the old speed), so speed-based correction chases a position that isn't
 * real. At constant speed the position is reliable, so drift is fixed with a seek, and
 * after a song change that is usually the only one needed.
 *
 * ExoPlayer's position also jitters by about ±120 ms between readings (measured on a
 * Pixel 7), so single readings are useless for 50 ms corrections: decisions are made on
 * the median of a few seconds of samples.
 */
class SyncCorrection {
    private val samples = ArrayList<Long>()

    /** Adds one drift reading. Returns the median once enough readings are collected, else null. */
    fun addSample(driftMs: Long): Long? {
        samples += driftMs
        if (samples.size < SAMPLES) return null
        val median = samples.sorted()[samples.size / 2]
        samples.clear()
        return median
    }

    /** Throws away readings, e.g. after a seek. */
    fun resetSamples() = samples.clear()

    /**
     * How far ahead of the target to seek: playback resumes a little after a seek, and this
     * learns by how much. Starts with a typical value.
     */
    var seekLeadMs = INITIAL_SEEK_LEAD_MS
        private set

    private var checkLeadAfterSeek = false

    /**
     * Called with the drift (positive = ahead) once playback has settled after the last seek.
     * Returns the position to seek to, or null if no correction is needed.
     */
    fun onSettledDrift(driftMs: Long, targetMs: Long, alone: Boolean = false): Long? {
        // Nobody to stay in sync with: only fix what's clearly wrong (a seek, a long stall).
        if (alone) return if (abs(driftMs) > ALONE_TOLERANCE_MS) targetMs + seekLeadMs else null
        if (checkLeadAfterSeek) {
            // After our last seek we ended up [driftMs] off: adjust the lead for next time,
            // by half the error, so one unlucky seek doesn't make it swing back and forth.
            seekLeadMs = (seekLeadMs - driftMs / 2).coerceIn(0, MAX_SEEK_LEAD_MS)
            checkLeadAfterSeek = false
        }
        if (abs(driftMs) <= TOLERANCE_MS) return null
        checkLeadAfterSeek = true
        return targetMs + seekLeadMs
    }

    /** The player was (re)loaded at [onSettledDrift]'s suggested position; check how well it landed. */
    fun onLoaded() {
        checkLeadAfterSeek = true
    }

    companion object {
        /** Closer than this counts as in sync. The median of readings still varies ±40 ms. */
        const val TOLERANCE_MS = 80L

        /** Listening alone: only drift bigger than this is fixed. */
        const val ALONE_TOLERANCE_MS = 1500L

        /** Wait this long after a seek or load before measuring again. */
        const val SETTLE_MS = 1500L

        /** Readings per decision; taken every [SAMPLE_INTERVAL_MS]. */
        const val SAMPLES = 8
        const val SAMPLE_INTERVAL_MS = 250L

        const val INITIAL_SEEK_LEAD_MS = 250L
        const val MAX_SEEK_LEAD_MS = 1500L

        /**
         * Where this device's player should be. [PlaybackTarget.syncOffsetMs] is the device's
         * sync adjustment: positive plays earlier, to make up for speaker or Bluetooth delay.
         */
        fun targetPosition(target: PlaybackTarget, nowMs: Long): Long {
            val hostNow = nowMs + target.clockOffset.toLong()
            val position = if (target.playing) target.positionMs + (hostNow - target.hostTimeMs) else target.positionMs
            return maxOf(0, position + target.syncOffsetMs)
        }
    }
}
