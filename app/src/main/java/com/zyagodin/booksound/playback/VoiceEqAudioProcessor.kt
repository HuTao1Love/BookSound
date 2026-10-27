package com.zyagodin.booksound.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.zyagodin.booksound.core.audio.VoiceEqualizer
import com.zyagodin.booksound.core.audio.VoicePreset
import com.zyagodin.booksound.core.audio.VolumeBoost
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Applies the voice equalizer ([VoiceEqualizer]) and the volume boost ([VolumeBoost]) to decoded
 * audio before it reaches the speed changer and the speaker. The same on every device (no
 * dependence on the phone's own effects), and [preset] and [boostDb] can be switched while playing.
 */
@OptIn(UnstableApi::class)
class VoiceEqAudioProcessor : BaseAudioProcessor() {

    /** Set from any thread; takes effect with the next audio buffer. */
    @Volatile
    var preset: VoicePreset = VoicePreset.OFF

    /** Set from any thread; takes effect with the next audio buffer. 0 is off. */
    @Volatile
    var boostDb: Int = 0

    private var equalizer: VoiceEqualizer? = null
    private var boost: VolumeBoost? = null

    override fun onConfigure(format: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (format.encoding != C.ENCODING_PCM_16BIT && format.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(format)
        }
        return format
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val output = replaceOutputBuffer(size)
        val format = inputAudioFormat
        val eq = equalizerFor(format)
        val louder = boostFor(format)
        if (eq == null && louder == null) {
            output.put(inputBuffer)
        } else {
            val channels = format.channelCount
            var i = 0
            fun process(x: Float): Float {
                val y = eq?.process(x, i++ % channels) ?: x
                return louder?.process(y) ?: y
            }
            if (format.encoding == C.ENCODING_PCM_16BIT) {
                while (inputBuffer.remaining() >= 2) {
                    val y = process(inputBuffer.getShort() / 32_768f)
                    output.putShort((y * 32_767f).roundToInt().coerceIn(-32_768, 32_767).toShort())
                }
            } else {
                while (inputBuffer.remaining() >= 4) {
                    output.putFloat(process(inputBuffer.getFloat()))
                }
            }
            // Whole frames always arrive, but never leave a stray byte unconsumed.
            inputBuffer.position(inputBuffer.limit())
        }
        output.flip()
    }

    /** The equalizer for the current preset and format; null passes audio through untouched. */
    private fun equalizerFor(format: AudioProcessor.AudioFormat): VoiceEqualizer? {
        val wanted = preset
        if (wanted == VoicePreset.OFF) {
            equalizer = null
            return null
        }
        val current = equalizer
        if (current != null && current.preset == wanted && current.sampleRate == format.sampleRate && current.channels == format.channelCount) {
            return current
        }
        return VoiceEqualizer(wanted, format.sampleRate, format.channelCount).also { equalizer = it }
    }

    /** The volume boost for the current level and format; null when it's off. */
    private fun boostFor(format: AudioProcessor.AudioFormat): VolumeBoost? {
        val wanted = boostDb
        if (wanted <= 0) {
            boost = null
            return null
        }
        val current = boost
        if (current != null && current.gainDb == wanted && current.sampleRate == format.sampleRate && current.channels == format.channelCount) {
            return current
        }
        return VolumeBoost(wanted, format.sampleRate, format.channelCount).also { boost = it }
    }

    override fun onReset() {
        equalizer = null
        boost = null
    }
}
