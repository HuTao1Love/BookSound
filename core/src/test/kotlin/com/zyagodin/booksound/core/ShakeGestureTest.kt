package com.zyagodin.booksound.core

import com.zyagodin.booksound.core.playback.ShakeGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeGestureTest {

    /** Feeds readings every 20 ms; strong ones at the given times. Returns the times of detected shakes. */
    private fun shakes(gesture: ShakeGesture, untilMs: Long, strongAt: Set<Long>, strength: Float = 3f): List<Long> {
        val detected = mutableListOf<Long>()
        var t = 0L
        while (t <= untilMs) {
            if (gesture.onSample(t, if (t in strongAt) strength else 1f)) detected += t
            t += 20
        }
        return detected
    }

    @Test
    fun `three jolts in a second are a shake`() {
        assertEquals(listOf(600L), shakes(ShakeGesture(), 2_000, setOf(200, 400, 600)))
    }

    @Test
    fun `a single bump or slow movements are not`() {
        assertTrue(shakes(ShakeGesture(), 2_000, setOf(500)).isEmpty())
        assertTrue(shakes(ShakeGesture(), 6_000, setOf(0, 1_500, 3_000, 4_500)).isEmpty())
        assertTrue(shakes(ShakeGesture(), 2_000, setOf(200, 400, 600), strength = 1.8f).isEmpty())
    }

    @Test
    fun `one jolt spread over several readings counts once`() {
        assertTrue(shakes(ShakeGesture(), 2_000, setOf(200, 220, 240)).isEmpty())
    }

    @Test
    fun `a long shake triggers once, then again after the cooldown`() {
        val strong = (0L..5_000L step 200).toSet()
        val detected = shakes(ShakeGesture(), 5_000, strong)
        assertEquals(listOf(400L, 2_800L), detected.take(2))
        assertFalse(detected.zipWithNext().any { (a, b) -> b - a < 2_000 })
    }
}
