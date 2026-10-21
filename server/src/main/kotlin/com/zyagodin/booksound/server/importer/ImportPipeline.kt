package com.zyagodin.booksound.server.importer

import com.zyagodin.booksound.api.ImportStage
import com.zyagodin.booksound.api.MetadataDto
import com.zyagodin.booksound.api.toDto
import com.zyagodin.booksound.api.toModel
import com.zyagodin.booksound.core.io.CancellationSignal
import com.zyagodin.booksound.core.io.RandomAccessSource
import com.zyagodin.booksound.core.metadata.AudioProbe
import com.zyagodin.booksound.core.metadata.mp4.Mp4Concat
import com.zyagodin.booksound.core.metadata.mp4.Mp4TagSpec
import com.zyagodin.booksound.core.model.BookId
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.core.model.EmbeddedPicture
import com.zyagodin.booksound.core.organize.ChapterPlanner
import com.zyagodin.booksound.core.organize.ConversionPlanner
import com.zyagodin.booksound.core.organize.ConversionStrategy
import com.zyagodin.booksound.core.organize.ImportChecks
import com.zyagodin.booksound.core.organize.ImportSourceFile
import com.zyagodin.booksound.core.organize.SourceAnalysis
import com.zyagodin.booksound.core.organize.SourceAnalyzer
import com.zyagodin.booksound.core.organize.SourceCandidate
import com.zyagodin.booksound.core.torrent.AudiobookIntegrity
import com.zyagodin.booksound.core.torrent.AudiobookLayout
import com.zyagodin.booksound.core.torrent.ReviewedPart
import com.zyagodin.booksound.core.torrent.TorrentReview
import com.zyagodin.booksound.core.torrent.TorrentReviewMerger
import com.zyagodin.booksound.core.torrent.MergedReview
import com.zyagodin.booksound.server.library.Covers
import com.zyagodin.booksound.server.library.LibraryService
import com.zyagodin.booksound.server.settings.SettingsStore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import java.io.File

/**
 * One import, the way the phone does it: read the files, pick copy or re-encode, write tags, chapters and
 * cover into a hidden file in the library folder, parse it back, then move it into place.
 */
