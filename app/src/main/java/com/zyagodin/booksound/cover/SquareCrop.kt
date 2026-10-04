package com.zyagodin.booksound.cover

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Geometry of the square cover crop: the image is shown filling a square viewport (it covers the
 * whole square at zoom 1), can be zoomed in and moved, and never leaves an empty edge.
 */
object SquareCrop {
    const val MAX_ZOOM = 5f

    /** Region of the source image, in image pixels. */
    data class Region(val left: Int, val top: Int, val size: Int)

    /** True when the image is already (almost) square and needs no crop. */
    fun isSquare(width: Int, height: Int): Boolean = abs(width - height) <= max(width, height) * 0.02

    /** Screen pixels per image pixel. */
    fun scale(width: Int, height: Int, viewport: Float, zoom: Float): Float = viewport / min(width, height) * zoom

    /** Limits the image offset (from centered) so the image always covers the viewport. */
    fun clampOffset(width: Int, height: Int, viewport: Float, zoom: Float, x: Float, y: Float): Pair<Float, Float> {
        val s = scale(width, height, viewport, zoom)
        val maxX = max(0f, (width * s - viewport) / 2)
        val maxY = max(0f, (height * s - viewport) / 2)
        return x.coerceIn(-maxX, maxX) to y.coerceIn(-maxY, maxY)
    }

    /** The part of the image visible in the viewport. */
    fun region(width: Int, height: Int, viewport: Float, zoom: Float, x: Float, y: Float): Region {
        val s = scale(width, height, viewport, zoom)
        val (cx, cy) = clampOffset(width, height, viewport, zoom, x, y)
        val left = (viewport - width * s) / 2 + cx
        val top = (viewport - height * s) / 2 + cy
        val size = (viewport / s).roundToInt().coerceIn(1, min(width, height))
        val srcLeft = (-left / s).roundToInt().coerceIn(0, width - size)
        val srcTop = (-top / s).roundToInt().coerceIn(0, height - size)
        return Region(srcLeft, srcTop, size)
    }
}
