package com.zyagodin.booksound.core.library

import com.zyagodin.booksound.core.metadata.ParsedAudioFile
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.core.model.ChapterMark
import com.zyagodin.booksound.core.naming.LibraryLayout
import com.zyagodin.booksound.core.organize.ChapterPlanner
import com.zyagodin.booksound.core.organize.DraftPart
import com.zyagodin.booksound.core.organize.NamePatternParser

/** Details of a book file found in the library folder that no import wrote (copied there by hand). */
object AdoptedFile {
    val EXTENSIONS = setOf("m4b", "m4a")

    /** From the tags, else the file name, else the folders it is in (`Author/Series/...`). */
    fun metadata(parsed: ParsedAudioFile, fileName: String, folders: List<String>): BookMetadata {
        val tags = parsed.tags
        val guess = NamePatternParser.parse(fileName)
        return BookMetadata(
            title = tags.title ?: tags.album ?: guess.title ?: fileName.substringBeforeLast('.'),
            author = tags.artist ?: tags.albumArtist ?: guess.author ?: folders.firstOrNull()?.takeIf { it != LibraryLayout.UNKNOWN_AUTHOR },
            narrator = tags.narrator ?: tags.composer,
            series = tags.series ?: if (folders.size >= 2) folders[1] else null,
            seriesIndex = tags.seriesPart ?: guess.seriesIndex,
            year = tags.year,
            genre = tags.genre,
            description = tags.description ?: tags.comment,
            language = tags.language,
        ).normalized()
    }

    /** The file's own chapters, or one chapter spanning the whole book. */
    fun chapters(parsed: ParsedAudioFile, sourceId: String, fileName: String, title: String): List<Chapter> {
        val duration = parsed.durationMs ?: 0L
        val marks = parsed.chapters.ifEmpty { listOf(ChapterMark(0, title)) }
        return ChapterPlanner.plan(listOf(DraftPart(sourceId, fileName, title, duration, marks)))
    }
}