class ImportPipeline(
    private val library: LibraryService,
    private val settings: SettingsStore,
    private val ffmpeg: Ffmpeg,
) {
    /** Returns the id of the book now in the library. */
    suspend fun run(jobId: String, spec: ImportSpec, workDir: File, report: (ImportStage, Float?) -> Unit): String {
        report(ImportStage.PREPARING, null)
        val (audio, images) = spec.files.map { ref ->
            val f = File(ref.path)
            if (!f.isFile) throw ImportFailure("Source file missing: ${f.name}")
            SourceCandidate(f.absolutePath, f.name, ref.relativeDir, f.length())
        }.filter { SourceAnalyzer.isAudioName(it.name) || SourceAnalyzer.isImage(it.name) }
            .partition { SourceAnalyzer.isAudioName(it.name) }

        val analysis = SourceAnalyzer.analyze(
            audio, images, spec.sourceName,
            open = { library.files.open(File(it.id)) },
            readImage = { c, max -> File(c.id).takeIf { it.length() <= max }?.readBytes() },
        )
        val ready = when (analysis) {
            SourceAnalysis.NoAudio -> throw ImportFailure("No audio files")
            is SourceAnalysis.NothingReadable -> throw ImportFailure("No readable audio: " + analysis.skipped.joinToString { "${it.name} (${it.detail})" })
            is SourceAnalysis.Ready -> analysis
        }
        if (spec.strict && ready.skipped.isNotEmpty()) {
            throw ImportFailure("Unreadable files: " + ready.skipped.joinToString { "${it.name} (${it.detail})" })
        }

        spec.expectedLayout?.let { layout ->
            val problems = AudiobookIntegrity.check(AudiobookLayout.valueOf(layout), ready.files)
            if (problems.isNotEmpty()) throw ImportFailure("Damaged files: " + problems.joinToString { "${it.fileName} (${it.issue.name.lowercase()})" })
        }

        // The book's parts and details: the user's choices over what the files say.
        val draft = ready.draft
        val (metadata, parts) = if (spec.partOrder != null || spec.suggested != null) {
            val suggested = (spec.suggested ?: spec.edited ?: draft.metadata.toDto()).toModel()
            val edited = (spec.edited ?: spec.suggested ?: draft.metadata.toDto()).toModel()
            val order = spec.partOrder ?: draft.parts.map { it.sourceId }
            val pathOf = { id: String -> id }
            val review = TorrentReview(suggested, edited, order.map { path ->
                val title = draft.parts.firstOrNull { it.sourceId == path }?.title.orEmpty()
                ReviewedPart(path, title, title)
            })
            when (val merged = TorrentReviewMerger.merge(review, draft.metadata, draft.parts, pathOf)) {
                is MergedReview.MissingParts -> throw ImportFailure("Files missing: " + merged.paths.joinToString { File(it).name })
                is MergedReview.Ready -> merged.metadata to merged.parts
            }
        } else {
            overlay(draft.metadata, spec.edited) to draft.parts
        }
        val files = ready.files.associateBy { it.id }
        val sources = parts.map { files.getValue(it.sourceId) }
        val chapters = ChapterPlanner.plan(parts)
        val cover = spec.coverPath?.let { File(it) }?.takeIf { it.isFile }?.readBytes()?.let(Covers::normalize)
            ?: draft.covers.firstNotNullOfOrNull { Covers.normalize(it.picture.bytes) }

        // An id taken from the source is kept only if no other book has it: importing a copy never replaces the original.
        val bookId = spec.bookId
            ?: draft.embeddedBookId?.takeIf { library.repo.book(it)?.takeIf { b -> !b.deleted } == null }
            ?: BookId.random().value

        val strategy = ConversionPlanner.plan(sources.map { it.parsed })
        if (strategy == ConversionStrategy.TRANSCODE && !ffmpeg.available) throw ImportFailure("ffmpeg is needed to convert these files")
        val totalDuration = sources.sumOf { it.parsed.durationMs ?: 0L }
        val bitrate = ConversionPlanner.outputBitrateKbps(settings.current.encoderBitrateKbps, sources)
        checkSpace(strategy, sources, totalDuration, bitrate, cover)

        var alignedChapters = chapters
        val source: RandomAccessSource = when (strategy) {
            ConversionStrategy.REMUX_SINGLE -> library.files.open(File(sources.single().id))
            ConversionStrategy.CONCAT_COPY -> join(sources)
                ?: transcode(sources, workDir, bitrate, totalDuration, spec.strict, report).also { alignedChapters = align(it, chapters, totalDuration, spec.strict) }
            ConversionStrategy.TRANSCODE -> transcode(sources, workDir, bitrate, totalDuration, spec.strict, report)
                .also { alignedChapters = align(it, chapters, totalDuration, spec.strict) }
        }

        val partial: File
        val target: File
        val expectedDuration: Long
        source.use { src ->
            expectedDuration = runCatching { AudioProbe.probe(src, "source.m4a").durationMs }.getOrNull() ?: totalDuration
            val own = library.repo.book(bookId)?.let { runCatching { library.fileOf(it) }.getOrNull() }
            target = library.files.targetFor(metadata, spec.sourceName, own)
            partial = library.files.partialFor(target, jobId)
            report(ImportStage.WRITING, 0f)
            val ctx = currentCoroutineContext()
            val size = src.size.coerceAtLeast(1)
            library.files.writeTagged(src, partial, Mp4TagSpec(bookId, metadata, alignedChapters, cover), CancellationSignal { !ctx.isActive }) {
                report(ImportStage.WRITING, (it.toFloat() / size).coerceAtMost(1f))
            }
        }
        try {
            report(ImportStage.VERIFYING, null)
            val sha = library.verify(partial, bookId, expectedDuration, alignedChapters.size)
            report(ImportStage.FINISHING, null)
            library.commitBook(partial, target, bookId, metadata.toDto(), alignedChapters, expectedDuration, sha, cover, spec.addedBy, library.serverDevice)
        } finally {
            partial.delete()
        }
        return bookId
    }

    /** The parts read as one file without re-encoding, or null when they can't be joined. */
    private fun join(sources: List<ImportSourceFile>): RandomAccessSource? {
        val opened = sources.map { library.files.open(File(it.id)) }
        return try {
            Mp4Concat.join(opened)
        } catch (e: Exception) {
            opened.forEach { runCatching { it.close() } }
            null
        }
    }

    private suspend fun transcode(
        sources: List<ImportSourceFile>,
        workDir: File,
        bitrate: Int,
        totalDuration: Long,
        strict: Boolean,
        report: (ImportStage, Float?) -> Unit,
    ): RandomAccessSource {
        report(ImportStage.CONVERTING, 0f)
        val out = File(workDir, "audio.m4a")
        // The most common input rate, so that most parts need no resampling; mono only if asked or all mono.
        val sampleRate = sources.mapNotNull { it.parsed.stream?.sampleRate }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val channels = if (settings.current.downmixMono || sources.all { it.parsed.stream?.channels == 1 }) 1 else 2
        ffmpeg.encodeAac(sources.map { File(it.id) }, out, bitrate, channels, sampleRate, totalDuration) { report(ImportStage.CONVERTING, it) }
        return library.files.open(out)
    }

    private fun align(source: RandomAccessSource, chapters: List<Chapter>, expected: Long, strict: Boolean): List<Chapter> {
        val actual = runCatching { AudioProbe.probe(source, "audio.m4a").durationMs }.getOrNull()
        if (strict) ImportChecks.decodedDurationProblem(actual, expected)?.let { throw ImportFailure("Damaged audio: $it") }
        return if (actual != null) ImportChecks.alignChapters(chapters, actual, expected) else chapters
    }

    private fun checkSpace(strategy: ConversionStrategy, sources: List<ImportSourceFile>, durationMs: Long, bitrate: Int, cover: EmbeddedPicture?) {
        val estimate = ConversionPlanner.estimateOutputBytes(strategy, sources.sumOf { it.sizeBytes }, durationMs, bitrate, cover?.bytes?.size ?: 0)
        val free = library.files.root.usableSpace
        if (free in 1 until estimate * 2 + RESERVE_BYTES) {
            throw ImportFailure("Not enough disk space: need about ${(estimate * 2) shr 20} MiB, ${free shr 20} MiB free")
        }
    }

    /** The files' metadata with every field the user set laid over it. */
    private fun overlay(analyzed: BookMetadata, edited: MetadataDto?): BookMetadata {
        if (edited == null) return analyzed
        return BookMetadata(
            title = edited.title.ifBlank { analyzed.title },
            author = edited.author ?: analyzed.author,
            narrator = edited.narrator ?: analyzed.narrator,
            series = edited.series ?: analyzed.series,
            seriesIndex = edited.seriesIndex ?: analyzed.seriesIndex,
            year = edited.year ?: analyzed.year,
            genre = edited.genre ?: analyzed.genre,
            description = edited.description ?: analyzed.description,
            language = edited.language ?: analyzed.language,
        ).normalized()
    }

    companion object {
        private const val RESERVE_BYTES = 200L * 1024 * 1024
    }
}
