package com.zyagodin.booksound.server.library

import com.zyagodin.booksound.api.ApiJson
import com.zyagodin.booksound.api.BookDto
import com.zyagodin.booksound.api.ChapterDto
import com.zyagodin.booksound.api.MetadataDto
import com.zyagodin.booksound.api.PlaybackDto
import com.zyagodin.booksound.api.PreferencesDto
import com.zyagodin.booksound.api.StampDto
import com.zyagodin.booksound.server.db.Database
import com.zyagodin.booksound.server.db.Tx
import com.zyagodin.booksound.server.db.bool
import com.zyagodin.booksound.server.db.int
import com.zyagodin.booksound.server.db.long
import com.zyagodin.booksound.server.db.str
import java.sql.ResultSet

/** A book as the server stores it. */
data class BookRow(
    val id: String,
    val metadata: MetadataDto,
    val durationMs: Long,
    val relativePath: String,
    val fileSize: Long,
    val fileModified: Long,
    val sha256: String?,
    val fileRevision: Int,
    val coverSha256: String?,
    val addedAt: Long,
    val addedBy: String?,
    val missingSince: Long?,
    val revision: Long,
    val updatedAt: Long,
    val updatedBy: String,
    val deleted: Boolean,
    val seq: Long,
) {
    fun toDto(chapters: List<ChapterDto>) = BookDto(
        id = id,
        metadata = metadata,
        durationMs = durationMs,
        sizeBytes = fileSize,
        sha256 = sha256,
        fileRevision = fileRevision,
        coverSha256 = coverSha256,
        addedAt = addedAt,
        // A book whose file disappeared is offered to nobody until it comes back.
        stamp = StampDto(revision, updatedAt, updatedBy, deleted || missingSince != null),
        chapters = chapters,
        relativePath = relativePath,
    )
}

data class PlaybackRow(val userId: String, val dto: PlaybackDto, val seq: Long)

/** Books, chapters, positions and preferences in the database; every change takes the next change number. */
class LibraryRepository(private val db: Database) {

    fun book(id: String): BookRow? = db.tx { queryOne("SELECT * FROM books WHERE id = ?", id, row = ::bookRow) }

    fun books(includeDeleted: Boolean = false): List<BookRow> = db.tx {
        query("SELECT * FROM books" + if (includeDeleted) "" else " WHERE deleted = 0 AND missing_since IS NULL", row = ::bookRow)
    }

    fun chapters(bookId: String): List<ChapterDto> = db.tx { chaptersOf(bookId) }

    /** Chapters of [bookIds] only. */
    fun chaptersOf(bookIds: Collection<String>): Map<String, List<ChapterDto>> {
        if (bookIds.isEmpty()) return emptyMap()
        return bookIds.chunked(500).flatMap { ids ->
            db.tx {
                query("SELECT * FROM chapters WHERE book_id IN (${ids.joinToString(",") { "?" }}) ORDER BY book_id, idx", *ids.toTypedArray()) {
                    it.str("book_id")!! to chapterRow(it)
                }
            }
        }.groupBy({ it.first }, { it.second })
    }


    fun dto(id: String): BookDto? = book(id)?.toDto(chapters(id))

    /** Inserts or replaces a book and its chapters. Returns the stored row. */
    fun saveBook(row: BookRow, chapters: List<ChapterDto>): BookRow = db.tx {
        val seq = nextSeq()
        update("DELETE FROM chapters WHERE book_id = ?", row.id)
        update(
            """INSERT INTO books (id, title, author, narrator, series, series_index, year, genre, description, language,
                 duration_ms, relative_path, file_size, file_modified, file_sha256, file_revision, cover_sha256, added_at, added_by,
                 missing_since, revision, updated_at, updated_by, deleted, seq)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(id) DO UPDATE SET title = excluded.title, author = excluded.author, narrator = excluded.narrator,
                 series = excluded.series, series_index = excluded.series_index, year = excluded.year, genre = excluded.genre,
                 description = excluded.description, language = excluded.language, duration_ms = excluded.duration_ms,
                 relative_path = excluded.relative_path, file_size = excluded.file_size, file_modified = excluded.file_modified,
                 file_sha256 = excluded.file_sha256, file_revision = excluded.file_revision, cover_sha256 = excluded.cover_sha256,
                 missing_since = excluded.missing_since, revision = excluded.revision, updated_at = excluded.updated_at,
                 updated_by = excluded.updated_by, deleted = excluded.deleted, seq = excluded.seq""",
            row.id, row.metadata.title, row.metadata.author, row.metadata.narrator, row.metadata.series, row.metadata.seriesIndex,
            row.metadata.year, row.metadata.genre, row.metadata.description, row.metadata.language,
            row.durationMs, row.relativePath, row.fileSize, row.fileModified, row.sha256, row.fileRevision, row.coverSha256,
            row.addedAt, row.addedBy, row.missingSince, row.revision, row.updatedAt, row.updatedBy, row.deleted, seq,
        )
        for (c in chapters) {
            update("INSERT INTO chapters (book_id, idx, title, start_ms, end_ms) VALUES (?, ?, ?, ?, ?)", row.id, c.index, c.title, c.startMs, c.endMs)
        }
        row.copy(seq = seq)
    }

    fun setLocation(id: String, relativePath: String, size: Long, modified: Long) = db.tx {
        update(
            "UPDATE books SET relative_path = ?, file_size = ?, file_modified = ?, missing_since = NULL, seq = ? WHERE id = ?",
            relativePath, size, modified, nextSeq(), id,
        )
    }

    fun setMissing(id: String, since: Long?) = db.tx {
        update("UPDATE books SET missing_since = ?, seq = ? WHERE id = ?", since, nextSeq(), id)
    }

