package com.zyagodin.booksound.cover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SquareCropTest {

    @Test
    fun `square detection tolerates a few pixels`() {
        assertTrue(SquareCrop.isSquare(1000, 1000))
        assertTrue(SquareCrop.isSquare(1000, 990))
        assertFalse(SquareCrop.isSquare(700, 1000))
    }

    @Test
    fun `untouched portrait crop is the centred square`() {
        // 600 x 900 image in a 300 px viewport: scale 0.5, the middle 600 x 600 is visible.
        assertEquals(SquareCrop.Region(0, 150, 600), SquareCrop.region(600, 900, 300f, 1f, 0f, 0f))
    }

    @Test
    fun `dragging moves the crop and stops at the edges`() {
        // Drag the image down by 75 screen px = 150 image px: the top of the image is visible.
        assertEquals(SquareCrop.Region(0, 0, 600), SquareCrop.region(600, 900, 300f, 1f, 0f, 75f))
        // Dragging further is clamped.
        assertEquals(0f to 75f, SquareCrop.clampOffset(600, 900, 300f, 1f, 40f, 500f))
        // Drag up: the bottom of the image.
        assertEquals(SquareCrop.Region(0, 300, 600), SquareCrop.region(600, 900, 300f, 1f, 0f, -1000f))
    }

    @Test
    fun `zooming in crops a smaller region`() {
        // Landscape 1200 x 800, zoom 2: visible square is 400 image px, centred.
        assertEquals(SquareCrop.Region(400, 200, 400), SquareCrop.region(1200, 800, 400f, 2f, 0f, 0f))
    }
}
