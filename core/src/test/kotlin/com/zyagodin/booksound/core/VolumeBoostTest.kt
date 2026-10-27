package com.zyagodin.booksound.core

import com.zyagodin.booksound.core.audio.VolumeBoost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class VolumeBoostTest {

    private val rate = 44_100

    private fun tone(amplitude: Double, seconds: Double, channels: Int = 1) =
        FloatArray((rate * seconds).toInt() * channels) { i -> (amplitude * sin(2 * PI * 440 * (i / channels) / rate)).toFloat() }

    @Test
    fun `quiet audio gets the full gain`() {
        val boost = VolumeBoost(6, rate, 1)
        val samples = tone(0.1, 0.5)
        val original = samples.copyOf()
        boost.process(samples)
        for (i in samples.indices) assertEquals(original[i] * 1.9953f, samples[i], 1e-4f)
    }

    @Test
    fun `loud audio never goes past full scale`() {
        val boost = VolumeBoost(12, rate, 2)
        val samples = tone(0.9, 1.0, channels = 2)
        boost.process(samples)
        assertTrue(samples.all { abs(it) <= 0.97f })
        // Still louder than it was, just held under the ceiling.
        assertTrue(samples.maxOf { abs(it) } > 0.9f)
    }

    @Test
    fun `the gain comes back after a loud passage`() {
        val boost = VolumeBoost(6, rate, 1)
        boost.process(tone(0.9, 0.2))
        val quiet = tone(0.1, 2.0)
        boost.process(quiet)
        // Right after the loud part the gain is still held back, two seconds later it's whole again.
        val start = quiet.take(rate / 20).maxOf { abs(it) }
        val end = quiet.takeLast(rate / 20).maxOf { abs(it) }
        assertTrue(start < 0.19f)
        assertEquals(0.1995f, end, 0.002f)
    }

    @Test
    fun `off passes audio through`() {
        val boost = VolumeBoost(0, rate, 1)
        val samples = tone(1.0, 0.1)
        val original = samples.copyOf()
        boost.process(samples)
        assertTrue(boost.isBypass)
        assertTrue(samples.contentEquals(original))
    }
}
