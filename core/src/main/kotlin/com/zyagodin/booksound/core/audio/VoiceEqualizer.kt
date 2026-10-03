package com.zyagodin.booksound.core.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Equalizer presets for the narrator's voice rather than for music genres. Each one corrects a
 * typical recording: a booming low voice, a sharp high voice, a muddy room, an old noisy tape.
 */
enum class VoicePreset {
    OFF,

    /** Deep male voice: less boom and chest resonance, more presence so words stay crisp. */
    DEEP_MALE,

    /** Bright female voice: softer sibilance and harshness, a little warmth underneath. */
    BRIGHT_FEMALE,

    /** Removes the low end entirely: rumble, hum, proximity boom, bass-heavy speakers. */
    NO_BASS,

    /** Old or noisy recording: band-limited to the speech range to drop hum and hiss. */
    OLD_RECORDING,

    /** Quiet or mumbled narration: lifts the frequencies that carry consonants. */
    CLARITY,
}

/** One filter stage. [gainDb] is used by peaking and shelf filters only. */
data class FilterSpec(val type: Type, val frequencyHz: Double, val q: Double = BUTTERWORTH_Q, val gainDb: Double = 0.0) {
    enum class Type { HIGH_PASS, LOW_PASS, PEAKING, LOW_SHELF, HIGH_SHELF }

    companion object {
        const val BUTTERWORTH_Q = 0.7071067811865476
    }
}

object VoicePresets {
    fun filters(preset: VoicePreset): List<FilterSpec> = when (preset) {
        VoicePreset.OFF -> emptyList()
        VoicePreset.DEEP_MALE -> listOf(
            FilterSpec(FilterSpec.Type.HIGH_PASS, 70.0),
            FilterSpec(FilterSpec.Type.PEAKING, 180.0, q = 1.0, gainDb = -4.0),
            FilterSpec(FilterSpec.Type.PEAKING, 400.0, q = 1.2, gainDb = -2.0),
            FilterSpec(FilterSpec.Type.PEAKING, 3_000.0, q = 1.0, gainDb = 3.5),
            FilterSpec(FilterSpec.Type.HIGH_SHELF, 8_000.0, gainDb = 1.5),
        )
        VoicePreset.BRIGHT_FEMALE -> listOf(
            FilterSpec(FilterSpec.Type.HIGH_PASS, 90.0),
            FilterSpec(FilterSpec.Type.LOW_SHELF, 250.0, gainDb = 2.0),
            FilterSpec(FilterSpec.Type.PEAKING, 3_500.0, q = 1.4, gainDb = -3.0),
            FilterSpec(FilterSpec.Type.PEAKING, 7_000.0, q = 2.0, gainDb = -4.0),
            FilterSpec(FilterSpec.Type.HIGH_SHELF, 10_000.0, gainDb = -2.0),
        )
        VoicePreset.NO_BASS -> listOf(
            // Two stages make a steep 24 dB/octave cut.
            FilterSpec(FilterSpec.Type.HIGH_PASS, 160.0),
            FilterSpec(FilterSpec.Type.HIGH_PASS, 160.0),
        )
        VoicePreset.OLD_RECORDING -> listOf(
            FilterSpec(FilterSpec.Type.HIGH_PASS, 110.0),
            FilterSpec(FilterSpec.Type.HIGH_PASS, 110.0),
            FilterSpec(FilterSpec.Type.PEAKING, 1_800.0, q = 0.9, gainDb = 3.0),
            FilterSpec(FilterSpec.Type.LOW_PASS, 6_000.0),
            FilterSpec(FilterSpec.Type.LOW_PASS, 6_000.0),
        )
        VoicePreset.CLARITY -> listOf(
            FilterSpec(FilterSpec.Type.HIGH_PASS, 80.0),
            FilterSpec(FilterSpec.Type.LOW_SHELF, 200.0, gainDb = -2.5),
            FilterSpec(FilterSpec.Type.PEAKING, 2_500.0, q = 0.8, gainDb = 5.0),
            FilterSpec(FilterSpec.Type.HIGH_SHELF, 6_000.0, gainDb = 2.0),
        )
    }

    /**
     * Gain applied before filtering so the strongest boost of [preset] can't clip; half of the
     * boost is taken back, the rest is left to the limiter in [VoiceEqualizer].
     */
    fun preGainDb(preset: VoicePreset): Double {
        val boost = filters(preset).maxOfOrNull { it.gainDb } ?: 0.0
        return if (boost > 0) -boost / 2 else 0.0
    }
}

/** A second-order IIR filter (RBJ "Audio EQ Cookbook"), normalized so a0 = 1. */
class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {

    /** Magnitude response in dB at [frequencyHz]. */
    fun magnitudeDb(frequencyHz: Double, sampleRate: Int): Double {
        val w = 2 * PI * frequencyHz / sampleRate
        val cos1 = cos(w)
        val sin1 = sin(w)
        val cos2 = cos(2 * w)
        val sin2 = sin(2 * w)
        val numRe = b0 + b1 * cos1 + b2 * cos2
        val numIm = -(b1 * sin1 + b2 * sin2)
        val denRe = 1 + a1 * cos1 + a2 * cos2
        val denIm = -(a1 * sin1 + a2 * sin2)
        val num = numRe * numRe + numIm * numIm
        val den = denRe * denRe + denIm * denIm
        return 10 * log10(num / den)
    }

