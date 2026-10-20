package com.zyagodin.booksound.core.organize

import com.zyagodin.booksound.core.metadata.ParsedAudioFile
import com.zyagodin.booksound.core.model.Chapter
import kotlin.math.abs

/** Checks every import runs on its converted and written audio, on the phone and on the server alike. */
object ImportChecks {

    /**
     * Decoders skip damaged frames instead of failing, which would silently shorten the book.
     * The converted audio must therefore last as long as the source files say they do.
     * Returns what is wrong, or null when the duration is fine.
     */
    fun decodedDurationProblem(actualMs: Long?, expectedMs: Long): String? {
        if (expectedMs <= 0) return null
        val tolerance = maxOf(10_000L, expectedMs * 3 / 100)
        return if (actualMs == null || actualMs <= 0 || abs(actualMs - expectedMs) > tolerance) {
            "decoded ${actualMs ?: 0} ms of $expectedMs ms"
        } else {
            null
        }
    }

    /** Stretches chapter starts so they match the real duration of the converted audio. */
    fun alignChapters(chapters: List<Chapter>, actualMs: Long, expectedMs: Long): List<Chapter> {
        if (chapters.isEmpty() || actualMs <= 0 || expectedMs <= 0) return chapters
        val ratio = actualMs.toDouble() / expectedMs
        return chapters.mapIndexed { i, c ->
            val start = (c.startMs * ratio).toLong()
            val end = if (i == chapters.lastIndex) actualMs else (chapters[i + 1].startMs * ratio).toLong()
            c.copy(startMs = start, endMs = end)
        }
    }

    /**
     * Compares a written book, parsed back, with what was meant to be written.
     * Returns what is wrong, or null when the file is the expected book.
     */
    fun writtenFileProblem(parsed: ParsedAudioFile, bookId: String, expectedDurationMs: Long, chapterCount: Int): String? {
        if (parsed.bookId != bookId) return "id mismatch"
        if (parsed.stream?.codec != "aac") return "codec ${parsed.stream?.codec}"
        val duration = parsed.durationMs ?: 0L
        val tolerance = maxOf(5_000L, expectedDurationMs / 50)
        if (duration <= 0 || abs(duration - expectedDurationMs) > tolerance) return "duration $duration, expected $expectedDurationMs"
        if (chapterCount > 0 && parsed.chapters.size != chapterCount) return "chapters ${parsed.chapters.size}/$chapterCount"
        return null
    }
}
