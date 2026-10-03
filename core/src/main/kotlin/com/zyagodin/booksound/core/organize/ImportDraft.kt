package com.zyagodin.booksound.core.organize

import com.zyagodin.booksound.core.metadata.ParsedAudioFile
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.core.model.ChapterMark
import com.zyagodin.booksound.core.model.EmbeddedPicture
import com.zyagodin.booksound.core.model.SeriesIndex
import com.zyagodin.booksound.core.naming.NaturalOrder

/** One input file of an import, already parsed. */
data class ImportSourceFile(
    /** Opaque identifier of the file (a content URI on Android). */
    val id: String,
    val displayName: String,
    /** Directories between the selected import root and the file. */
    val relativeDir: List<String>,
    val sizeBytes: Long,
    val parsed: ParsedAudioFile,
)

/** One input file as an ordered, titled part of the book being imported. */
data class DraftPart(
    val sourceId: String,
    val displayName: String,
    val title: String,
    val durationMs: Long,
    /** Chapters embedded in this part (titles editable by the user). */
    val chapters: List<ChapterMark>,
)

enum class CoverOrigin { EMBEDDED, FOLDER, ONLINE, USER }

data class CoverCandidate(val picture: EmbeddedPicture, val origin: CoverOrigin, val label: String)

/** Everything the user reviews before an import starts. */
data class ImportDraft(
    val metadata: BookMetadata,
    val parts: List<DraftPart>,
    val covers: List<CoverCandidate>,
    /** Name of the selected file or folder. Used as the deterministic fallback title. */
    val sourceName: String,
    /** BookSound id embedded in the source (re-import of a file produced by this app). */
    val embeddedBookId: String?,
) {
    val totalDurationMs: Long get() = parts.sumOf { it.durationMs }
}

object ImportDraftBuilder {

    /**
     * Builds an initial draft from parsed files. [folderImages] are image files found next to the
     * audio (cover.jpg etc.). [untitledPart] produces localized "Chapter N" titles.
     */
    fun build(
        sourceName: String,
        files: List<ImportSourceFile>,
        folderImages: List<Pair<String, EmbeddedPicture>> = emptyList(),
        untitledPart: (Int) -> String = { "Chapter $it" },
    ): ImportDraft {
        require(files.isNotEmpty()) { "No audio files" }
        val ordered = order(files)
        val tags = ordered.map { it.parsed.tags }
        val single = ordered.size == 1
        val nameGuess = NamePatternParser.parse(sourceName)

        val title = if (single) {
            tags[0].title ?: tags[0].album ?: nameGuess.title
        } else {
            mostCommon(tags.map { it.album }) ?: nameGuess.title ?: mostCommon(tags.map { it.title })
        } ?: sourceName

        val author = mostCommon(tags.map { it.artist }) ?: mostCommon(tags.map { it.albumArtist }) ?: nameGuess.author
        val composer = mostCommon(tags.map { it.composer })
        val narrator = mostCommon(tags.map { it.narrator })
            ?: composer?.takeIf { !it.equals(author, ignoreCase = true) }
            ?: nameGuess.narrator
            ?: tags.firstNotNullOfOrNull { NamePatternParser.narratorFromText(it.comment) ?: NamePatternParser.narratorFromText(it.description) }

        val groupingSeries = NamePatternParser.parseSeries(mostCommon(tags.map { it.grouping }))
        val series = mostCommon(tags.map { it.series }) ?: groupingSeries?.takeIf { it.second != null }?.first ?: nameGuess.series
        val seriesIndex = SeriesIndex.normalize(mostCommon(tags.map { it.seriesPart }))
            ?: groupingSeries?.second
            ?: nameGuess.seriesIndex?.takeIf { series != null }

        val description = tags.firstNotNullOfOrNull { it.description }
            ?: tags.firstNotNullOfOrNull { it.comment?.takeIf { c -> c.length >= 60 } }

        val metadata = BookMetadata(
            title = title,
            author = author,
            narrator = narrator,
            series = series,
            seriesIndex = seriesIndex,
            year = mostCommon(tags.map { it.year }) ?: nameGuess.year,
            genre = mostCommon(tags.map { it.genre }),
            description = description,
            language = mostCommon(tags.map { it.language }),
        ).normalized()

        return ImportDraft(
            metadata = metadata,
            parts = partTitles(ordered, untitledPart),
            covers = coverCandidates(ordered, folderImages),
            sourceName = sourceName,
            embeddedBookId = if (single) ordered[0].parsed.bookId else null,
        )
    }

    /** Orders by disc/track numbers when they are complete and unique, otherwise by natural path order. */
    fun order(files: List<ImportSourceFile>): List<ImportSourceFile> {
        val byPath = files.sortedWith { a, b ->
            NaturalOrder.compare((a.relativeDir + a.displayName).joinToString("/"), (b.relativeDir + b.displayName).joinToString("/"))
        }
        if (files.size < 2) return byPath
        val keys = files.map { f -> f.parsed.tags.trackNumber?.let { (f.parsed.tags.discNumber ?: 1) to it } }
        if (keys.any { it == null } || keys.toSet().size != keys.size) return byPath
        // Track numbers that contradict the file names in many places are probably wrong; prefer names.
        val byTrack = files.sortedWith(compareBy({ it.parsed.tags.discNumber ?: 1 }, { it.parsed.tags.trackNumber }))
        val disagreements = byTrack.indices.count { byTrack[it] !== byPath[it] }
        return if (disagreements > files.size / 2) byPath else byTrack
    }

