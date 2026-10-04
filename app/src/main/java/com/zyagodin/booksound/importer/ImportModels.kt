package com.zyagodin.booksound.importer

import android.net.Uri
import com.zyagodin.booksound.core.metadata.AudioContainer
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.core.model.EmbeddedPicture
import com.zyagodin.booksound.core.organize.ConversionStrategy

/** What the user picked to import. */
sealed interface ImportSelection {
    data class Documents(val uris: List<Uri>) : ImportSelection
    data class Folder(val treeUri: Uri) : ImportSelection

    /** Re-tag a book that is already in the library (metadata/cover/chapter edit). */
    data class ExistingBook(val bookId: String) : ImportSelection

    /** Review a torrent's book details while it downloads; conversion starts after the download. */
    data class Torrent(val torrentId: String) : ImportSelection
}

/** One input file of a confirmed import. */
data class SourcePart(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val container: AudioContainer,
    /** Stream properties when known; used to give parallel-encoded parts one common format. */
    val sampleRate: Int? = null,
    val channels: Int? = null,
)

enum class ConflictPolicy {
    /** Never overwrite: pick "Title (2).m4b" if the name is taken. */
    KEEP_BOTH,

    /** User confirmed replacing the existing file at the target path. */
    REPLACE,
}

/** A fully specified, confirmed import. */
data class ImportRequest(
    val jobId: String,
    val bookId: String,
    val parts: List<SourcePart>,
    val metadata: BookMetadata,
    val chapters: List<Chapter>,
    val cover: EmbeddedPicture?,
    val strategy: ConversionStrategy,
    val bitrateKbps: Int,
    val downmixToMono: Boolean,
    val conflictPolicy: ConflictPolicy,
    /** Book whose file is overwritten by this import (replace or metadata edit). */
    val replacesBookId: String?,
    /** True when editing an existing book: keep its id, progress and "added" date. */
    val isEdit: Boolean,
    val sourceName: String,
    /** Persisted read grants taken for the sources; released when the job is finished with. */
    val heldPermissions: List<Uri>,
    val estimatedOutputBytes: Long,
    /** Torrent this import was started for; such jobs are shown and retried by the torrent. */
    val torrentId: String? = null,
    /**
     * Reject the import when the decoded audio is noticeably shorter or longer than the sources
     * claim (silently skipped damaged frames). Used for downloads, which nobody listened to yet.
     */
    val strictValidation: Boolean = false,
) {
    val totalDurationMs: Long get() = parts.sumOf { it.durationMs }
}

enum class ImportStage { QUEUED, PREPARING, CONVERTING, WRITING, VERIFYING, FINISHING, DONE, FAILED, CANCELLED }

data class ImportJob(
    val id: String,
    val request: ImportRequest,
    val stage: ImportStage = ImportStage.QUEUED,
    /** Overall progress 0..1, or null when indeterminate. */
    val progress: Float? = null,
    val failure: ImportFailure? = null,
    val resultBookId: String? = null,
    val coverPath: String? = null,
) {
    val isActive: Boolean get() = stage != ImportStage.DONE && stage != ImportStage.FAILED && stage != ImportStage.CANCELLED
    val title: String get() = request.metadata.title
}

/** User-presentable reasons an import can fail. */
sealed class ImportFailure(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    enum class Location { TEMPORARY, LIBRARY }

    class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long?, val location: Location) :
        ImportFailure("Not enough storage ($location): need $requiredBytes, have $availableBytes")

    class SourceUnavailable(val fileName: String, cause: Throwable? = null) : ImportFailure("Source unavailable: $fileName", cause)
    class UnsupportedFormat(val fileName: String?, val detail: String?) : ImportFailure("Unsupported: $fileName $detail")
    class CorruptedInput(val fileName: String?, val detail: String?) : ImportFailure("Corrupted: $fileName $detail")
    class ConversionFailed(val detail: String?, cause: Throwable? = null) : ImportFailure("Conversion failed: $detail", cause)

    /** The phone's codecs kept failing, even in fresh conversion processes; the file is likely fine. */
    class CodecFailure(val detail: String?, cause: Throwable? = null) : ImportFailure("Codec failed: $detail", cause)
    class LibraryUnavailable : ImportFailure("Library folder unavailable")
    class WriteFailed(val detail: String?, cause: Throwable? = null) : ImportFailure("Write failed: $detail", cause)
    class VerificationFailed(val detail: String?) : ImportFailure("Verification failed: $detail")
    class TimeLimit : ImportFailure("Android stopped the background task")
    class Unexpected(val detail: String?, cause: Throwable? = null) : ImportFailure("Unexpected: $detail", cause)
}
