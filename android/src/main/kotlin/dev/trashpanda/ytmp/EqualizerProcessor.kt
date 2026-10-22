package dev.trashpanda.ytmp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * The equalizer in ExoPlayer's audio output: the decoded sound goes through an [Equalizer] with
 * the setting [settings] gives (read for every piece, so a change is heard at once, also when
 * other headphones are connected). Switched off, the sound passes as it is.
 */
@OptIn(UnstableApi::class)
class EqualizerProcessor(private val settings: () -> EqSettings) : BaseAudioProcessor() {
    private var equalizer: Equalizer? = null

    // Decoders give 16-bit sound, or float when asked; anything else passes by.
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT || inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) inputAudioFormat
        else AudioFormat.NOT_SET

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // A new song or a seek: the filters start quiet.
        equalizer = Equalizer(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
    }

    override fun onReset() {
        equalizer = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val setting = settings()
        val equalizer = equalizer
        if (equalizer == null || !setting.on) {
            output.put(inputBuffer)
        } else {
            equalizer.use(setting)
            val channels = inputAudioFormat.channelCount
            var channel = 0
            if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
                while (inputBuffer.hasRemaining()) {
                    output.putFloat(equalizer.process(inputBuffer.getFloat().toDouble(), channel).toFloat())
                    channel = (channel + 1) % channels
                }
            } else {
                while (inputBuffer.hasRemaining()) {
                    val y = equalizer.process(inputBuffer.getShort() / 32768.0, channel)
                    output.putShort((y * 32768.0).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
                    channel = (channel + 1) % channels
                }
            }
        }
        output.flip()
    }
}
