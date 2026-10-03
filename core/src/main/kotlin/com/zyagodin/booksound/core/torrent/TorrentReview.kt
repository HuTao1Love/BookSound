package com.zyagodin.booksound.core.torrent

import com.zyagodin.booksound.core.metadata.AudioContainer
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.organize.DraftPart
import com.zyagodin.booksound.core.organize.ImportDraftBuilder
import com.zyagodin.booksound.core.organize.ImportSourceFile
import com.zyagodin.booksound.core.organize.NamePatternParser

/** One audio file of a torrent as a part of the book, before it is downloaded. */
data class TorrentPart(
    val fileIndex: Int,
    /** Path inside the torrent. */
    val path: String,
    val title: String,
    val sizeBytes: Long,
)

/** Initial suggestions shown to the user right after a torrent was added. */
data class TorrentSuggestion(val metadata: BookMetadata, val parts: List<TorrentPart>)

/**
 * What the user confirmed in the editor while the torrent was still downloading. [suggested] is
 * what the editor initially proposed; a field the user left as suggested may later be replaced by
 * better information from the downloaded files' tags, a field the user changed never is.
 */
data class TorrentReview(
    val suggested: BookMetadata,
    val edited: BookMetadata,
    val parts: List<ReviewedPart>,
)

data class ReviewedPart(val path: String, val title: String, val suggestedTitle: String)

object TorrentSuggestions {

    /** Guesses metadata from the torrent name (e.g. "Author - Title (read by X) [2014, MP3]"). */
    fun build(torrentName: String, content: TorrentContentCheck.Valid, untitledPart: (Int) -> String = { "Chapter $it" }): TorrentSuggestion {
        val guess = NamePatternParser.parse(torrentName)
        val metadata = BookMetadata(
            title = guess.title ?: torrentName,
            author = guess.author,
            narrator = guess.narrator,
            series = guess.series,
            seriesIndex = guess.seriesIndex?.takeIf { guess.series != null },
            year = guess.year,
        ).normalized().let { if (it.title.isBlank()) it.copy(title = torrentName.trim()) else it }
        val audio = content.audio
        val titles = if (content.layout == AudiobookLayout.SINGLE_M4B) {
            listOf(metadata.title)
        } else {
            ImportDraftBuilder.titlesFromFileNames(audio.map { it.name }, untitledPart)
        }
        return TorrentSuggestion(
            metadata = metadata,
            parts = audio.mapIndexed { i, f -> TorrentPart(f.index, f.path, titles[i], f.sizeBytes) },
        )
    }
}

/** Result of combining the user's review with what the downloaded files actually contain. */
sealed interface MergedReview {
    data class Ready(val metadata: BookMetadata, val parts: List<DraftPart>) : MergedReview

    /** Files the user kept in the book were not found among the analysed files. */
    data class MissingParts(val paths: List<String>) : MergedReview
}

object TorrentReviewMerger {

    /**
     * Applies [review] to the draft built from the downloaded files. [pathOf] maps a draft part's
     * source id back to its path inside the torrent.
     */
    fun merge(review: TorrentReview, analyzed: BookMetadata, analyzedParts: List<DraftPart>, pathOf: (sourceId: String) -> String?): MergedReview {
        val byPath = analyzedParts.associateBy { pathOf(it.sourceId) }
        val missing = review.parts.filter { it.path !in byPath }.map { it.path }
        if (missing.isNotEmpty() || review.parts.isEmpty()) return MergedReview.MissingParts(missing)
        val parts = review.parts.map { reviewed ->
            val part = byPath.getValue(reviewed.path)
            val title = if (reviewed.title.isNotBlank() && reviewed.title != reviewed.suggestedTitle) reviewed.title else part.title
            part.copy(title = title.ifBlank { reviewed.title })
        }
        return MergedReview.Ready(mergeMetadata(review.suggested, review.edited, analyzed), parts)
    }

    fun mergeMetadata(suggested: BookMetadata, edited: BookMetadata, analyzed: BookMetadata): BookMetadata {
        val s = suggested.normalized()
        val e = edited.normalized()
        val a = analyzed.normalized()
        fun pick(sv: String?, ev: String?, av: String?): String? = if (ev != sv) ev else av ?: ev
        val title = if (e.title != s.title && e.title.isNotBlank()) e.title else a.title.ifBlank { e.title.ifBlank { s.title } }
        // Series and number are one decision: a number found in the tags belongs to the tags' series.
        val seriesEdited = e.series != s.series || e.seriesIndex != s.seriesIndex
        val (series, seriesIndex) = if (seriesEdited || a.series == null) e.series to e.seriesIndex else a.series to a.seriesIndex
        return BookMetadata(
            title = title,
            author = pick(s.author, e.author, a.author),
            narrator = pick(s.narrator, e.narrator, a.narrator),
            series = series,
            seriesIndex = seriesIndex,
            year = pick(s.year, e.year, a.year),
            genre = a.genre,
            description = pick(s.description, e.description, a.description),
            language = a.language,
        ).normalized()
    }
}

enum class IntegrityIssue {
    /** The file's content is not the format its name and the torrent layout promise. */
    WRONG_FORMAT,

    /** No audio stream was found. */
    NO_AUDIO_STREAM,

    /** The duration is missing or zero. */
    NO_DURATION,

    /** File size and duration don't fit together (mostly garbage, or a broken index). */
    IMPLAUSIBLE_BITRATE,
}

data class IntegrityProblem(val fileName: String, val issue: IntegrityIssue)

/**
 * Sanity checks for downloaded audiobook files, on top of what parsing already rejects
 * (truncated or damaged containers). A book with any problem is not imported.
 */
object AudiobookIntegrity {
    /** Lowest plausible average bitrate (kbit/s) of speech audio. */
    const val MIN_KBPS = 4L

    /** Highest plausible average bitrate (kbit/s); lossless stereo stays below this. */
    const val MAX_KBPS = 2_500L

    fun check(layout: AudiobookLayout, files: List<ImportSourceFile>): List<IntegrityProblem> = files.mapNotNull { file ->
        val parsed = file.parsed
        val expected = if (layout == AudiobookLayout.SINGLE_M4B) AudioContainer.MP4 else AudioContainer.MP3
        val duration = parsed.durationMs ?: 0L
        val issue = when {
            parsed.container != expected -> IntegrityIssue.WRONG_FORMAT
            parsed.stream == null -> IntegrityIssue.NO_AUDIO_STREAM
            duration <= 0L -> IntegrityIssue.NO_DURATION
            file.sizeBytes > 0 && (file.sizeBytes * 8 / duration) !in MIN_KBPS..MAX_KBPS -> IntegrityIssue.IMPLAUSIBLE_BITRATE
            else -> null
        }
        issue?.let { IntegrityProblem(file.displayName, it) }
    }
}
