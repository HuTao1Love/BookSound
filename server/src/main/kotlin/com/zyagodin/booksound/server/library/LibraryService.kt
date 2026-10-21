package com.zyagodin.booksound.server.library

import com.zyagodin.booksound.api.BookDto
import com.zyagodin.booksound.api.ChapterDto
import com.zyagodin.booksound.api.EventDto
import com.zyagodin.booksound.api.EventTypes
import com.zyagodin.booksound.api.MetadataDto
import com.zyagodin.booksound.api.RescanResultDto
import com.zyagodin.booksound.api.toDto
import com.zyagodin.booksound.api.toModel
import com.zyagodin.booksound.core.io.CancellationSignal
import com.zyagodin.booksound.core.io.sha256
import com.zyagodin.booksound.core.library.AdoptedFile
import com.zyagodin.booksound.core.library.ContentId
import com.zyagodin.booksound.core.metadata.AudioContainer
import com.zyagodin.booksound.core.metadata.AudioProbe
import com.zyagodin.booksound.core.metadata.ParsedAudioFile
import com.zyagodin.booksound.core.metadata.mp4.Mp4TagSpec
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.core.model.EmbeddedPicture
import com.zyagodin.booksound.core.naming.LibraryLayout
import com.zyagodin.booksound.core.organize.ImportChecks
import com.zyagodin.booksound.server.events.Events
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.util.UUID

class LibraryException(val code: String, message: String) : Exception(message)

/**
 * Everything that changes the library folder: books added by imports and uploads, metadata rewritten
 * into the files, and scans that pick up files put into the folder by hand. One change at a time, so
 * two books never claim one file name.
 */
