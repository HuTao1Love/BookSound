package com.zyagodin.booksound.core.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Makes a quiet recording louder: raises everything by [gainDb] and turns the gain down just enough
 * where the louder result would go past full scale, so loud passages are held back instead of
 * clipping. The gain comes back smoothly after them (no pumping on every syllable, no distortion).
 *
 * Samples are interleaved floats in -1..1; all channels share one gain so the stereo image stays put.
 * Not thread-safe: use one instance per audio stream.
 */
class VolumeBoost(val gainDb: Int, val sampleRate: Int, val channels: Int) {
    private val gain = 10.0.pow(gainDb / 20.0)

    /** Per-sample decay of the peak envelope: back to the full gain over [RELEASE_SECONDS]. */
    private val release = exp(-1.0 / (RELEASE_SECONDS * sampleRate * channels))
    private var envelope = 0.0

    val isBypass: Boolean get() = gainDb <= 0

    /** Boosts one sample (of any channel). */
    fun process(sample: Float): Float {
        if (isBypass) return sample
        val x = sample * gain
        // Instant attack: the envelope is never below the sample, so the output never exceeds the ceiling.
        envelope = maxOf(abs(x), envelope * release)
        val y = if (envelope > CEILING) x * (CEILING / envelope) else x
        return y.toFloat()
    }

    /** Boosts interleaved samples in place. */
    fun process(samples: FloatArray, count: Int = samples.size) {
        if (isBypass) return
        for (i in 0 until count) samples[i] = process(samples[i])
    }

    /** Forgets the loudness so far, e.g. after a seek. */
    fun reset() {
        envelope = 0.0
    }

    companion object {
        /** Choices offered to the user, in dB; 0 is off. */
        val LEVELS = listOf(0, 3, 6, 9, 12)

        /** Just under full scale, so later rounding to 16 bits can't wrap around. */
        private const val CEILING = 0.97
        private const val RELEASE_SECONDS = 0.25
    }
}
