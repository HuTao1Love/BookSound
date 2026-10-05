package com.zyagodin.booksound.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class NightWindowTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone)

    @Test
    fun `night across midnight`() {
        val night = NightWindow(22 * 60, 7 * 60)
        assertTrue(night.contains(at(5, 22)))
        assertTrue(night.contains(at(5, 23, 59)))
        assertTrue(night.contains(at(6, 0)))
        assertTrue(night.contains(at(6, 6, 59)))
        assertFalse(night.contains(at(6, 7)))
        assertFalse(night.contains(at(5, 21, 59)))
        assertFalse(night.contains(at(5, 12)))
    }

    @Test
    fun `night within one day`() {
        val night = NightWindow(60, 6 * 60)
        assertTrue(night.contains(at(5, 1)))
        assertTrue(night.contains(at(5, 5, 59)))
        assertFalse(night.contains(at(5, 6)))
        assertFalse(night.contains(at(5, 0, 59)))
        assertFalse(night.contains(at(5, 23)))
    }

    @Test
    fun `equal ends mean no night`() {
        val night = NightWindow(7 * 60, 7 * 60)
        assertFalse(night.contains(at(5, 7)))
        assertFalse(night.contains(at(5, 3)))
    }

    @Test
    fun `end of the night is the next end time`() {
        val night = NightWindow(22 * 60, 7 * 60)
        assertEquals(at(6, 7), night.endAfter(at(5, 23)))
        assertEquals(at(6, 7), night.endAfter(at(6, 2)))
        assertEquals(at(7, 7), night.endAfter(at(6, 7)))
    }
}