    fun setSha(id: String, sha: String) = db.tx {
        update("UPDATE books SET file_sha256 = ? WHERE id = ?", sha, id)
    }

    fun markDeleted(id: String, now: Long, by: String) = db.tx {
        update("UPDATE books SET deleted = 1, revision = revision + 1, updated_at = ?, updated_by = ?, seq = ? WHERE id = ?", now, by, nextSeq(), id)
    }

    fun bookChanges(since: Long, limit: Int): List<BookRow> = db.tx {
        query("SELECT * FROM books WHERE seq > ? ORDER BY seq LIMIT ?", since, limit, row = ::bookRow)
    }

    // ------------------------------------------------------------------------------------ playback

    fun playback(userId: String, bookId: String): PlaybackDto? = db.tx {
        queryOne("SELECT * FROM playback WHERE user_id = ? AND book_id = ?", userId, bookId) { playbackRow(it).dto }
    }

    fun playbackOf(userId: String): Map<String, PlaybackDto> = db.tx {
        query("SELECT * FROM playback WHERE user_id = ? AND deleted = 0", userId) { playbackRow(it).dto }
    }.associateBy { it.bookId }

    fun savePlayback(userId: String, p: PlaybackDto): Long = db.tx {
        val seq = nextSeq()
        update(
            """INSERT INTO playback (user_id, book_id, position_ms, speed, finished, last_played_at, revision, updated_at, updated_by, deleted, seq)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
               ON CONFLICT(user_id, book_id) DO UPDATE SET position_ms = excluded.position_ms, speed = excluded.speed,
                 finished = excluded.finished, last_played_at = excluded.last_played_at, revision = excluded.revision,
                 updated_at = excluded.updated_at, updated_by = excluded.updated_by, deleted = excluded.deleted, seq = excluded.seq""",
            userId, p.bookId, p.positionMs, p.speed, p.finished, p.lastPlayedAt, p.stamp.revision, p.stamp.updatedAt, p.stamp.updatedBy,
            p.stamp.deleted, seq,
        )
        seq
    }

    fun touchPlayback(userId: String, bookId: String) = db.tx {
        update("UPDATE playback SET seq = ? WHERE user_id = ? AND book_id = ?", nextSeq(), userId, bookId)
    }

    /** Books someone has started and not finished. */
    fun booksInProgress(): Set<String> = db.tx {
        query("SELECT DISTINCT book_id FROM playback WHERE finished = 0 AND position_ms > 0 AND deleted = 0") { it.str("book_id")!! }
    }.toSet()

    fun playbackChanges(userId: String, since: Long, limit: Int): List<PlaybackRow> = db.tx {
        query("SELECT * FROM playback WHERE user_id = ? AND seq > ? ORDER BY seq LIMIT ?", userId, since, limit, row = ::playbackRow)
    }

    // --------------------------------------------------------------------------------- preferences

    fun preferences(userId: String): Pair<PreferencesDto, Long>? = db.tx {
        queryOne("SELECT body, seq FROM preferences WHERE user_id = ?", userId) {
            ApiJson.decodeFromString(PreferencesDto.serializer(), it.str("body")!!) to it.long("seq")!!
        }
    }

    fun savePreferences(userId: String, prefs: PreferencesDto) = db.tx {
        val seq = nextSeq()
        update(
            "INSERT INTO preferences (user_id, body, seq) VALUES (?, ?, ?) ON CONFLICT(user_id) DO UPDATE SET body = excluded.body, seq = excluded.seq",
            userId, ApiJson.encodeToString(PreferencesDto.serializer(), prefs), seq,
        )
    }

    // ------------------------------------------------------------------------------------- mapping

    private fun Tx.chaptersOf(bookId: String) = query("SELECT * FROM chapters WHERE book_id = ? ORDER BY idx", bookId, row = ::chapterRow)

    private fun chapterRow(rs: ResultSet) = ChapterDto(rs.int("idx")!!, rs.str("title")!!, rs.long("start_ms")!!, rs.long("end_ms")!!)

    private fun bookRow(rs: ResultSet) = BookRow(
        id = rs.str("id")!!,
        metadata = MetadataDto(
            title = rs.str("title")!!,
            author = rs.str("author"),
            narrator = rs.str("narrator"),
            series = rs.str("series"),
            seriesIndex = rs.str("series_index"),
            year = rs.str("year"),
            genre = rs.str("genre"),
            description = rs.str("description"),
            language = rs.str("language"),
        ),
        durationMs = rs.long("duration_ms")!!,
        relativePath = rs.str("relative_path")!!,
        fileSize = rs.long("file_size")!!,
        fileModified = rs.long("file_modified") ?: 0,
        sha256 = rs.str("file_sha256"),
        fileRevision = rs.int("file_revision")!!,
        coverSha256 = rs.str("cover_sha256"),
        addedAt = rs.long("added_at")!!,
        addedBy = rs.str("added_by"),
        missingSince = rs.long("missing_since"),
        revision = rs.long("revision")!!,
        updatedAt = rs.long("updated_at")!!,
        updatedBy = rs.str("updated_by")!!,
        deleted = rs.bool("deleted"),
        seq = rs.long("seq")!!,
    )

    private fun playbackRow(rs: ResultSet) = PlaybackRow(
        rs.str("user_id")!!,
        PlaybackDto(
            bookId = rs.str("book_id")!!,
            positionMs = rs.long("position_ms")!!,
            speed = rs.getDouble("speed").toFloat(),
            finished = rs.bool("finished"),
            lastPlayedAt = rs.long("last_played_at"),
            stamp = StampDto(rs.long("revision")!!, rs.long("updated_at")!!, rs.str("updated_by")!!, rs.bool("deleted")),
        ),
        rs.long("seq")!!,
    )
}
