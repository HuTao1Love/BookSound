package com.zyagodin.booksound.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A book in the library. [id] is the stable BookId that is also embedded in the m4b file, so the
 * row can be re-associated with its file after it is moved or the app is reinstalled.
 */
@Entity(
    tableName = "books",
    indices = [Index("file_uri"), Index("deleted")],
)
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val narrator: String?,
    val series: String?,
    @ColumnInfo(name = "series_index") val seriesIndex: String?,
    val year: String?,
    val genre: String?,
    val description: String?,
    val language: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    /** SAF document URI of the m4b inside the library folder. */
    @ColumnInfo(name = "file_uri") val fileUri: String,
    /** Path relative to the library root, for display only. */
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "file_size") val fileSize: Long,
    @ColumnInfo(name = "file_sha256") val fileSha256: String?,
    @ColumnInfo(name = "file_revision") val fileRevision: Int,
    @ColumnInfo(name = "file_modified") val fileModified: Long,
    /** Locally cached cover (regenerated from the m4b when missing). */
    @ColumnInfo(name = "cover_path") val coverPath: String?,
    @ColumnInfo(name = "cover_sha256") val coverSha256: String?,
    @ColumnInfo(name = "added_at") val addedAt: Long,
    /** Set when the file could not be found during the last scan. */
    @ColumnInfo(name = "missing_since") val missingSince: Long?,
    // --- sync stamp ---
    val revision: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by") val updatedBy: String,
    /** Removed from the library (tombstone). The file may still exist and is then ignored by scans. */
    val deleted: Boolean,
    val dirty: Boolean,
)

@Entity(
    tableName = "chapters",
    primaryKeys = ["book_id", "idx"],
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE)],
)
data class ChapterEntity(
    @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "idx") val index: Int,
    val title: String,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
)

@Entity(
    tableName = "playback_state",
    foreignKeys = [ForeignKey(entity = BookEntity::class, parentColumns = ["id"], childColumns = ["book_id"], onDelete = ForeignKey.CASCADE)],
)
data class PlaybackStateEntity(
    @PrimaryKey @ColumnInfo(name = "book_id") val bookId: String,
    @ColumnInfo(name = "position_ms") val positionMs: Long,
    val speed: Float,
    val finished: Boolean,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long?,
    val revision: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "updated_by") val updatedBy: String,
    val dirty: Boolean,
)

/** Book joined with its playback state for list display. */
data class BookWithState(
    @Embedded val book: BookEntity,
    @ColumnInfo(name = "position_ms") val positionMs: Long?,
    val finished: Boolean?,
    @ColumnInfo(name = "last_played_at") val lastPlayedAt: Long?,
    @ColumnInfo(name = "chapter_count") val chapterCount: Int,
)
