package com.zyagodin.booksound.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * A book in the library of the signed-in server, as last pulled. Books on the phone stay in `books`; this
 * is the catalog the "On the server" screen offers for download.
 */
@Entity(tableName = "server_books")
data class ServerBookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val narrator: String?,
    val series: String?,
    @ColumnInfo(name = "series_index") val seriesIndex: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "file_revision") val fileRevision: Int,
    @ColumnInfo(name = "cover_sha256") val coverSha256: String?,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    /** The book as the server sent it (metadata and chapters), JSON of `BookDto`. */
    val body: String,
)

/**
 * The listening position the server has for a book, as last pushed or pulled. A local position that differs
 * is pushed; for books not on the phone it is the progress taken over when the book is downloaded.
 */
@Entity(tableName = "server_playback")
data class ServerPlaybackEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    val speed: Float,
    val finished: Boolean,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long?,
    val revision: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by") val updatedBy: String,
)

@Dao
interface ServerDao {
    @Query("SELECT * FROM server_books")
    fun observeBooks(): Flow<List<ServerBookEntity>>

    @Query("SELECT * FROM server_books")
    suspend fun books(): List<ServerBookEntity>

    @Query("SELECT * FROM server_books WHERE id = :id")
    suspend fun book(id: String): ServerBookEntity?

    @Upsert
    suspend fun upsertBook(book: ServerBookEntity)

    @Query("DELETE FROM server_books WHERE id = :id")
    suspend fun deleteBook(id: String)

    @Query("SELECT * FROM server_playback")
    suspend fun playback(): List<ServerPlaybackEntity>

    @Query("SELECT * FROM server_playback")
    fun observePlayback(): Flow<List<ServerPlaybackEntity>>

    @Query("SELECT * FROM server_playback WHERE book_id = :bookId")
    suspend fun playback(bookId: String): ServerPlaybackEntity?

    @Upsert
    suspend fun upsertPlayback(state: ServerPlaybackEntity)

    @Query("DELETE FROM server_books")
    suspend fun clearBooks()

    @Query("DELETE FROM server_playback")
    suspend fun clearPlayback()
}
