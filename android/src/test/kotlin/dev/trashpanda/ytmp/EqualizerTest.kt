package dev.trashpanda.ytmp

import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EqualizerTest {
    private val rate = 48_000

    private fun settings(vararg bands: Pair<Int, Float>) =
        EqSettings(on = true, gains = List(Equalizer.BANDS.size) { i -> bands.firstOrNull { it.first == i }?.second ?: 0f })

    /** How much louder (dB) a sine at [hz] comes out, after the filters have settled. */
    private fun responseDb(settings: EqSettings, hz: Double): Double {
        val eq = Equalizer(rate, 2).apply { use(settings) }
        var input = 0.0
        var output = 0.0
        for (n in 0 until rate) {
            val x = 0.25 * sin(2 * PI * hz * n / rate)
            // Both channels, as interleaved stereo.
            val y = eq.process(x, 0).also { eq.process(x, 1) }
            if (n >= rate / 2) {
                input += x * x
                output += y * y
            }
        }
        return 20 * log10(sqrt(output / input))
    }

    @Test
    fun `flat leaves the sound as it is`() {
        val eq = Equalizer(rate, 1).apply { use(EqSettings.OFF.copy(on = true)) }
        for (x in listOf(0.0, 0.5, -0.99, 0.123)) assertEquals(x, eq.process(x, 0))
    }

    @Test
    fun `a boosted band is louder there, and the rest is turned down so it doesn't clip`() {
        val boost = settings(5 to 12f) // 1 kHz
        assertEquals(0.0, responseDb(boost, 1000.0), 0.3)
        assertEquals(-12.0, responseDb(boost, 8000.0), 0.5)
        assertEquals(-12.0, responseDb(boost, 60.0), 0.5)
    }

    @Test
    fun `a lowered band is quieter there, the rest stays`() {
        val cut = settings(1 to -12f) // 62 Hz
        assertEquals(-12.0, responseDb(cut, 62.0), 0.3)
        assertEquals(0.0, responseDb(cut, 2000.0), 0.3)
    }

    @Test
    fun `bands above what the sample rate can carry are left out`() {
        val eq = Equalizer(22_050, 1).apply { use(settings(9 to -12f)) }
        // 16 kHz doesn't exist at 22 kHz: nothing changes.
        assertEquals(0.3, eq.process(0.3, 0))
    }

    @Test
    fun `the player's processor lets the sound by when off, and equalizes it when on`() {
        var setting = EqSettings.OFF
        val processor = EqualizerProcessor { setting }
        val stereo = AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT)
        assertEquals(stereo, processor.configure(stereo))
        processor.flush(AudioProcessor.StreamMetadata.DEFAULT)
        // 20 ms of 1 kHz, both channels.
        val samples = ShortArray(1920) { (8000 * sin(2 * PI * 1000 * (it / 2) / rate)).toInt().toShort() }
        fun play(): ShortArray {
            val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
            samples.forEach { input.putShort(it) }
            input.flip()
            processor.queueInput(input)
            val output = processor.output
            return ShortArray(output.remaining() / 2) { output.getShort() }
        }
        assertContentEquals(samples, play())
        setting = settings(5 to -12f)
        // A quarter as loud once the filter has settled (8000 → about 2000).
        assertTrue(play().drop(960).maxOf { abs(it.toInt()) } in 1500..2500)
        // Other kinds of sound pass by.
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_24BIT)))
    }

    @Test
    fun `odd settings are off`() {
        assertEquals(EqSettings.OFF, EqSettings(on = true, gains = listOf(1f, 2f)).valid())
        assertEquals(12f, settings(0 to 40f).valid().gains[0])
    }

    @Test
    fun `each Bluetooth device is an output of its own, the speaker and wired headphones one each`() {
        assertEquals(AudioOutput("bluetooth:WH-1000XM4", "WH-1000XM4"), AudioOutput.of(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "WH-1000XM4"))
        assertEquals(AudioOutput.SPEAKER, AudioOutput.of(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Pixel 8"))
        assertEquals("wired", AudioOutput.of(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "").id)
        assertEquals("wired", AudioOutput.of(AudioDeviceInfo.TYPE_WIRED_HEADSET, "Headset").id)
    }

    @Test
    fun `without Android saying, Bluetooth goes before wired before the speaker`() {
        val speaker = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to "Pixel 8"
        val wired = AudioDeviceInfo.TYPE_WIRED_HEADSET to ""
        val bluetooth = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to "Car"
        val call = AudioDeviceInfo.TYPE_BLUETOOTH_SCO to "Car"
        assertEquals(AudioOutput.SPEAKER, AudioOutput.likelyFor(listOf(speaker)))
        assertEquals("wired", AudioOutput.likelyFor(listOf(speaker, wired)).id)
        assertEquals("bluetooth:Car", AudioOutput.likelyFor(listOf(speaker, call, wired, bluetooth)).id)
    }
}
