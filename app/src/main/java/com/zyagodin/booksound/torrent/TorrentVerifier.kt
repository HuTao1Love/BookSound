package com.zyagodin.booksound.torrent

import android.content.Context
import android.net.Uri
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.model.EmbeddedPicture
import com.zyagodin.booksound.core.torrent.AudiobookIntegrity
import com.zyagodin.booksound.core.torrent.IntegrityIssue
import com.zyagodin.booksound.core.torrent.MergedReview
import com.zyagodin.booksound.core.torrent.TorrentReviewMerger
import com.zyagodin.booksound.cover.CoverImages
import com.zyagodin.booksound.data.library.LibraryRepository
import com.zyagodin.booksound.importer.AnalysisState
import com.zyagodin.booksound.importer.ImportAnalyzer
import com.zyagodin.booksound.importer.ImportDecision
import com.zyagodin.booksound.importer.ImportPlanner
import com.zyagodin.booksound.importer.PlanOutcome
import com.zyagodin.booksound.importer.SkipReason
import java.io.File

/** What checking a downloaded book found: it can be converted, it can't, or it is already in the library. */
internal sealed interface Verification {
    class Planned(val request: com.zyagodin.booksound.importer.ImportRequest) : Verification
    class Failed(val failure: TorrentFailure) : Verification
    data object AlreadyInLibrary : Verification
}

/**
 * The last checks a downloaded book passes before it may enter the library, and the plan of its conversion.
 * A book that fails any of them never reaches the library.
 */
internal class TorrentVerifier(
    private val context: Context,
    private val store: TorrentStore,
    private val library: LibraryRepository,
    private val analyzer: ImportAnalyzer,
    private val planner: ImportPlanner,
) {
    /**
     * Checks the downloaded files of the book [id] (all there, the expected sizes, real audio of a plausible
     * bitrate) and plans its conversion with the details the user reviewed.
     */
    suspend fun verifyAndPlan(id: String): Verification {
        val r = store[id] ?: return Verification.Failed(TorrentFailure(TorrentFailureCode.FILES_MISSING))
        // A conversion interrupted by a crash may have finished just before it.
        library.book(r.bookId)?.takeIf { !it.deleted }?.let { return Verification.AlreadyInLibrary }

        val dir = File(r.dataDir)
        val wantedFiles = r.files.filter { it.index in r.wanted }
        val missing = wantedFiles.filter { f -> File(dir, f.path).let { !it.isFile || it.length() != f.size } }
        if (missing.isNotEmpty()) return Verification.Failed(TorrentFailure(TorrentFailureCode.FILES_MISSING, files = missing.map { it.path }))

        val inputs = wantedFiles.map { f -> File(dir, f.path) to f.path.split('/').dropLast(1) }
        // A book of a collection is named after its folder, not after the whole torrent.
        val analysis = analyzer.analyzeLocalFiles(inputs, r.volume ?: r.name)
        val ready = analysis as? AnalysisState.Ready
            ?: return Verification.Failed(TorrentFailure(TorrentFailureCode.AUDIOBOOK_INVALID, files = (analysis as? AnalysisState.Failed)?.skipped.orEmpty().map { it.name }))
        if (ready.skipped.isNotEmpty()) {
            return Verification.Failed(
                TorrentFailure(TorrentFailureCode.AUDIOBOOK_INVALID, files = ready.skipped.map { "${it.name} — ${skipLabel(it.reason)}" }),
            )
        }
        val problems = AudiobookIntegrity.check(r.layout ?: return Verification.Failed(TorrentFailure(TorrentFailureCode.FILES_MISSING)), ready.files)
        if (problems.isNotEmpty()) {
            return Verification.Failed(
                TorrentFailure(TorrentFailureCode.AUDIOBOOK_INVALID, files = problems.map { "${it.fileName} — ${integrityLabel(it.issue)}" }),
            )
        }

        val review = r.review() ?: return Verification.Failed(TorrentFailure(TorrentFailureCode.FILES_MISSING))
        val pathByUri = r.files.associate { r.fileUri(it.path) to it.path }
        val merged = when (val m = TorrentReviewMerger.merge(review, ready.draft.metadata, ready.draft.parts) { pathByUri[it] }) {
            is MergedReview.MissingParts -> return Verification.Failed(TorrentFailure(TorrentFailureCode.FILES_MISSING, files = m.paths))
            is MergedReview.Ready -> m
        }
        val cover = store.customCover(id) ?: ready.draft.covers.firstNotNullOfOrNull { CoverImages.normalize(it.picture.bytes) }
        val outcome = planner.plan(
            ImportPlanner.Input(
                metadata = merged.metadata,
                parts = merged.parts,
                files = ready.files.associateBy { it.id },
                cover = cover,
                sourceName = r.volume ?: r.name,
                isEdit = false,
                existingBookId = null,
                heldPermissions = emptyList(),
                newBookId = r.bookId,
                torrentId = r.id,
                strictValidation = true,
            ),
            // Unattended: never overwrite anything, add "Title (2)" instead.
            ImportDecision.KEEP_BOTH,
        )
        return when (outcome) {
            is PlanOutcome.Ready -> Verification.Planned(outcome.request)
            PlanOutcome.LibraryUnavailable -> Verification.Failed(TorrentFailure(TorrentFailureCode.CONVERSION_FAILED, context.getString(R.string.failure_library)))
            is PlanOutcome.NeedsDecision -> Verification.Failed(TorrentFailure(TorrentFailureCode.CONVERSION_FAILED, null))
        }
    }

    private fun skipLabel(reason: SkipReason): String = context.getString(
        when (reason) {
            SkipReason.UNSUPPORTED -> R.string.skip_unsupported
            SkipReason.CORRUPTED -> R.string.skip_corrupted
            SkipReason.UNREADABLE -> R.string.skip_unreadable
        },
    )

    private fun integrityLabel(issue: IntegrityIssue): String = context.getString(
        when (issue) {
            IntegrityIssue.WRONG_FORMAT -> R.string.integrity_wrong_format
            IntegrityIssue.NO_AUDIO_STREAM -> R.string.integrity_no_audio
            IntegrityIssue.NO_DURATION -> R.string.integrity_no_duration
            IntegrityIssue.IMPLAUSIBLE_BITRATE -> R.string.integrity_implausible
        },
    )
}

/** Where a downloaded file of this torrent is, as an address the importer reads. */
internal fun TorrentRecord.fileUri(path: String): String = Uri.fromFile(File(dataDir, path)).toString()

/** The cover the user chose for the torrent's book, if any. */
internal fun TorrentStore.customCover(id: String): EmbeddedPicture? {
    val file = coverFile(id).takeIf { it.isFile } ?: return null
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    return EmbeddedPicture.sniffMimeType(bytes)?.let { EmbeddedPicture(bytes, it) }
}
