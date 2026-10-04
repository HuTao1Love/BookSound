package com.zyagodin.booksound.data.library

import android.net.Uri
import androidx.room.withTransaction
import com.zyagodin.booksound.core.model.Audiobook
import com.zyagodin.booksound.core.model.BookFile
import com.zyagodin.booksound.core.model.BookId
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.core.model.DeviceId
import com.zyagodin.booksound.core.model.LibraryEntry
import com.zyagodin.booksound.core.model.SyncStamp
import com.zyagodin.booksound.cover.CoverStore
import com.zyagodin.booksound.data.db.AppDatabase
import com.zyagodin.booksound.data.db.BookEntity
import com.zyagodin.booksound.data.db.BookWithState
import com.zyagodin.booksound.data.db.ChapterEntity
import com.zyagodin.booksound.data.db.PlaybackStateEntity
import com.zyagodin.booksound.data.settings.SettingsRepository
import com.zyagodin.booksound.storage.DocumentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** A library row ready for display. */
data class LibraryItem(
    val entry: LibraryEntry,
    val coverPath: String?,
    val isMissing: Boolean,
    val chapterCount: Int,
) {
    val id: String get() = entry.book.id.value
    val metadata: BookMetadata get() = entry.book.metadata
}

data class BookDetails(
    val item: LibraryItem,
    val chapters: List<Chapter>,
    val relativePath: String,
    val fileUri: Uri,
    val fileSize: Long,
    val speed: Float?,
)

