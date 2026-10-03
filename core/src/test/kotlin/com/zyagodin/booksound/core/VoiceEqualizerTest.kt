package com.zyagodin.booksound.core

import com.zyagodin.booksound.core.audio.Biquad
import com.zyagodin.booksound.core.audio.FilterSpec
import com.zyagodin.booksound.core.audio.VoiceEqualizer
import com.zyagodin.booksound.core.audio.VoicePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class VoiceEqualizerTest {

    private val rate = 44_100

    @Test
    fun `cookbook filters have the expected response`() {
        val peak = Biquad.design(FilterSpec(FilterSpec.Type.PEAKING, 1_000.0, q = 1.0, gainDb = 6.0), rate)
        assertEquals(6.0, peak.magnitudeDb(1_000.0, rate), 0.01)
        assertEquals(0.0, peak.magnitudeDb(20.0, rate), 0.1)

        val highPass = Biquad.design(FilterSpec(FilterSpec.Type.HIGH_PASS, 100.0), rate)
        assertEquals(-3.01, highPass.magnitudeDb(100.0, rate), 0.05)
        assertEquals(0.0, highPass.magnitudeDb(5_000.0, rate), 0.01)
        assertTrue(highPass.magnitudeDb(25.0, rate) < -20)

        val lowShelf = Biquad.design(FilterSpec(FilterSpec.Type.LOW_SHELF, 200.0, gainDb = -4.0), rate)
        assertEquals(-4.0, lowShelf.magnitudeDb(10.0, rate), 0.05)
        assertEquals(0.0, lowShelf.magnitudeDb(10_000.0, rate), 0.05)

        val highShelf = Biquad.design(FilterSpec(FilterSpec.Type.HIGH_SHELF, 5_000.0, gainDb = 3.0), rate)
        assertEquals(3.0, highShelf.magnitudeDb(20_000.0, rate), 0.2)
        assertEquals(0.0, highShelf.magnitudeDb(50.0, rate), 0.05)
    }

    @Test
    fun `presets shape the voice as described`() {
        fun eq(p: VoicePreset) = VoiceEqualizer(p, rate, 2)

        val off = eq(VoicePreset.OFF)
        assertTrue(off.isBypass)
        assertEquals(0.0, off.magnitudeDb(1_000.0), 1e-9)

        val noBass = eq(VoicePreset.NO_BASS)
        assertTrue(noBass.magnitudeDb(60.0) < -25)
        assertEquals(0.0, noBass.magnitudeDb(2_000.0), 0.5)

        val old = eq(VoicePreset.OLD_RECORDING)
        assertTrue(old.magnitudeDb(50.0) < -20) // mains hum
        assertTrue(old.magnitudeDb(14_000.0) < -20) // tape hiss
        assertTrue(old.magnitudeDb(1_800.0) > old.magnitudeDb(14_000.0) + 20)

        val deep = eq(VoicePreset.DEEP_MALE)
        assertTrue("presence above boom", deep.magnitudeDb(3_000.0) > deep.magnitudeDb(180.0) + 6)

        val bright = eq(VoicePreset.BRIGHT_FEMALE)
        assertTrue("sibilance tamed", bright.magnitudeDb(7_000.0) < bright.magnitudeDb(1_000.0) - 3)

        val clarity = eq(VoicePreset.CLARITY)
        assertTrue(clarity.magnitudeDb(2_500.0) > clarity.magnitudeDb(150.0) + 5)
    }

    @Test
    fun `processing matches the designed response and never clips`() {
        val eq = VoiceEqualizer(VoicePreset.NO_BASS, rate, 1)
        assertEquals(rms(eq, 2_000.0, 0.5) / 0.5 * sqrt(2.0), 1.0, 0.05)
        eq.reset()
        assertTrue(rms(eq, 50.0, 0.5) < 0.5 / sqrt(2.0) * 0.05)

        // A full-scale tone in the boosted band stays inside -1..1.
        val loud = VoiceEqualizer(VoicePreset.CLARITY, rate, 1)
        val samples = FloatArray(rate) { (0.99 * sin(2 * PI * 2_500.0 * it / rate)).toFloat() }
        loud.process(samples)
        assertTrue(samples.all { abs(it) <= 1f })
    }

    @Test
    fun `channels are filtered independently`() {
        val eq = VoiceEqualizer(VoicePreset.DEEP_MALE, rate, 2)
        val n = 4_000
        val interleaved = FloatArray(n * 2) { if (it % 2 == 0) (0.3 * sin(2 * PI * 440.0 * (it / 2) / rate)).toFloat() else 0f }
        eq.process(interleaved)
        assertTrue((0 until n).all { interleaved[it * 2 + 1] == 0f })
        assertTrue((0 until n).any { interleaved[it * 2] != 0f })
    }

    /** RMS of a sine of [frequency] after filtering, ignoring the first 100 ms of settling. */
    private fun rms(eq: VoiceEqualizer, frequency: Double, amplitude: Double): Double {
        val skip = rate / 10
        var sum = 0.0
        var count = 0
        for (i in 0 until rate) {
            val y = eq.process((amplitude * sin(2 * PI * frequency * i / rate)).toFloat(), 0)
            if (i >= skip) {
                sum += y * y
                count++
            }
        }
        return sqrt(sum / count)
    }
}
