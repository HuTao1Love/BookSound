package com.zyagodin.booksound.core.model

/**
 * A book in the library as seen by shared logic (search, sorting, sync). Platform layers map their
 * storage entities to this model; the location of the file is an opaque, platform specific string.
 */
data class Audiobook(
    val id: BookId,
    val metadata: BookMetadata,
    val durationMs: Long,
    val chapters: List<Chapter>,
    val file: BookFile,
    val addedAt: Long,
    val sync: SyncStamp,
)

/** Identity of the audio file belonging to a book. */
data class BookFile(
    /** Opaque platform location (a content URI on Android, a path on a server). Never used as identity. */
    val location: String,
    val sizeBytes: Long,
    /** Content hash used to detect changes and to verify transfers. */
    val sha256: String?,
    /** Incremented each time the file is regenerated (e.g. metadata rewritten). */
    val revision: Int,
)

/** Listening state of one book. */
data class PlaybackState(
    val bookId: BookId,
    val positionMs: Long,
    val speed: Float,
    val finished: Boolean,
    val lastPlayedAt: Long?,
    val sync: SyncStamp,
)

/**
 * Change-tracking stamp shared by all synchronizable records.
 * [revision] increases monotonically on every local change; [dirty] marks changes not yet pushed.
 */
data class SyncStamp(
    val revision: Long,
    val updatedAt: Long,
    val updatedBy: DeviceId,
    val deleted: Boolean = false,
    val dirty: Boolean = true,
) {
    fun touched(now: Long, device: DeviceId): SyncStamp =
        copy(revision = revision + 1, updatedAt = now, updatedBy = device, dirty = true)
}

/** Library entry enriched with progress, used for search/sorting and list display. */
data class LibraryEntry(
    val book: Audiobook,
    val positionMs: Long,
    val finished: Boolean,
    val lastPlayedAt: Long?,
) {
    val progress: Float
        get() = when {
            finished -> 1f
            book.durationMs <= 0 -> 0f
            else -> (positionMs.toFloat() / book.durationMs).coerceIn(0f, 1f)
        }
}
