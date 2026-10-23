package dev.trashpanda.ytmp

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The equalizer's setting for one output (docs/features/playback-and-outputs.md#equalizer), as
 * the web app makes it (equalizer.svelte.ts): [gains] in dB per band of [Equalizer.BANDS],
 * [preset] the preset they come from (null: your own).
 */
@Serializable
data class EqSettings(val on: Boolean, val gains: List<Float>, val preset: String? = null) {
    /** Within range, one gain per band; anything else is off. */
    fun valid(): EqSettings =
        if (gains.size != Equalizer.BANDS.size || gains.any { !it.isFinite() }) OFF
        else copy(gains = gains.map { it.coerceIn(-Equalizer.MAX_DB, Equalizer.MAX_DB) })

    companion object {
        val OFF = EqSettings(on = false, gains = List(Equalizer.BANDS.size) { 0f }, preset = "flat")
    }
}

/**
 * The equalizer's filters for one stream of [channels] interleaved channels at [sampleRate]: a
 * peaking filter per band (the same as the browser's Web Audio `BiquadFilterNode`, from the
 * Audio EQ Cookbook), then the whole sound turned down by the biggest boost so it doesn't clip.
 */
class Equalizer(private val sampleRate: Int, private val channels: Int) {
    /** Per band ([BANDS]): b0, b1, b2, a1, a2 (divided by a0); null when it does nothing. */
    private val coefficients = arrayOfNulls<DoubleArray>(BANDS.size)
    /** The bands that do something. */
    private var active = IntArray(0)
    /** Per band and channel ([band * channels + channel]): the filter's two memories. */
    private val memory = Array(BANDS.size * channels) { DoubleArray(2) }
    private var gain = 1.0
    private var applied: EqSettings? = null

    /** Uses [settings] from now on (cheap when they're the ones already in use). */
    fun use(settings: EqSettings) {
        if (settings == applied) return
        applied = settings
        for (band in BANDS.indices) {
            val db = settings.gains[band]
            coefficients[band] = if (db != 0f && BANDS[band] < sampleRate * 0.45) peaking(BANDS[band], db.toDouble()) else null
            // A band that stopped starts quiet next time; the others keep going, so moving a slider doesn't click.
            if (coefficients[band] == null) for (channel in 0 until channels) memory[band * channels + channel].fill(0.0)
        }
        active = BANDS.indices.filter { coefficients[it] != null }.toIntArray()
        gain = 10.0.pow(headroom(settings.gains) / 20.0)
    }

    /** The next [sample] (-1..1) of [channel]. */
    fun process(sample: Double, channel: Int): Double {
        var x = sample
        for (band in active) {
            val c = coefficients[band]!!
            val m = memory[band * channels + channel]
            // Transposed direct form II.
            val y = c[0] * x + m[0]
            m[0] = c[1] * x - c[3] * y + m[1]
            m[1] = c[2] * x - c[4] * y
            x = y
        }
        return x * gain
    }

    private fun peaking(frequency: Double, db: Double): DoubleArray {
        val a = 10.0.pow(db / 40.0)
        val w0 = 2 * PI * frequency / sampleRate
        val alpha = sin(w0) / (2 * Q)
        val a0 = 1 + alpha / a
        return doubleArrayOf(
            (1 + alpha * a) / a0,
            -2 * cos(w0) / a0,
            (1 - alpha * a) / a0,
            -2 * cos(w0) / a0,
            (1 - alpha / a) / a0,
        )
    }

    companion object {
        /** The bands' centres in Hz, as the web app's (EQ_BANDS). */
        val BANDS = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
        const val MAX_DB = 12f
        /** How wide each band is (an octave apart), as the web app's EQ_Q. */
        const val Q = 1.41

        /** The biggest boost turned the other way (dB, 0 or less), as the web app's eqHeadroom. */
        fun headroom(gains: List<Float>): Double = -maxOf(0f, gains.maxOrNull() ?: 0f).toDouble()
    }
}