    private fun partTitles(files: List<ImportSourceFile>, untitledPart: (Int) -> String): List<DraftPart> {
        val tagTitles = files.map { it.parsed.tags.title }
        val tagTitlesUsable = files.size > 1 &&
            tagTitles.all { !it.isNullOrBlank() } && tagTitles.toSet().size == tagTitles.size
        val stems = files.map { it.displayName.substringBeforeLast('.') }
        val prefix = if (files.size > 1) commonWordPrefix(stems) else ""
        return files.mapIndexed { i, file ->
            val title = when {
                files.size == 1 -> file.parsed.tags.title ?: stems[i]
                tagTitlesUsable -> tagTitles[i]!!
                else -> titleFromName(stems[i], prefix, i, untitledPart)
            }
            DraftPart(
                sourceId = file.id,
                displayName = file.displayName,
                title = title,
                durationMs = file.parsed.durationMs ?: 0L,
                chapters = file.parsed.chapters,
            )
        }
    }

    /**
     * Part titles from file names alone, for when the files' tags are not known yet (e.g. a
     * torrent that has not been downloaded). Same rules as for parts without usable tags.
     */
    fun titlesFromFileNames(names: List<String>, untitledPart: (Int) -> String = { "Chapter $it" }): List<String> {
        val stems = names.map { it.substringBeforeLast('.') }
        if (stems.size <= 1) return stems
        val prefix = commonWordPrefix(stems)
        return stems.mapIndexed { i, stem -> titleFromName(stem, prefix, i, untitledPart) }
    }

    private fun titleFromName(stem: String, prefix: String, index: Int, untitledPart: (Int) -> String): String {
        val fromName = stem.removePrefix(prefix).trim().trim('-', '_', '.', ' ')
        return when {
            fromName.any { it.isLetter() } -> fromName
            stem.any { it.isLetter() } && prefix.isEmpty() -> stem
            else -> untitledPart(index + 1)
        }
    }

    private fun commonWordPrefix(values: List<String>): String {
        if (values.isEmpty()) return ""
        var prefix = values.first()
        for (v in values.drop(1)) {
            var i = 0
            while (i < prefix.length && i < v.length && prefix[i] == v[i]) i++
            prefix = prefix.substring(0, i)
        }
        // Only cut at a separator so words are never split, and never strip digits that differ.
        val cut = prefix.indexOfLast { it == ' ' || it == '-' || it == '_' || it == '.' }
        return if (cut >= 0) prefix.substring(0, cut + 1) else ""
    }

    private fun coverCandidates(files: List<ImportSourceFile>, folderImages: List<Pair<String, EmbeddedPicture>>): List<CoverCandidate> {
        val result = mutableListOf<CoverCandidate>()
        val seen = HashSet<Int>()
        for (file in files) {
            val cover = file.parsed.cover ?: continue
            if (seen.add(cover.bytes.contentHashCode())) {
                result += CoverCandidate(cover, CoverOrigin.EMBEDDED, file.displayName)
            }
        }
        val preferred = Regex("""(cover|folder|front|обложка)""", RegexOption.IGNORE_CASE)
        folderImages.sortedWith(compareBy({ !preferred.containsMatchIn(it.first) }, { it.first }))
            .forEach { (name, picture) ->
                if (seen.add(picture.bytes.contentHashCode())) result += CoverCandidate(picture, CoverOrigin.FOLDER, name)
            }
        return result
    }

    /** Most frequent non-blank value (ties resolved by first occurrence). */
    fun mostCommon(values: List<String?>): String? {
        val counts = LinkedHashMap<String, Int>()
        for (v in values) {
            val clean = v?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            counts[clean] = (counts[clean] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key
    }
}

object ChapterPlanner {

    /**
     * Builds the final chapter list from ordered parts. Parts with embedded chapters contribute
     * them (shifted by the part's offset), other parts become one chapter each.
     */
    fun plan(parts: List<DraftPart>, untitledChapter: (Int) -> String = { "Chapter $it" }): List<Chapter> {
        data class Start(val startMs: Long, val title: String?)
        val starts = mutableListOf<Start>()
        var offset = 0L
        for (part in parts) {
            val marks = part.chapters.filter { it.startMs < part.durationMs || part.durationMs == 0L }.sortedBy { it.startMs }
            if (marks.size <= 1) {
                val embeddedTitle = marks.firstOrNull()?.title
                starts += Start(offset, if (parts.size == 1) embeddedTitle ?: part.title else part.title)
            } else {
                marks.forEachIndexed { i, mark -> starts += Start(offset + if (i == 0) 0 else mark.startMs, mark.title) }
            }
            offset += part.durationMs
        }
        val total = offset
        val unique = starts.distinctBy { it.startMs }
        return unique.mapIndexed { i, s ->
            val end = if (i + 1 < unique.size) unique[i + 1].startMs else maxOf(total, s.startMs)
            Chapter(index = i, title = s.title?.takeIf { it.isNotBlank() } ?: untitledChapter(i + 1), startMs = s.startMs, endMs = end)
        }
    }

    /** Converts existing chapters back into marks, e.g. to edit an imported book. */
    fun marksOf(chapters: List<Chapter>): List<ChapterMark> = chapters.map { ChapterMark(it.startMs, it.title) }
}
