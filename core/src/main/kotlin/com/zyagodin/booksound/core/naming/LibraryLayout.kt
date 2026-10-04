package com.zyagodin.booksound.core.naming

import com.zyagodin.booksound.core.model.BookMetadata

/** Relative location of a book inside the library root. */
data class LibraryPath(val directories: List<String>, val fileName: String) {
    val relativePath: String get() = (directories + fileName).joinToString("/")
}

/**
 * Deterministic library organisation:
 *
 * ```
 * <Author>/<Series>/<NN> - <Title>.m4b   (book in a series with a number)
 * <Author>/<Series>/<Title>.m4b          (series without a number)
 * <Author>/<Title>.m4b                   (standalone)
 * ```
 *
 * Missing metadata never produces an empty component: the author falls back to
 * [UNKNOWN_AUTHOR] and the title to the source name, then [UNTITLED].
 */
object LibraryLayout {
    const val UNKNOWN_AUTHOR = "Unknown Author"
    const val UNTITLED = "Untitled Audiobook"
    const val EXTENSION = "m4b"

    private const val DIR_MAX_BYTES = 120
    private const val FILE_STEM_MAX_BYTES = 180

    fun pathFor(metadata: BookMetadata, fallbackTitle: String? = null): LibraryPath {
        val author = FileNameSanitizer.sanitize(metadata.author, DIR_MAX_BYTES) ?: UNKNOWN_AUTHOR
        val series = FileNameSanitizer.sanitize(metadata.series, DIR_MAX_BYTES)
        val title = FileNameSanitizer.sanitize(metadata.title, FILE_STEM_MAX_BYTES)
            ?: FileNameSanitizer.sanitize(fallbackTitle, FILE_STEM_MAX_BYTES)
            ?: UNTITLED
        val number = series?.let { formatSeriesIndex(metadata.seriesIndex) }
        val stem = if (number != null) "$number - $title" else title
        val fileName = FileNameSanitizer.truncateToBytes(stem, FILE_STEM_MAX_BYTES).trimEnd('.', ' ') + ".$EXTENSION"
        return LibraryPath(listOfNotNull(author, series), fileName)
    }

    /** "3" -> "03", "3.5" -> "03.5", so that files sort naturally in any file manager. */
    fun formatSeriesIndex(index: String?): String? {
        val normalized = com.zyagodin.booksound.core.model.SeriesIndex.normalize(index) ?: return null
        val whole = normalized.substringBefore('.')
        val fraction = normalized.substringAfter('.', "")
        val padded = whole.padStart(2, '0')
        return if (fraction.isEmpty()) padded else "$padded.$fraction"
    }

    /**
     * Returns [desired] or "<stem> (2).<ext>", "(3)"... so that it does not collide (case-insensitively)
     * with [existing] names in the same directory.
     */
    fun uniqueFileName(desired: String, existing: Collection<String>): String {
        val taken = existing.mapTo(HashSet()) { FileNameSanitizer.collisionKey(it) }
        if (FileNameSanitizer.collisionKey(desired) !in taken) return desired
        val dot = desired.lastIndexOf('.')
        val stem = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        var n = 2
        while (true) {
            val candidate = "$stem ($n)$ext"
            if (FileNameSanitizer.collisionKey(candidate) !in taken) return candidate
            n++
        }
    }

    /** Prefix of temporary files written inside the library while an import is in progress. */
    const val PARTIAL_PREFIX = ".booksound-partial-"

    /** Prefix used when moving a replaced book aside until the replacement is committed. */
    const val REPLACED_PREFIX = ".booksound-replaced-"

    fun isTemporaryName(name: String): Boolean = name.startsWith(PARTIAL_PREFIX) || name.startsWith(REPLACED_PREFIX)
}
