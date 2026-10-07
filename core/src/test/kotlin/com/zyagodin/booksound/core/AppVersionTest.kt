package com.zyagodin.booksound.core

import com.zyagodin.booksound.core.update.AppVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    private fun v(text: String) = AppVersion.parse(text)!!

    @Test
    fun `numeric order`() {
        assertTrue(v("1.0.100") > v("1.0.99"))
        assertTrue(v("1.1.0") > v("1.0.250"))
        assertTrue(v("1.0") < v("1.0.1"))
        assertEquals(v("1.0"), v("1.0.0"))
    }

    @Test
    fun `tags and suffixes`() {
        assertEquals(v("1.0.35"), v("v1.0.35"))
        assertEquals(v("1.0.35"), v("1.0.35-debug"))
    }

    @Test
    fun `not a version`() {
        assertNull(AppVersion.parse(null))
        assertNull(AppVersion.parse(""))
        assertNull(AppVersion.parse("latest"))
        assertNull(AppVersion.parse("1.x.2"))
    }
}
