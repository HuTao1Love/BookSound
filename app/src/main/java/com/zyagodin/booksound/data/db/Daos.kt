package com.zyagodin.booksound.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query(
        """
        SELECT b.*, p.position_ms, p.finished, p.last_played_at,
               (SELECT COUNT(*) FROM chapters c WHERE c.book_id = b.id) AS chapter_count
        FROM books b LEFT JOIN playback_state p ON p.book_id = b.id
        WHERE b.deleted = 0
        """,
    )
    fun observeLibrary(): Flow<List<BookWithState>>

    @Query(
        """
        SELECT b.*, p.position_ms, p.finished, p.last_played_at,
               (SELECT COUNT(*) FROM chapters c WHERE c.book_id = b.id) AS chapter_count
        FROM books b LEFT JOIN playback_state p ON p.book_id = b.id
        WHERE b.id = :id
        """,
    )
    fun observeBook(id: String): Flow<BookWithState?>

    @Query("SELECT * FROM books WHERE deleted = 1 ORDER BY updated_at DESC")
    fun observeRemoved(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: String): BookEntity?

    @Query("SELECT * FROM books")
    suspend fun all(): List<BookEntity>

    @Query("SELECT * FROM books WHERE file_uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): BookEntity?

    @Upsert
    suspend fun upsert(book: BookEntity)

    @Query("UPDATE books SET deleted = :deleted, revision = revision + 1, updated_at = :now, updated_by = :device, dirty = 1 WHERE id = :id")
    suspend fun setDeleted(id: String, deleted: Boolean, now: Long, device: String)

    @Query("UPDATE books SET missing_since = :missingSince WHERE id = :id")
    suspend fun setMissing(id: String, missingSince: Long?)

    @Query("UPDATE books SET cover_path = :path WHERE id = :id")
    suspend fun setCoverPath(id: String, path: String?)

    @Query("UPDATE books SET file_uri = :uri, relative_path = :relativePath, file_size = :size, file_modified = :modified, missing_since = NULL WHERE id = :id")
    suspend fun updateLocation(id: String, uri: String, relativePath: String, size: Long, modified: Long)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deletePermanently(id: String)

    @Query("SELECT * FROM chapters WHERE book_id = :bookId ORDER BY idx")
    suspend fun chapters(bookId: String): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE book_id = :bookId ORDER BY idx")
    fun observeChapters(bookId: String): Flow<List<ChapterEntity>>

    @Query("DELETE FROM chapters WHERE book_id = :bookId")
    suspend fun deleteChapters(bookId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Query("SELECT * FROM books WHERE dirty = 1")
    suspend fun dirty(): List<BookEntity>

    @Query("UPDATE books SET dirty = 0 WHERE id = :id AND revision = :revision")
    suspend fun markClean(id: String, revision: Long)

    @Transaction
    suspend fun replaceBook(book: BookEntity, chapters: List<ChapterEntity>) {
        upsert(book)
        deleteChapters(book.id)
        insertChapters(chapters)
    }
}

@Dao
interface PlaybackDao {
    @Query("SELECT * FROM playback_state WHERE book_id = :bookId")
    suspend fun get(bookId: String): PlaybackStateEntity?

    @Query("SELECT * FROM playback_state WHERE book_id = :bookId")
    fun observe(bookId: String): Flow<PlaybackStateEntity?>

    @Upsert
    suspend fun upsert(state: PlaybackStateEntity)

    @Query("SELECT * FROM playback_state WHERE dirty = 1")
    suspend fun dirty(): List<PlaybackStateEntity>

    @Query("UPDATE playback_state SET dirty = 0 WHERE book_id = :bookId AND revision = :revision")
    suspend fun markClean(bookId: String, revision: Long)
}