class LibraryService(
    val repo: LibraryRepository,
    val files: LibraryFiles,
    val covers: Covers,
    val events: Events,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val log = LoggerFactory.getLogger(LibraryService::class.java)
    private val writeLock = Any()

    /** Device id the server writes into stamps of changes it makes itself. */
    val serverDevice = "server"

    fun fileOf(book: BookRow): File = files.resolve(book.relativePath)

    /**
     * Puts a written and verified book file ([partial], in the library folder) in place and records it. Replaces the
     * book with the same id, if any, keeping its "added" date.
     */
    fun commitBook(
        partial: File,
        target: File,
        bookId: String,
        metadata: MetadataDto,
        chapters: List<Chapter>,
        durationMs: Long,
        sha256: String,
        cover: EmbeddedPicture?,
        addedBy: String?,
        updatedBy: String,
    ): BookRow = synchronized(writeLock) {
        val previous = repo.book(bookId)
        val previousFile = previous?.let { runCatching { fileOf(it) }.getOrNull() }
        // The name was picked while converting; another book may have taken it since.
        val placed = files.stillFree(target, previousFile)
        files.commit(partial, placed, previousFile)
        if (cover != null) covers.save(bookId, cover) else if (previous == null) covers.remove(bookId)
        val now = clock()
        val row = BookRow(
            id = bookId,
            metadata = metadata,
            durationMs = durationMs,
            relativePath = files.relativePathOf(placed),
            fileSize = placed.length(),
            fileModified = placed.lastModified(),
            sha256 = sha256,
            fileRevision = (previous?.fileRevision ?: 0) + 1,
            coverSha256 = cover?.let { Covers.sha256(it.bytes) } ?: previous?.coverSha256,
            addedAt = previous?.addedAt ?: now,
            addedBy = previous?.addedBy ?: addedBy,
            missingSince = null,
            revision = (previous?.revision ?: 0) + 1,
            updatedAt = now,
            updatedBy = updatedBy,
            deleted = false,
            seq = 0,
        )
        repo.saveBook(row, chapters.map { it.toDto() }).also { notify(bookId) }
    }

    /**
     * Takes a finished BookSound M4B (uploaded from a phone) into the library. A book already in the shared library
     * is replaced only by whoever added it, or an admin.
     */
    fun adoptUploadedBook(file: File, uploaderId: String, isAdmin: Boolean, deviceId: String): BookRow {
        val parsed = files.open(file).use { src ->
            try {
                AudioProbe.probe(src, file.name)
            } catch (e: Exception) {
                throw LibraryException("not_audiobook", e.message ?: "Unreadable file")
            }
        }
        if (parsed.container != AudioContainer.MP4 || parsed.stream?.codec != "aac" || !parsed.rewritable) {
            throw LibraryException("unsupported", "Not an AAC M4B file")
        }
        val bookId = parsed.bookId ?: throw LibraryException("bad_request", "The file has no BookSound id")
        val sha = files.open(file).use { sha256(it) }
        val existing = repo.book(bookId)?.takeIf { !it.deleted }
        if (existing != null && existing.sha256 == sha) {
            file.delete()
            return existing
        }
        if (existing != null && !isAdmin && existing.addedBy != uploaderId) {
            throw LibraryException("forbidden", "This book is in the library already; only whoever added it or an admin can replace its file")
        }
        val metadata = AdoptedFile.metadata(parsed, file.name, emptyList())
        val chapters = AdoptedFile.chapters(parsed, file.name, file.name, metadata.title)
        val target = files.targetFor(metadata, null, repo.book(bookId)?.let(::fileOf))
        val partial = files.partialFor(target, UUID.randomUUID().toString())
        file.copyTo(partial, overwrite = true)
        file.delete()
        val cover = parsed.cover?.let { Covers.normalize(it.bytes) }
        return commitBook(partial, target, bookId, metadata.toDto(), chapters, parsed.durationMs ?: 0, sha, cover, uploaderId, deviceId)
    }

    /** Rewrites a book's metadata into its file (which may move to a new path) and records the change. */
    fun updateMetadata(bookId: String, metadata: MetadataDto, cover: EmbeddedPicture?, by: String): BookDto {
        val book = repo.book(bookId)?.takeIf { !it.deleted } ?: throw LibraryException("not_found", "No such book")
        val clean = metadata.toModel().normalized()
        if (clean.title.isEmpty()) throw LibraryException("bad_request", "Title is required")
        val source = fileOf(book)
        if (!source.isFile) throw LibraryException("not_found", "The book's file is missing")
        val chapters = repo.chapters(bookId).map { it.toModel() }
        val newCover = cover ?: covers.file(bookId)?.readBytes()?.let { bytes -> EmbeddedPicture.sniffMimeType(bytes)?.let { EmbeddedPicture(bytes, it) } }
        val target = files.targetFor(clean, null, source)
        val partial = files.partialFor(target, UUID.randomUUID().toString())
        try {
            files.open(source).use { src ->
                files.writeTagged(src, partial, Mp4TagSpec(bookId, clean, chapters, newCover), CancellationSignal.NONE)
            }
            val sha = verify(partial, bookId, book.durationMs, chapters.size)
            return commitBook(partial, target, bookId, clean.toDto(), chapters, book.durationMs, sha, newCover, null, by)
                .toDto(repo.chapters(bookId))
        } finally {
            partial.delete()
        }
    }

    /** Parses a written book back; returns its SHA-256, or throws when it is not what was meant to be written. */
    fun verify(file: File, bookId: String, expectedDurationMs: Long, chapterCount: Int): String = files.open(file).use { src ->
        val parsed = try {
            AudioProbe.probe(src, file.name)
        } catch (e: Exception) {
            throw IOException("Verification failed: ${e.message}")
        }
        ImportChecks.writtenFileProblem(parsed, bookId, expectedDurationMs, chapterCount)?.let { throw IOException("Verification failed: $it") }
        sha256(src)
    }

    /** Removes a book for everyone: the file is deleted and clients get a tombstone. */
    fun delete(bookId: String, by: String) = synchronized(writeLock) {
        val book = repo.book(bookId) ?: throw LibraryException("not_found", "No such book")
        runCatching { fileOf(book) }.getOrNull()?.let { f ->
            f.delete()
            files.pruneEmptyDirectories(f.parentFile)
        }
        covers.remove(bookId)
        repo.markDeleted(bookId, clock(), by)
        notify(bookId)
    }

    /**
     * Brings the database in line with the library folder: finds books copied in by hand (by their embedded
     * id, or one derived from their content like the phone does), follows moved files and marks missing ones.
     */
    fun scan(): RescanResultDto {
        val now = clock()
        // Reading and hashing new files can take minutes for a big folder: it happens without the lock, so imports
        // finishing meanwhile aren't held up. Only recording what was found takes the lock.
        val known = repo.books(includeDeleted = true).associateBy { it.relativePath }
        val found = mutableListOf<FoundFile>()
        files.root.walkTopDown().onEnter { !it.name.startsWith(".") || it == files.root }.filter { it.isFile }.forEach { file ->
            if (LibraryLayout.isTemporaryName(file.name)) {
                if (now - file.lastModified() > STALE_TEMP_MS) file.delete()
                return@forEach
            }
            if (file.extension.lowercase() !in AdoptedFile.EXTENSIONS) return@forEach
            val relativePath = files.relativePathOf(file)
            val size = file.length()
            val modified = file.lastModified()
            val existing = known[relativePath]
            if (existing != null && existing.fileSize == size && existing.fileModified == modified) {
                found += FoundFile(file, relativePath, size, modified, existing.id, parsed = null, cover = null)
                return@forEach
            }
            try {
                val parsed = files.open(file).use { AudioProbe.probe(it, file.name) }
                if (parsed.container != AudioContainer.MP4) return@forEach
                val id = parsed.bookId ?: files.open(file).use { ContentId.of(it) }
                found += FoundFile(file, relativePath, size, modified, id, parsed, parsed.cover?.let { Covers.normalize(it.bytes) })
            } catch (e: Exception) {
                log.warn("Skipping {}: {}", relativePath, e.message)
            }
        }
        return synchronized(writeLock) { record(found, now) }
    }

    private class FoundFile(
        val file: File,
        val relativePath: String,
        val size: Long,
        val modified: Long,
        val id: String,
        /** Null when the file is the one already recorded at this path, unchanged. */
        val parsed: ParsedAudioFile?,
        val cover: EmbeddedPicture?,
    )

    /** Brings the books in line with what a scan found; the database is read again, as it may have changed meanwhile. */
    private fun record(found: List<FoundFile>, now: Long): RescanResultDto {
        val known = repo.books(includeDeleted = true)
        val byId = known.associateBy { it.id }
        val seen = HashSet<String>()
        var added = 0
        var relinked = 0
        for (f in found) {
            val knownBook = byId[f.id]
            if (f.parsed == null) {
                seen += f.id
                if (knownBook?.missingSince != null) repo.setLocation(f.id, f.relativePath, f.size, f.modified)
                continue
            }
            if (knownBook != null) {
                seen += f.id
                // A second copy of a book whose own file still exists is left alone.
                val ownFile = runCatching { fileOf(knownBook) }.getOrNull()
                if (knownBook.relativePath == f.relativePath || ownFile?.isFile != true) {
                    repo.setLocation(f.id, f.relativePath, f.size, f.modified)
                    if (knownBook.relativePath != f.relativePath) relinked++
                    notify(f.id)
                }
                continue
            }
            try {
                adopt(f.file, f.parsed, f.cover, f.id, f.relativePath, now)
                seen += f.id
                added++
            } catch (e: Exception) {
                log.warn("Skipping {}: {}", f.relativePath, e.message)
            }
        }

        var missing = 0
        for (book in known) {
            if (book.id in seen || book.deleted) continue
            if (runCatching { fileOf(book) }.getOrNull()?.isFile == true) continue
            if (book.missingSince == null) {
                repo.setMissing(book.id, now)
                notify(book.id)
            }
            missing++
        }
        return RescanResultDto(added, relinked, missing)
    }

    private fun adopt(file: File, parsed: ParsedAudioFile, cover: EmbeddedPicture?, id: String, relativePath: String, now: Long) {
        val folders = relativePath.split('/').dropLast(1)
        val metadata = AdoptedFile.metadata(parsed, file.name, folders)
        val chapters = AdoptedFile.chapters(parsed, relativePath, file.name, metadata.title)
        cover?.let { covers.save(id, it) }
        val row = BookRow(
            id = id,
            metadata = metadata.toDto(),
            durationMs = parsed.durationMs ?: 0,
            relativePath = relativePath,
            fileSize = file.length(),
            fileModified = file.lastModified(),
            sha256 = null,
            fileRevision = 1,
            coverSha256 = cover?.let { Covers.sha256(it.bytes) },
            addedAt = now,
            addedBy = null,
            missingSince = null,
            revision = 1,
            updatedAt = now,
            updatedBy = serverDevice,
            deleted = false,
            seq = 0,
        )
        repo.saveBook(row, chapters.map { it.toDto() })
        notify(id)
    }

    /** SHA-256 of a book's file, computed once and stored (books adopted by a scan have none yet). */
    fun ensureSha(book: BookRow): String? {
        book.sha256?.let { return it }
        val file = runCatching { fileOf(book) }.getOrNull()?.takeIf { it.isFile } ?: return null
        val sha = files.open(file).use { sha256(it) }
        repo.setSha(book.id, sha)
        return sha
    }

    private fun notify(bookId: String) = events.emit(EventDto(EventTypes.LIBRARY, bookId = bookId))

    companion object {
        private const val STALE_TEMP_MS = 60 * 60 * 1000L
    }
}
