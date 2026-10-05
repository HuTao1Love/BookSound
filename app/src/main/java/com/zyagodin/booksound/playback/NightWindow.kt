package com.zyagodin.booksound.playback

import java.time.ZonedDateTime

/**
 * Night hours for the automatic sleep timer: from [startMinute] (inclusive) to [endMinute]
 * (exclusive), as minutes since midnight. The night may span midnight (22:00–07:00); equal ends
 * mean no night at all.
 */
data class NightWindow(val startMinute: Int, val endMinute: Int) {

    fun contains(minuteOfDay: Int): Boolean = when {
        startMinute == endMinute -> false
        startMinute < endMinute -> minuteOfDay in startMinute until endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }

    fun contains(time: ZonedDateTime): Boolean = contains(time.hour * 60 + time.minute)

    /** The first end of the night after [time]. */
    fun endAfter(time: ZonedDateTime): ZonedDateTime {
        val end = time.toLocalDate().atTime(endMinute / 60, endMinute % 60).atZone(time.zone)
        return if (end.isAfter(time)) end else end.plusDays(1)
    }
}
