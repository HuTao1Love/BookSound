package com.zyagodin.booksound.util

import android.content.Context
import android.text.format.Formatter
import com.zyagodin.booksound.R
import kotlin.math.abs

/** "1:02:03" / "12:34" — for positions inside the player. */
fun formatClock(ms: Long): String {
    val totalSeconds = abs(ms) / 1000
    val h = totalSeconds / 3600
    val m = totalSeconds % 3600 / 60
    val s = totalSeconds % 60
    val sign = if (ms < 0) "-" else ""
    return if (h > 0) "%s%d:%02d:%02d".format(sign, h, m, s) else "%s%d:%02d".format(sign, m, s)
}

/** "12 h 5 min" / "45 min" — for durations in lists. */
fun formatDuration(context: Context, ms: Long): String {
    val totalMinutes = (ms + 30_000) / 60_000
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return when {
        h > 0 && m > 0 -> context.getString(R.string.duration_hours_minutes, h, m)
        h > 0 -> context.getString(R.string.duration_hours, h)
        else -> context.getString(R.string.duration_minutes, m.coerceAtLeast(if (ms > 0) 1 else 0))
    }
}

fun formatSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

fun formatSpeed(speed: Float): String {
    val rounded = (speed * 100).toInt() / 100f
    val text = if (rounded % 1f == 0f) "%.1f".format(rounded) else "%.2f".format(rounded).trimEnd('0')
    return "$text×"
}
