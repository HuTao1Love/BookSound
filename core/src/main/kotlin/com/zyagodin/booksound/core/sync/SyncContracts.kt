package com.zyagodin.booksound.core.sync

import com.zyagodin.booksound.core.model.BookId
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.core.model.DeviceId
import com.zyagodin.booksound.core.model.SyncStamp

/*
 * Contracts for synchronizing the library with an optional self-hosted backend.
 *
 * The app is local-first: every operation works offline against local storage, and local changes
 * are only *marked* dirty. A SyncEngine (not implemented yet) pushes dirty records and pulls remote
 * changes when a backend is reachable. Nothing in the player, library or import pipeline talks to a
 * backend directly.
 */

/** Book record exchanged with a backend. Keyed by [BookId], never by a file path. */
data class BookRecord(
    val id: BookId,
    val metadata: BookMetadata,
    val durationMs: Long,
    val file: FileDescriptorRecord,
    val coverSha256: String?,
    val addedAt: Long,
    val stamp: SyncStamp,
)

/** Describes the audio file so other devices can download and verify it. */
data class FileDescriptorRecord(val sizeBytes: Long, val sha256: String?, val revision: Int, val mimeType: String = "audio/mp4")

data class ChapterListRecord(val bookId: BookId, val chapters: List<Chapter>, val stamp: SyncStamp)

data class PlaybackRecord(
    val bookId: BookId,
    val positionMs: Long,
    val speed: Float,
    val finished: Boolean,
    val lastPlayedAt: Long?,
    val stamp: SyncStamp,
)

/** Per-user preferences worth sharing between devices. */
data class PreferencesRecord(
    val defaultSpeed: Float,
    val sleepTimerMinutes: Int,
    val skipBackSeconds: Int,
    val skipForwardSeconds: Int,
    val stamp: SyncStamp,
)

/** Opaque position in the backend's change feed. */
@JvmInline
value class SyncCursor(val value: String)

data class ChangeSet(
    val books: List<BookRecord> = emptyList(),
    val chapters: List<ChapterListRecord> = emptyList(),
    val playback: List<PlaybackRecord> = emptyList(),
    val preferences: PreferencesRecord? = null,
) {
    val isEmpty: Boolean get() = books.isEmpty() && chapters.isEmpty() && playback.isEmpty() && preferences == null
}

data class PullResult(val changes: ChangeSet, val cursor: SyncCursor, val hasMore: Boolean)

data class PushResult(
    /** Records the backend accepted, with the revision it assigned. */
    val accepted: Map<BookId, Long>,
    /** Records rejected because the server holds a newer revision; they must be pulled and merged. */
    val conflicts: List<BookId>,
)

sealed interface BackendStatus {
    data object NotConfigured : BackendStatus
    data object Unreachable : BackendStatus
    data class Available(val serverVersion: String) : BackendStatus
    data class Unauthorized(val message: String) : BackendStatus
}

/** Destination for downloaded file bytes (a temp file on Android). */
interface FileSink {
    fun write(bytes: ByteArray, offset: Int, length: Int)
    /** Bytes already present, to resume interrupted downloads. */
    val existingBytes: Long
}

/**
 * Transport to a backend. Implementations must be safe to call when offline: they return
 * [BackendStatus.Unreachable] or throw an IOException, never block indefinitely.
 */
interface SyncBackend {
    suspend fun status(): BackendStatus
    suspend fun pull(since: SyncCursor?): PullResult
    suspend fun push(changes: ChangeSet, device: DeviceId): PushResult
    suspend fun downloadFile(bookId: BookId, revision: Int, sink: FileSink, onProgress: (Long, Long) -> Unit)
    suspend fun uploadFile(bookId: BookId, revision: Int, sizeBytes: Long, open: () -> java.io.InputStream, onProgress: (Long, Long) -> Unit)
    suspend fun downloadCover(bookId: BookId): ByteArray?
    suspend fun uploadCover(bookId: BookId, bytes: ByteArray, mimeType: String)
}

/** Backend used while no server is configured: always reports [BackendStatus.NotConfigured]. */
object NoBackend : SyncBackend {
    override suspend fun status(): BackendStatus = BackendStatus.NotConfigured
    override suspend fun pull(since: SyncCursor?): PullResult = PullResult(ChangeSet(), since ?: SyncCursor(""), hasMore = false)
    override suspend fun push(changes: ChangeSet, device: DeviceId) = PushResult(emptyMap(), emptyList())
    override suspend fun downloadFile(bookId: BookId, revision: Int, sink: FileSink, onProgress: (Long, Long) -> Unit) =
        throw UnsupportedOperationException("No backend configured")
    override suspend fun uploadFile(bookId: BookId, revision: Int, sizeBytes: Long, open: () -> java.io.InputStream, onProgress: (Long, Long) -> Unit) =
        throw UnsupportedOperationException("No backend configured")
    override suspend fun downloadCover(bookId: BookId): ByteArray? = null
    override suspend fun uploadCover(bookId: BookId, bytes: ByteArray, mimeType: String) = Unit
}

/** Local side of synchronization, implemented by the app's storage layer. */
interface SyncLocalStore {
    suspend fun dirtyChanges(): ChangeSet
    suspend fun markPushed(changes: ChangeSet)
    suspend fun applyRemote(changes: ChangeSet, resolver: PlaybackConflictResolver)
    suspend fun cursor(): SyncCursor?
    suspend fun saveCursor(cursor: SyncCursor)
}

sealed interface SyncReport {
    data object Skipped : SyncReport
    data class Completed(val pushed: Int, val pulled: Int, val conflicts: Int) : SyncReport
    data class Failed(val reason: String) : SyncReport
}

/** Orchestrates one synchronization round. */
interface SyncEngine {
    suspend fun sync(): SyncReport
}

/** Reference engine: push dirty records, then pull and merge remote changes. */
class DefaultSyncEngine(
    private val backend: SyncBackend,
    private val local: SyncLocalStore,
    private val device: DeviceId,
    private val resolver: PlaybackConflictResolver = PlaybackConflictResolver(),
) : SyncEngine {
    override suspend fun sync(): SyncReport {
        when (val status = backend.status()) {
            BackendStatus.NotConfigured, BackendStatus.Unreachable -> return SyncReport.Skipped
            is BackendStatus.Unauthorized -> return SyncReport.Failed(status.message)
            is BackendStatus.Available -> Unit
        }
        return try {
            val outgoing = local.dirtyChanges()
            val push = if (outgoing.isEmpty) PushResult(emptyMap(), emptyList()) else backend.push(outgoing, device)
            if (!outgoing.isEmpty) local.markPushed(outgoing)
            var cursor = local.cursor()
            var pulled = 0
            do {
                val page = backend.pull(cursor)
                local.applyRemote(page.changes, resolver)
                pulled += page.changes.books.size + page.changes.playback.size
                cursor = page.cursor
                local.saveCursor(page.cursor)
            } while (page.hasMore)
            SyncReport.Completed(push.accepted.size, pulled, push.conflicts.size)
        } catch (e: java.io.IOException) {
            SyncReport.Failed(e.message ?: "Network error")
        }
    }
}
