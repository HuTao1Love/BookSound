package com.zyagodin.booksound.sync

import androidx.room.withTransaction
import com.zyagodin.booksound.core.model.BookId
import com.zyagodin.booksound.core.model.DeviceId
import com.zyagodin.booksound.core.model.SyncStamp
import com.zyagodin.booksound.core.sync.BackendStatus
import com.zyagodin.booksound.core.sync.BookRecord
import com.zyagodin.booksound.core.sync.ChangeSet
import com.zyagodin.booksound.core.sync.ChapterListRecord
import com.zyagodin.booksound.core.sync.DefaultSyncEngine
import com.zyagodin.booksound.core.sync.FileDescriptorRecord
import com.zyagodin.booksound.core.sync.NoBackend
import com.zyagodin.booksound.core.sync.PlaybackConflictResolver
import com.zyagodin.booksound.core.sync.PlaybackRecord
import com.zyagodin.booksound.core.sync.SyncBackend
import com.zyagodin.booksound.core.sync.SyncCursor
import com.zyagodin.booksound.core.sync.SyncLocalStore
import com.zyagodin.booksound.core.sync.SyncReport
import com.zyagodin.booksound.data.db.AppDatabase
import com.zyagodin.booksound.data.db.PlaybackStateEntity
import com.zyagodin.booksound.data.library.metadata
import com.zyagodin.booksound.data.library.toCore
import com.zyagodin.booksound.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Entry point for synchronization. Today the backend is [NoBackend], so every request is a no-op;
 * plugging in a real [SyncBackend] (e.g. a home server) requires no changes to the player,
 * library or import code, which only mark records dirty.
 */
class SyncCoordinator(
    private val scope: CoroutineScope,
    private val localStore: SyncLocalStore,
    private val device: suspend () -> DeviceId,
    private val backend: SyncBackend = NoBackend,
) {
    private val _lastReport = MutableStateFlow<SyncReport>(SyncReport.Skipped)
    val lastReport: StateFlow<SyncReport> = _lastReport
    private val mutex = Mutex()

    suspend fun status(): BackendStatus = backend.status()

    fun requestSync() {
        scope.launch {
            mutex.withLock {
                _lastReport.value = DefaultSyncEngine(backend, localStore, device()).sync()
            }
        }
    }
}

/** Room-backed implementation of the sync engine's local side. */
class RoomSyncLocalStore(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) : SyncLocalStore {

    override suspend fun dirtyChanges(): ChangeSet {
        val books = db.books().dirty()
        return ChangeSet(
            books = books.map { b ->
                BookRecord(
                    id = BookId(b.id),
                    metadata = b.metadata(),
                    durationMs = b.durationMs,
                    file = FileDescriptorRecord(b.fileSize, b.fileSha256, b.fileRevision),
                    coverSha256 = b.coverSha256,
                    addedAt = b.addedAt,
                    stamp = SyncStamp(b.revision, b.updatedAt, DeviceId(b.updatedBy), b.deleted, b.dirty),
                )
            },
            chapters = books.map { b ->
                ChapterListRecord(
                    BookId(b.id),
                    db.books().chapters(b.id).map { it.toCore() },
                    SyncStamp(b.revision, b.updatedAt, DeviceId(b.updatedBy)),
                )
            },
            playback = db.playback().dirty().map { it.toRecord() },
        )
    }

    override suspend fun markPushed(changes: ChangeSet) {
        db.withTransaction {
            changes.books.forEach { db.books().markClean(it.id.value, it.stamp.revision) }
            changes.playback.forEach { db.playback().markClean(it.bookId.value, it.stamp.revision) }
        }
    }

    override suspend fun applyRemote(changes: ChangeSet, resolver: PlaybackConflictResolver) {
        db.withTransaction {
            for (remote in changes.playback) {
                val local = db.playback().get(remote.bookId.value)
                if (db.books().get(remote.bookId.value) == null) continue
                val winner = if (local == null) remote else resolver.resolve(local.toRecord(), remote, base = null).winner
                db.playback().upsert(
                    PlaybackStateEntity(
                        bookId = winner.bookId.value,
                        positionMs = winner.positionMs,
                        speed = winner.speed,
                        finished = winner.finished,
                        lastPlayedAt = winner.lastPlayedAt,
                        revision = maxOf(winner.stamp.revision, local?.revision ?: 0),
                        updatedAt = winner.stamp.updatedAt,
                        updatedBy = winner.stamp.updatedBy.value,
                        dirty = winner !== remote,
                    ),
                )
            }
            // Remote books and files require a download step that arrives with the backend.
        }
    }

    override suspend fun cursor(): SyncCursor? = settings.syncCursor()?.let(::SyncCursor)

    override suspend fun saveCursor(cursor: SyncCursor) = settings.setSyncCursor(cursor.value)

    private fun PlaybackStateEntity.toRecord() = PlaybackRecord(
        bookId = BookId(bookId),
        positionMs = positionMs,
        speed = speed,
        finished = finished,
        lastPlayedAt = lastPlayedAt,
        stamp = SyncStamp(revision, updatedAt, DeviceId(updatedBy), dirty = dirty),
    )
}
