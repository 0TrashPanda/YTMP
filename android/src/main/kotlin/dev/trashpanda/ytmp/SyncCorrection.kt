package dev.trashpanda.ytmp

import kotlin.math.abs

/**
 * Keeping ExoPlayer in sync with the room: the same rules as the browser's
 * (frontend/src/lib/sync.ts); keep the two in step.
 *
 * Small drift is fixed by playing a little faster or slower (pitch kept), big drift with a
 * seek. This needs the real position, read from the audio output ([AudioClock]), and the
 * speed set on the AudioTrack itself, which changes what you hear at once. (ExoPlayer's own
 * `currentPosition` moves in ~250 ms steps, which looked like jumps after every speed change
 * and made this class seek all the time; see docs/implementation/playback-sync.md.)
 */
class SyncCorrection {
    /** What to do: play at [speed], and seek to [seekTo] first if it isn't null. */
    data class Fix(val speed: Float, val seekTo: Long? = null)

    /**
     * How far ahead of the target to seek: playback resumes a little after a seek, and this
     * learns by how much. Starts with a typical value.
     */
    var seekLeadMs = INITIAL_SEEK_LEAD_MS
        private set

    private var checkLeadAfterSeek = false

    /**
     * How to fix [driftMs] (positive = ahead of the room) while playing at [speed]; [targetMs]
     * is where the room is. Called with every reading once playback has settled.
     */
    fun onDrift(driftMs: Long, targetMs: Long, speed: Float, alone: Boolean = false): Fix {
        val size = abs(driftMs)
        // Nobody to stay in sync with: only fix what's clearly wrong (a seek, a long stall).
        if (alone) return if (size > ALONE_TOLERANCE_MS) seek(targetMs) else Fix(NORMAL)
        if (checkLeadAfterSeek) {
            // After our last seek we ended up [driftMs] off: adjust the lead for next time,
            // by half the error, so one unlucky seek doesn't make it swing back and forth.
            seekLeadMs = (seekLeadMs - driftMs / 2).coerceIn(0, MAX_SEEK_LEAD_MS)
            checkLeadAfterSeek = false
        }
        if (size > SEEK_THRESHOLD_MS) return seek(targetMs)
        val correcting = if (driftMs > 0) NORMAL - CORRECTION_RATE else NORMAL + CORRECTION_RATE
        if (speed != NORMAL) {
            // Keep going until close enough, or until we overshot to the other side.
            return Fix(if (size < STOP_CORRECTING_MS || speed != correcting) NORMAL else speed)
        }
        return Fix(if (size > START_CORRECTING_MS) correcting else NORMAL)
    }

    private fun seek(targetMs: Long): Fix {
        checkLeadAfterSeek = true
        return Fix(NORMAL, targetMs + seekLeadMs)
    }

    /** The player was (re)loaded at a position from [seekLeadMs]; check how well it landed. */
    fun onLoaded() {
        checkLeadAfterSeek = true
    }

    companion object {
        const val NORMAL = 1f

        /** Further off than this, seek instead of speeding up or slowing down. */
        const val SEEK_THRESHOLD_MS = 400L

        /** Start correcting when further off than this… */
        const val START_CORRECTING_MS = 40L

        /** …and stop once closer than this. The gap avoids flipping speed back and forth. */
        const val STOP_CORRECTING_MS = 10L

        /** Speed change while correcting: 3% faster or slower. */
        const val CORRECTION_RATE = 0.03f

        /** Listening alone: only drift bigger than this is fixed with a seek here. */
        const val ALONE_TOLERANCE_MS = 1500L

        /**
         * Listening alone: drift bigger than this, which this device caused (buffering at the
         * start of a song), moves the room to this device instead: nothing to hear.
         */
        const val ALONE_ROOM_TOLERANCE_MS = 400L

        /** More drift than this isn't a slow start: this device follows the room. */
        const val MAX_ROOM_FOLLOW_MS = 10_000L

        /** Wait this long after a seek or load before measuring again. */
        const val SETTLE_MS = 1000L

        /** How often to measure while playing with others. */
        const val SAMPLE_INTERVAL_MS = 250L

        /** Listening alone, after [QUICK_MEASURE_MS] of settled playback: one reading per second. */
        const val ALONE_SAMPLE_INTERVAL_MS = 1000L

        /** Long enough for a few quick readings after a load or seek. */
        const val QUICK_MEASURE_MS = 2000L

        /**
         * The room's clock offset (re-estimated from pings) moves at most this much per
         * reading, so a new estimate becomes a gentle speed change rather than a seek…
         */
        const val OFFSET_SLEW_MS = 2.0

        /** …unless it moved this much: then it's a real change, taken at once. */
        const val OFFSET_JUMP_MS = 500.0

        const val INITIAL_SEEK_LEAD_MS = 250L
        const val MAX_SEEK_LEAD_MS = 1500L

        /**
         * Where this device's player should be. [PlaybackTarget.syncOffsetMs] is the device's
         * sync adjustment: positive plays earlier, to make up for speaker or Bluetooth delay.
         */
        fun targetPosition(target: PlaybackTarget, nowMs: Long): Long {
            val hostNow = nowMs + target.clockOffset.toLong()
            // An episode at another speed moves on (and makes up the delay) at that speed.
            val position = if (target.playing) target.positionMs + ((hostNow - target.hostTimeMs) * target.speed).toLong() else target.positionMs
            return maxOf(0, position + (target.syncOffsetMs * target.speed).toLong())
        }

        /** The player's speed apart from the room's ([roomSpeed]): what [onDrift] works with. Rounded (1.455 / 1.5 is 0.97). */
        fun ownSpeed(playerSpeed: Float, roomSpeed: Float): Float = Math.round(playerSpeed / roomSpeed * 1000) / 1000f
    }
}
