package com.zyagodin.booksound.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanServerTest {

    @Test
    fun `ranges a Cast device asks for`() {
        assertEquals(0L to 999L, LanServer.parseRange("bytes=0-", 1000))
        assertEquals(100L to 199L, LanServer.parseRange("bytes=100-199", 1000))
        assertEquals(500L to 999L, LanServer.parseRange("bytes=500-5000", 1000))
        assertEquals(900L to 999L, LanServer.parseRange("bytes=-100", 1000))
        assertEquals(0L to 999L, LanServer.parseRange("bytes=-5000", 1000))
    }

    @Test
    fun `unsatisfiable ranges`() {
        assertNull(LanServer.parseRange("bytes=1000-", 1000))
        assertNull(LanServer.parseRange("bytes=200-100", 1000))
        assertNull(LanServer.parseRange("bytes=abc", 1000))
        assertNull(LanServer.parseRange("bytes=0-", 0))
    }
}
