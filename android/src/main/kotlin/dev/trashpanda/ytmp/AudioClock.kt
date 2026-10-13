package dev.trashpanda.ytmp

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.util.concurrent.atomic.AtomicReference

/**
 * Where ExoPlayer's audio really is, read from its audio output.
 *
 * `ExoPlayer.currentPosition` isn't advanced between the playback thread's updates: it stands
 * still for ~250 ms and then jumps (measured: a ±125 ms sawtooth), which made sync decisions
 * chase errors that weren't there. The audio output is asked for its position every few ms
 * by the playback thread; each answer is kept here with the exact time, and extrapolated to
 * now (±1 ms, see docs/implementation/playback-sync.md).
 */
@OptIn(UnstableApi::class)
class AudioClock(sink: AudioSink) : ForwardingAudioSink(sink) {
    private class Reading(val positionUs: Long, val atNanos: Long)

    private val last = AtomicReference<Reading?>(null)

    /** The output's timeline runs ahead of the song's by this much (set for each stream). */
    @Volatile
    private var streamOffsetUs = 0L

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        streamOffsetUs = outputStreamOffsetUs
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long =
        super.getCurrentPositionUs(sourceEnded).also {
            if (it != AudioSink.CURRENT_POSITION_NOT_SET) last.set(Reading(it - streamOffsetUs, SystemClock.elapsedRealtimeNanos()))
        }

    override fun flush() {
        // A seek or a new song: the old readings are about other audio.
        last.set(null)
        super.flush()
    }

    /**
     * The song position (ms) playing now, at [speed]; null without a recent reading. Only
     * meaningful while the player is playing (paused, the last reading doesn't move on).
     */
    fun positionMs(speed: Float): Long? {
        val reading = last.get() ?: return null
        val ageNanos = SystemClock.elapsedRealtimeNanos() - reading.atNanos
        if (ageNanos > MAX_AGE_NANOS) return null
        return (reading.positionUs + (ageNanos / 1000 * speed).toLong()) / 1000
    }

    private companion object {
        /** The playback thread asks every few ms while playing; older means it isn't playing. */
        const val MAX_AGE_NANOS = 500_000_000L
    }
}
