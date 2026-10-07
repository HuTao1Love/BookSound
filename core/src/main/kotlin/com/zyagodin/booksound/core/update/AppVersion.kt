package com.zyagodin.booksound.core.update

/**
 * A release version as CI names it: `<appVersion>.<run number>`, e.g. 1.0.35. Compared part by
 * part as numbers, so 1.0.100 is newer than 1.0.99; a missing part counts as 0.
 */
class AppVersion private constructor(private val parts: List<Int>, private val text: String) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val c = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is AppVersion && compareTo(other) == 0
    override fun hashCode(): Int = parts.dropLastWhile { it == 0 }.hashCode()
    override fun toString(): String = text

    companion object {
        /** "1.0.35", "v1.0.35" or "1.0.35-debug"; null when there is no number to compare. */
        fun parse(text: String?): AppVersion? {
            val clean = text?.trim()?.removePrefix("v")?.substringBefore('-')?.takeIf { it.isNotEmpty() } ?: return null
            val parts = clean.split('.').map { it.toIntOrNull() ?: return null }
            return AppVersion(parts, clean)
        }
    }
}