    companion object {
        fun design(spec: FilterSpec, sampleRate: Int): Biquad {
            // Keep the corner safely below Nyquist for low sample rates (e.g. 8 kHz recordings).
            val f0 = spec.frequencyHz.coerceAtMost(sampleRate * 0.45)
            val w0 = 2 * PI * f0 / sampleRate
            val cosW = cos(w0)
            val sinW = sin(w0)
            val a = 10.0.pow(spec.gainDb / 40)
            val alpha = sinW / (2 * spec.q)
            val b0: Double
            val b1: Double
            val b2: Double
            val a0: Double
            val a1: Double
            val a2: Double
            when (spec.type) {
                FilterSpec.Type.HIGH_PASS -> {
                    b0 = (1 + cosW) / 2; b1 = -(1 + cosW); b2 = (1 + cosW) / 2
                    a0 = 1 + alpha; a1 = -2 * cosW; a2 = 1 - alpha
                }
                FilterSpec.Type.LOW_PASS -> {
                    b0 = (1 - cosW) / 2; b1 = 1 - cosW; b2 = (1 - cosW) / 2
                    a0 = 1 + alpha; a1 = -2 * cosW; a2 = 1 - alpha
                }
                FilterSpec.Type.PEAKING -> {
                    b0 = 1 + alpha * a; b1 = -2 * cosW; b2 = 1 - alpha * a
                    a0 = 1 + alpha / a; a1 = -2 * cosW; a2 = 1 - alpha / a
                }
                FilterSpec.Type.LOW_SHELF -> {
                    val k = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) - (a - 1) * cosW + k)
                    b1 = 2 * a * ((a - 1) - (a + 1) * cosW)
                    b2 = a * ((a + 1) - (a - 1) * cosW - k)
                    a0 = (a + 1) + (a - 1) * cosW + k
                    a1 = -2 * ((a - 1) + (a + 1) * cosW)
                    a2 = (a + 1) + (a - 1) * cosW - k
                }
                FilterSpec.Type.HIGH_SHELF -> {
                    val k = 2 * sqrt(a) * alpha
                    b0 = a * ((a + 1) + (a - 1) * cosW + k)
                    b1 = -2 * a * ((a - 1) + (a + 1) * cosW)
                    b2 = a * ((a + 1) + (a - 1) * cosW - k)
                    a0 = (a + 1) - (a - 1) * cosW + k
                    a1 = 2 * ((a - 1) - (a + 1) * cosW)
                    a2 = (a + 1) - (a - 1) * cosW - k
                }
            }
            return Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
    }
}

/**
 * Applies a [VoicePreset] to interleaved audio, keeping separate filter state per channel.
 * Samples are floats in -1..1; the output is soft-limited so boosts never wrap around.
 * Not thread-safe: use one instance per audio stream.
 */
class VoiceEqualizer(val preset: VoicePreset, val sampleRate: Int, val channels: Int) {
    private val stages: Array<Biquad> = VoicePresets.filters(preset).map { Biquad.design(it, sampleRate) }.toTypedArray()
    private val preGain = 10.0.pow(VoicePresets.preGainDb(preset) / 20)

    // Transposed direct form II state: two values per stage per channel.
    private val z1 = Array(channels) { DoubleArray(stages.size) }
    private val z2 = Array(channels) { DoubleArray(stages.size) }

    val isBypass: Boolean get() = stages.isEmpty()

    /** Combined magnitude response of all stages including the pre-gain, in dB. */
    fun magnitudeDb(frequencyHz: Double): Double =
        20 * log10(preGain) + stages.sumOf { it.magnitudeDb(frequencyHz, sampleRate) }

    /** Filters one sample of [channel]. */
    fun process(sample: Float, channel: Int): Float {
        if (stages.isEmpty()) return sample
        var x = sample * preGain
        val s1 = z1[channel]
        val s2 = z2[channel]
        for (i in stages.indices) {
            val f = stages[i]
            val y = f.b0 * x + s1[i]
            s1[i] = f.b1 * x - f.a1 * y + s2[i]
            s2[i] = f.b2 * x - f.a2 * y
            x = y
        }
        return limit(x).toFloat()
    }

    /** Filters interleaved samples in place. */
    fun process(samples: FloatArray, count: Int = samples.size) {
        if (stages.isEmpty()) return
        for (i in 0 until count) samples[i] = process(samples[i], i % channels)
    }

    /** Clears the filter memory, e.g. after a seek, so old audio doesn't ring into new. */
    fun reset() {
        for (c in 0 until channels) {
            z1[c].fill(0.0)
            z2[c].fill(0.0)
        }
    }

    /** Linear up to [KNEE], then a smooth curve that approaches but never exceeds 1. */
    private fun limit(x: Double): Double {
        val ax = abs(x)
        if (ax <= KNEE) return x
        val over = ax - KNEE
        val soft = KNEE + (1 - KNEE) * (over / (over + (1 - KNEE)))
        return if (x < 0) -soft else soft
    }

    private companion object {
        const val KNEE = 0.9
    }
}