class LibraryRepository(
    private val db: AppDatabase,
    private val documents: DocumentStore,
    private val covers: CoverStore,
    private val settings: SettingsRepository,
) {
    private val books = db.books()
    private val playback = db.playback()
    @Volatile private var deviceId: DeviceId? = null

    suspend fun device(): DeviceId = deviceId ?: settings.deviceId().also { deviceId = it }

    val library: Flow<List<LibraryItem>> = books.observeLibrary().map { rows -> rows.map { it.toItem() } }

    val removed: Flow<List<BookEntity>> = books.observeRemoved()

    fun observeDetails(bookId: String): Flow<BookDetails?> =
        combine(books.observeBook(bookId), books.observeChapters(bookId), playback.observe(bookId)) { row, chapters, state ->
            row?.let {
                BookDetails(
                    item = it.toItem(),
                    chapters = chapters.map { c -> c.toCore() },
                    relativePath = it.book.relativePath,
                    fileUri = Uri.parse(it.book.fileUri),
                    fileSize = it.book.fileSize,
                    speed = state?.speed,
                )
            }
        }

    suspend fun book(bookId: String): BookEntity? = books.get(bookId)

    suspend fun chapters(bookId: String): List<Chapter> = books.chapters(bookId).map { it.toCore() }

    suspend fun playbackState(bookId: String): PlaybackStateEntity? = playback.get(bookId)

    /** Persists the listening position. Called frequently by the player; cheap and idempotent. */
    suspend fun savePosition(bookId: String, positionMs: Long, speed: Float, finished: Boolean? = null, played: Boolean = true) {
        val now = System.currentTimeMillis()
        val device = device().value
        db.withTransaction {
            if (books.get(bookId) == null) return@withTransaction
            val old = playback.get(bookId)
            playback.upsert(
                PlaybackStateEntity(
                    bookId = bookId,
                    positionMs = positionMs.coerceAtLeast(0),
                    speed = speed,
                    finished = finished ?: old?.finished ?: false,
                    lastPlayedAt = if (played) now else old?.lastPlayedAt,
                    revision = (old?.revision ?: 0) + 1,
                    updatedAt = now,
                    updatedBy = device,
                    dirty = true,
                ),
            )
        }
    }

    suspend fun setFinished(bookId: String, finished: Boolean) {
        val book = books.get(bookId) ?: return
        val state = playback.get(bookId)
        val position = if (finished) book.durationMs else 0L
        savePosition(bookId, position, state?.speed ?: settings.current().defaultSpeed, finished, played = false)
    }

    /**
     * Removes a book from the library. The row stays as a tombstone (for future sync and so a
     * folder rescan does not re-add a file the user chose to keep). Returns false if the file
     * should have been deleted but could not be.
     */
    suspend fun remove(bookId: String, deleteFile: Boolean): Boolean = withContext(Dispatchers.IO) {
        val book = books.get(bookId) ?: return@withContext true
        var fileDeleted = true
        if (deleteFile) {
            fileDeleted = documents.delete(Uri.parse(book.fileUri))
            if (fileDeleted) books.setMissing(bookId, System.currentTimeMillis())
        }
        books.setDeleted(bookId, true, System.currentTimeMillis(), device().value)
        covers.delete(bookId)
        if (settings.current().lastBookId == bookId) settings.setLastBook(null)
        fileDeleted
    }

    /** Restores a removed book if its file still exists. */
    suspend fun restore(bookId: String): Boolean = withContext(Dispatchers.IO) {
        val book = books.get(bookId) ?: return@withContext false
        if (!documents.exists(Uri.parse(book.fileUri))) return@withContext false
        books.setDeleted(bookId, false, System.currentTimeMillis(), device().value)
        books.setMissing(bookId, null)
        true
    }

    /** Permanently forgets a removed book (does not touch any file). */
    suspend fun forget(bookId: String) {
        books.deletePermanently(bookId)
        covers.delete(bookId)
    }

    /** Inserts or replaces a book after a successful import or metadata edit. */
    suspend fun saveImportedBook(
        bookId: String,
        metadata: BookMetadata,
        durationMs: Long,
        chapters: List<Chapter>,
        fileUri: Uri,
        relativePath: String,
        fileSize: Long,
        fileSha256: String?,
        coverPath: String?,
        coverSha256: String?,
        keepAddedAt: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val device = device().value
        db.withTransaction {
            val old = books.get(bookId)
            val entity = BookEntity(
                id = bookId,
                title = metadata.title,
                author = metadata.author,
                narrator = metadata.narrator,
                series = metadata.series,
                seriesIndex = metadata.seriesIndex,
                year = metadata.year,
                genre = metadata.genre,
                description = metadata.description,
                language = metadata.language,
                durationMs = durationMs,
                fileUri = fileUri.toString(),
                relativePath = relativePath,
                fileSize = fileSize,
                fileSha256 = fileSha256,
                fileRevision = (old?.fileRevision ?: 0) + 1,
                fileModified = now,
                coverPath = coverPath,
                coverSha256 = coverSha256,
                addedAt = if (keepAddedAt && old != null) old.addedAt else now,
                missingSince = null,
                revision = (old?.revision ?: 0) + 1,
                updatedAt = now,
                updatedBy = device,
                deleted = false,
                dirty = true,
            )
            books.replaceBook(entity, chapters.map { ChapterEntity(bookId, it.index, it.title, it.startMs, it.endMs) })
        }
    }

    suspend fun allBooks(): List<BookEntity> = books.all()

    suspend fun findByUri(uri: String): BookEntity? = books.findByUri(uri)

    suspend fun updateLocation(bookId: String, uri: Uri, relativePath: String, size: Long, modified: Long) =
        books.updateLocation(bookId, uri.toString(), relativePath, size, modified)

    suspend fun setMissing(bookId: String, missingSince: Long?) = books.setMissing(bookId, missingSince)

    suspend fun setCoverPath(bookId: String, path: String?) = books.setCoverPath(bookId, path)

    // ------------------------------------------------------------------ mapping

    private fun BookWithState.toItem(): LibraryItem = LibraryItem(
        entry = LibraryEntry(
            book = book.toCore(),
            positionMs = positionMs ?: 0L,
            finished = finished ?: false,
            lastPlayedAt = lastPlayedAt,
        ),
        coverPath = book.coverPath,
        isMissing = book.missingSince != null,
        chapterCount = chapterCount,
    )
}

fun BookEntity.metadata() = BookMetadata(
    title = title, author = author, narrator = narrator, series = series, seriesIndex = seriesIndex,
    year = year, genre = genre, description = description, language = language,
)

fun BookEntity.toCore(chapters: List<Chapter> = emptyList()) = Audiobook(
    id = BookId(id),
    metadata = metadata(),
    durationMs = durationMs,
    chapters = chapters,
    file = BookFile(fileUri, fileSize, fileSha256, fileRevision),
    addedAt = addedAt,
    sync = SyncStamp(revision, updatedAt, DeviceId(updatedBy), deleted, dirty),
)

fun ChapterEntity.toCore() = Chapter(index, title, startMs, endMs)
