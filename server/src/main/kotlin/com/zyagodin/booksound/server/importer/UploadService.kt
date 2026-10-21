package com.zyagodin.booksound.server.importer

import com.zyagodin.booksound.api.ErrorCodes
import com.zyagodin.booksound.api.ImportFromUploadsRequest
import com.zyagodin.booksound.api.UploadCreateRequest
import com.zyagodin.booksound.api.UploadDto
import com.zyagodin.booksound.api.UploadKind
import com.zyagodin.booksound.api.UploadResultDto
import com.zyagodin.booksound.core.io.toHex
import com.zyagodin.booksound.core.naming.FileNameSanitizer
import com.zyagodin.booksound.server.auth.Principal
import com.zyagodin.booksound.server.db.Database
import com.zyagodin.booksound.server.db.long
import com.zyagodin.booksound.server.db.str
import com.zyagodin.booksound.server.library.LibraryException
import com.zyagodin.booksound.server.library.LibraryService
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID

/**
 * Files sent to the server in chunks, so a big book survives a dropped connection and the router's limits:
 * each chunk names its byte range, and a client asks how much arrived before it resumes.
 */
class UploadService(
    private val db: Database,
    private val dir: File,
    private val library: LibraryService,
    private val imports: ImportService,
) {
    private data class Row(val id: String, val userId: String, val fileName: String, val size: Long, val kind: UploadKind, val sha256: String?, val relativeDir: List<String>, val createdAt: Long)

    private fun partFile(id: String) = File(dir, "$id.part")

    fun create(principal: Principal, request: UploadCreateRequest): UploadDto {
        if (request.sizeBytes <= 0 || request.sizeBytes > MAX_FILE_BYTES) throw LibraryException(ErrorCodes.BAD_REQUEST, "Invalid size")
        val name = FileNameSanitizer.sanitize(request.fileName, 200) ?: throw LibraryException(ErrorCodes.BAD_REQUEST, "Invalid file name")
        val folders = request.relativeDir.mapNotNull { FileNameSanitizer.sanitize(it) }.take(8)
        if (dir.usableSpace in 1 until request.sizeBytes + SPACE_RESERVE) throw LibraryException(ErrorCodes.INSUFFICIENT_STORAGE, "Not enough disk space")
        val id = UUID.randomUUID().toString()
        db.tx {
            update(
                "INSERT INTO uploads (id, user_id, file_name, size_bytes, kind, sha256, relative_dir, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, principal.userId, name, request.sizeBytes, request.kind, request.sha256?.lowercase(), folders.joinToString("/"), System.currentTimeMillis(),
            )
        }
        partFile(id).createNewFile()
        return UploadDto(id, name, request.sizeBytes, 0, request.kind)
    }

    fun status(principal: Principal, id: String): UploadDto {
        val row = row(principal, id)
        return UploadDto(row.id, row.fileName, row.size, partFile(id).length(), row.kind)
    }

    /**
     * Appends the bytes of `Content-Range: bytes start-end/size`. A chunk must start where the received bytes end;
     * otherwise the caller gets the current length and resends from there.
     */
    fun append(principal: Principal, id: String, start: Long, endInclusive: Long, total: Long, body: InputStream): UploadDto {
        val row = row(principal, id)
        val file = partFile(id)
        if (total != row.size || endInclusive < start || endInclusive >= row.size || endInclusive - start + 1 > MAX_CHUNK_BYTES) {
            throw LibraryException(ErrorCodes.BAD_REQUEST, "Invalid range")
        }
        if (start != file.length()) throw UploadOffsetException(file.length())
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(start)
            val buffer = ByteArray(64 * 1024)
            var remaining = endInclusive - start + 1
            while (remaining > 0) {
                val n = body.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) break
                raf.write(buffer, 0, n)
                remaining -= n
            }
            // A short body leaves what arrived; the client asks for the length and continues.
            if (remaining > 0) raf.setLength(start + (endInclusive - start + 1 - remaining))
        }
        return UploadDto(row.id, row.fileName, row.size, file.length(), row.kind)
    }

    /** A complete [UploadKind.BOOK] upload goes into the library. */
    fun completeBook(principal: Principal, id: String): UploadResultDto {
        val row = row(principal, id)
        if (row.kind != UploadKind.BOOK) throw LibraryException(ErrorCodes.BAD_REQUEST, "Not a book upload")
        val file = checkComplete(row)
        val book = try {
            library.adoptUploadedBook(file, principal.userId, principal.isAdmin, principal.deviceId)
        } finally {
            forget(id)
        }
        return UploadResultDto(book = book.toDto(library.repo.chapters(book.id)))
    }

    /** Several complete [UploadKind.SOURCE] uploads become one import job. */
    fun importSources(principal: Principal, request: ImportFromUploadsRequest): JobRecord {
        if (request.uploadIds.isEmpty()) throw LibraryException(ErrorCodes.BAD_REQUEST, "No files")
        val rows = request.uploadIds.map { row(principal, it) }
        if (rows.any { it.kind != UploadKind.SOURCE }) throw LibraryException(ErrorCodes.BAD_REQUEST, "Not source uploads")
        rows.forEach(::checkComplete)
        val sourceDir = File(dir, "import-" + UUID.randomUUID()).apply { mkdirs() }
        val refs = rows.map { r ->
            val folder = r.relativeDir.fold(sourceDir) { d, name -> File(d, name) }.apply { mkdirs() }
            var target = File(folder, r.fileName)
            var n = 2
            while (target.exists()) target = File(folder, "${r.fileName.substringBeforeLast('.')} ($n).${r.fileName.substringAfterLast('.', "")}").also { n++ }
            if (!partFile(r.id).renameTo(target)) partFile(r.id).copyTo(target).also { partFile(r.id).delete() }
            forget(r.id)
            SourceFileRef(target.absolutePath, r.relativeDir)
        }
        val sourceName = request.sourceName.trim().ifEmpty { rows.first().fileName.substringBeforeLast('.') }
        val spec = ImportSpec(
            files = refs,
            sourceName = sourceName,
            edited = request.metadata,
            cleanupDir = sourceDir.absolutePath,
            addedBy = principal.userId,
        )
        return imports.enqueue(spec, request.metadata?.title?.takeIf { it.isNotBlank() } ?: sourceName)
    }

    fun delete(principal: Principal, id: String) {
        row(principal, id)
        forget(id)
    }

    /** Drops uploads nobody finished within two days. */
    fun cleanUp() {
        val cutoff = System.currentTimeMillis() - 2 * 24 * 3_600_000L
        val stale = db.tx { query("SELECT id FROM uploads WHERE created_at < ?", cutoff) { it.str("id")!! } }
        stale.forEach(::forget)
        val known = db.tx { query("SELECT id FROM uploads") { it.str("id")!! } }.toSet()
        dir.listFiles()?.forEach { f ->
            val id = f.name.removeSuffix(".part")
            val orphanPart = f.isFile && f.name.endsWith(".part") && id !in known
            val oldImportDir = f.isDirectory && f.name.startsWith("import-") && f.lastModified() < cutoff
            if (orphanPart || oldImportDir) f.deleteRecursively()
        }
    }

    private fun checkComplete(row: Row): File {
        val file = partFile(row.id)
        if (file.length() != row.size) throw LibraryException(ErrorCodes.BAD_REQUEST, "Upload incomplete: ${file.length()} of ${row.size} bytes")
        row.sha256?.let { expected ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 20)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            if (digest.digest().toHex() != expected) {
                forget(row.id)
                throw LibraryException(ErrorCodes.BAD_REQUEST, "Checksum mismatch; upload again")
            }
        }
        return file
    }

    private fun row(principal: Principal, id: String): Row = db.tx {
        queryOne("SELECT * FROM uploads WHERE id = ? AND user_id = ?", id, principal.userId) {
            Row(
                it.str("id")!!, it.str("user_id")!!, it.str("file_name")!!, it.long("size_bytes")!!, UploadKind.valueOf(it.str("kind")!!),
                it.str("sha256"), it.str("relative_dir")!!.split('/').filter(String::isNotEmpty), it.long("created_at")!!,
            )
        }
    } ?: throw LibraryException(ErrorCodes.NOT_FOUND, "No such upload")

    private fun forget(id: String) {
        partFile(id).delete()
        db.tx { update("DELETE FROM uploads WHERE id = ?", id) }
    }

    companion object {
        const val MAX_CHUNK_BYTES = 16L * 1024 * 1024
        private const val MAX_FILE_BYTES = 20L * 1024 * 1024 * 1024
        private const val SPACE_RESERVE = 200L * 1024 * 1024
    }
}

class UploadOffsetException(val received: Long) : Exception("Upload continues at $received")
