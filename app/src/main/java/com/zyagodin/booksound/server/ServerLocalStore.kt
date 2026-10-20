package com.zyagodin.booksound.server

import androidx.room.withTransaction
import com.zyagodin.booksound.api.ApiJson
import com.zyagodin.booksound.api.BookDto
import com.zyagodin.booksound.api.toDto
import com.zyagodin.booksound.api.toModel
import com.zyagodin.booksound.core.model.BookId
import com.zyagodin.booksound.core.model.DeviceId
import com.zyagodin.booksound.core.model.SyncStamp
import com.zyagodin.booksound.core.sync.BookRecord
import com.zyagodin.booksound.core.sync.ChangeSet
import com.zyagodin.booksound.core.sync.ChapterListRecord
import com.zyagodin.booksound.core.sync.FileDescriptorRecord
import com.zyagodin.booksound.core.sync.PlaybackConflictResolver
import com.zyagodin.booksound.core.sync.PlaybackRecord
import com.zyagodin.booksound.core.sync.SyncCursor
import com.zyagodin.booksound.core.sync.SyncLocalStore
import com.zyagodin.booksound.data.db.AppDatabase
import com.zyagodin.booksound.data.db.PlaybackStateEntity
import com.zyagodin.booksound.data.db.ServerBookEntity
import com.zyagodin.booksound.data.db.ServerPlaybackEntity
import com.zyagodin.booksound.data.library.metadata
import com.zyagodin.booksound.data.library.toCore
import com.zyagodin.booksound.shared.server.ServerAccountStore

/**
 * The phone's side of syncing with the server. It keeps its own record of what the server has (the catalog and
 * the last agreed positions) instead of the `dirty` flags, which the watch sync clears: a position goes to the
 * server whenever it differs from what the server last had, whoever changed it.
 */
class ServerLocalStore(
    private val db: AppDatabase,
    private val accounts: ServerAccountStore,
) : SyncLocalStore {
    private val server get() = db.server()

    override suspend fun dirtyChanges(): ChangeSet {
        val known = server.playback().associateBy { it.bookId }
        val playback = db.playback().all().filter { local ->
            val remote = known[local.bookId]
            remote == null || remote.revision != local.revision || remote.updatedAt != local.updatedAt || remote.positionMs != local.positionMs ||
                remote.finished != local.finished || remote.speed != local.speed
        }.filter { server.book(it.bookId) != null }

        // Details edited on this phone after the server's copy was written go back to the server.
        val catalog = server.books().associateBy { it.id }
        val edited = db.books().all().filter { b ->
            val remote = catalog[b.id] ?: return@filter false
            !b.deleted && b.updatedAt > remote.updatedAt && b.metadata() != decode(remote).metadata.toModel()
        }
        return ChangeSet(
            books = edited.map { b ->
                BookRecord(
                    id = BookId(b.id),
                    metadata = b.metadata(),
                    durationMs = b.durationMs,
                    file = FileDescriptorRecord(b.fileSize, b.fileSha256, b.fileRevision),
                    coverSha256 = b.coverSha256,
                    addedAt = b.addedAt,
                    stamp = SyncStamp(b.revision, b.updatedAt, DeviceId(b.updatedBy), b.deleted),
                )
            },
            chapters = edited.map { b -> ChapterListRecord(BookId(b.id), db.books().chapters(b.id).map { it.toCore() }, SyncStamp(b.revision, b.updatedAt, DeviceId(b.updatedBy))) },
            playback = playback.map { it.toRecord() },
        )
    }

    override suspend fun markPushed(changes: ChangeSet) {
        db.withTransaction {
            for (p in changes.playback) server.upsertPlayback(p.toServerEntity())
        }
    }

    override suspend fun applyRemote(changes: ChangeSet, resolver: PlaybackConflictResolver) {
        val chapters = changes.chapters.associate { it.bookId.value to it.chapters }
        db.withTransaction {
            for (book in changes.books) {
                if (book.stamp.deleted) {
                    server.deleteBook(book.id.value)
                    continue
                }
                val dto = book.toDto(chapters[book.id.value].orEmpty())
                server.upsertBook(
                    ServerBookEntity(
                        id = dto.id,
                        title = dto.metadata.title,
                        author = dto.metadata.author,
                        narrator = dto.metadata.narrator,
                        series = dto.metadata.series,
                        seriesIndex = dto.metadata.seriesIndex,
                        durationMs = dto.durationMs,
                        sizeBytes = dto.sizeBytes,
                        fileRevision = dto.fileRevision,
                        coverSha256 = dto.coverSha256,
                        updatedAt = dto.stamp.updatedAt,
                        body = ApiJson.encodeToString(BookDto.serializer(), dto),
                    ),
                )
            }
            for (remote in changes.playback) {
                server.upsertPlayback(remote.toServerEntity())
                if (db.books().get(remote.bookId.value) == null) continue
                val local = db.playback().get(remote.bookId.value)
                val localRecord = local?.toRecord()
                val winner = if (localRecord == null) remote else resolver.resolve(localRecord, remote, base = null).winner
                if (winner == localRecord) continue
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
                        // A position from the server goes on to the watch.
                        dirty = true,
                    ),
                )
            }
        }
    }

    override suspend fun cursor(): SyncCursor? = accounts.cursor?.let(::SyncCursor)

    override suspend fun saveCursor(cursor: SyncCursor) {
        accounts.cursor = cursor.value
    }

    /** Forgets everything about the server (signed out). */
    suspend fun clear() = db.withTransaction {
        server.clearBooks()
        server.clearPlayback()
    }

    private fun decode(entity: ServerBookEntity): BookDto = ApiJson.decodeFromString(BookDto.serializer(), entity.body)

    private fun PlaybackStateEntity.toRecord() = PlaybackRecord(
        bookId = BookId(bookId),
        positionMs = positionMs,
        speed = speed,
        finished = finished,
        lastPlayedAt = lastPlayedAt,
        stamp = SyncStamp(revision, updatedAt, DeviceId(updatedBy), dirty = dirty),
    )

    private fun PlaybackRecord.toServerEntity() = ServerPlaybackEntity(
        bookId = bookId.value,
        positionMs = positionMs,
        speed = speed,
        finished = finished,
        lastPlayedAt = lastPlayedAt,
        revision = stamp.revision,
        updatedAt = stamp.updatedAt,
        updatedBy = stamp.updatedBy.value,
    )
}
