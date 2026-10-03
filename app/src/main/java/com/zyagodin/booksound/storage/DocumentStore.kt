package com.zyagodin.booksound.storage

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.OpenableColumns
import android.system.Os
import com.zyagodin.booksound.core.io.FileChannelSource
import com.zyagodin.booksound.core.io.RandomAccessSource
import com.zyagodin.booksound.core.naming.FileNameSanitizer
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException

data class DocEntry(
    val uri: Uri,
    val documentId: String,
    val name: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
) {
    val isDirectory: Boolean get() = mimeType == Document.MIME_TYPE_DIR
}

/** Thrown when a document can't be opened for random access (e.g. a streaming-only provider). */
class NotSeekableException(name: String) : IOException("'$name' can't be read directly from its location")

/**
 * Thin, synchronous wrapper over the Storage Access Framework. All methods do blocking I/O and
 * must be called off the main thread.
 */
class DocumentStore(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    // ------------------------------------------------------------------ permissions

    fun takePersistablePermission(uri: Uri, write: Boolean) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or if (write) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0
        resolver.takePersistableUriPermission(uri, flags)
    }

    fun releasePersistablePermission(uri: Uri) {
        runCatching {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun hasPersistedPermission(treeUri: Uri, write: Boolean): Boolean =
        resolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission && (!write || it.isWritePermission) }

    // ------------------------------------------------------------------ queries

    fun rootDocumentId(treeUri: Uri): String = DocumentsContract.getTreeDocumentId(treeUri)

    fun documentUri(treeUri: Uri, documentId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)

    fun children(treeUri: Uri, parentDocumentId: String): List<DocEntry> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        return query(childrenUri) { c -> toEntry(treeUri, c) }
    }

    /** Recursively lists all documents below [rootDocumentId] (depth-first, bounded depth). */
    fun walk(treeUri: Uri, rootDocumentId: String, maxDepth: Int = 8, visit: (entry: DocEntry, path: List<String>) -> Unit) {
        fun recurse(docId: String, path: List<String>, depth: Int) {
            for (child in children(treeUri, docId)) {
                if (child.isDirectory) {
                    if (depth < maxDepth && !child.name.startsWith(".")) recurse(child.documentId, path + child.name, depth + 1)
                } else {
                    visit(child, path)
                }
            }
        }
        recurse(rootDocumentId, emptyList(), 0)
    }

    fun entry(treeUri: Uri, documentUri: Uri): DocEntry? =
        query(documentUri) { c -> toEntry(treeUri, c) }.firstOrNull()

    /** Name and size for any openable URI (tree document or single picked document). */
    fun describe(uri: Uri): Pair<String, Long>? = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) null
            else {
                val name = c.getString(0) ?: uri.lastPathSegment ?: "file"
                val size = if (c.isNull(1)) -1L else c.getLong(1)
                name to size
            }
        }
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    fun exists(uri: Uri): Boolean = describe(uri) != null

    // ------------------------------------------------------------------ mutations

    /** Finds a sub-directory by name (case-insensitively) or creates it. Returns its document ID. */
    fun ensureDirectory(treeUri: Uri, parentDocumentId: String, name: String): String {
        val key = FileNameSanitizer.collisionKey(name)
        children(treeUri, parentDocumentId).firstOrNull { it.isDirectory && FileNameSanitizer.collisionKey(it.name) == key }
            ?.let { return it.documentId }
        val parentUri = documentUri(treeUri, parentDocumentId)
        val created = DocumentsContract.createDocument(resolver, parentUri, Document.MIME_TYPE_DIR, name)
            ?: throw IOException("Can't create folder '$name'")
        return DocumentsContract.getDocumentId(created)
    }

    fun createFile(treeUri: Uri, parentDocumentId: String, name: String, mimeType: String): Uri {
        val parentUri = documentUri(treeUri, parentDocumentId)
        return DocumentsContract.createDocument(resolver, parentUri, mimeType, name)
            ?: throw IOException("Can't create file '$name'")
    }

    /** Renames a document; returns its (possibly new) URI. */
    fun rename(uri: Uri, newName: String): Uri =
        DocumentsContract.renameDocument(resolver, uri, newName) ?: throw IOException("Can't rename to '$newName'")

    fun delete(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (_: FileNotFoundException) {
        true // already gone
    } catch (_: IllegalArgumentException) {
        true
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalStateException) {
        false
    }

    /**
     * After a file was deleted, removes its parent folders (e.g. "Author/Series") if they became
     * empty, never going above the library root. Only applies to providers with path-like
     * document IDs (local and SD card storage); other providers are left untouched.
     */
    fun pruneEmptyDirectories(treeUri: Uri, deletedFileUri: Uri) {
        val rootId = runCatching { rootDocumentId(treeUri) }.getOrNull() ?: return
        var docId = runCatching { DocumentsContract.getDocumentId(deletedFileUri) }.getOrNull() ?: return
        repeat(4) {
            val parentId = docId.substringBeforeLast('/', "")
            if (parentId.isEmpty() || parentId == rootId || !parentId.startsWith(rootId)) return
            val empty = runCatching { children(treeUri, parentId).isEmpty() }.getOrDefault(false)
            if (!empty || !delete(documentUri(treeUri, parentId))) return
            docId = parentId
        }
    }

    // ------------------------------------------------------------------ I/O

    fun openSource(uri: Uri, displayName: String = uri.lastPathSegment ?: "file"): RandomAccessSource {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(displayName)
        if (pfd.statSize < 0) {
            pfd.close()
            throw NotSeekableException(displayName)
        }
        val stream = FileInputStream(pfd.fileDescriptor)
        return FileChannelSource(stream.channel) {
            runCatching { stream.close() }
            runCatching { pfd.close() }
        }
    }

    fun openForWrite(uri: Uri): ParcelFileDescriptor =
        resolver.openFileDescriptor(uri, "w") ?: throw FileNotFoundException(uri.toString())

    fun readBytes(uri: Uri, maxBytes: Int): ByteArray? = try {
        resolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytesCompat(maxBytes + 1)
            if (bytes.size > maxBytes) null else bytes
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** Free bytes on the file system that holds [pfd]. */
    fun freeBytes(pfd: ParcelFileDescriptor): Long? = try {
        val st = Os.fstatvfs(pfd.fileDescriptor)
        st.f_bavail * st.f_frsize
    } catch (_: Exception) {
        null
    }

    /** Human readable location such as "Internal storage / Audiobooks". */
    fun describeTree(treeUri: Uri, internalLabel: String, externalLabel: String): String {
        val docId = runCatching { rootDocumentId(treeUri) }.getOrNull() ?: return treeUri.toString()
        val volume = docId.substringBefore(':', "")
        val path = docId.substringAfter(':', docId)
        val volumeLabel = if (volume == "primary") internalLabel else if (volume.isNotEmpty()) externalLabel else ""
        return listOf(volumeLabel, path).filter { it.isNotEmpty() }.joinToString(" / ")
    }

    private fun <T> query(uri: Uri, map: (Cursor) -> T): List<T> {
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
        return resolver.query(uri, projection, null, null, null)?.use { c ->
            val out = ArrayList<T>(c.count)
            while (c.moveToNext()) out += map(c)
            out
        } ?: emptyList()
    }

    private fun toEntry(treeUri: Uri, c: Cursor): DocEntry {
        val docId = c.getString(0)
        return DocEntry(
            uri = documentUri(treeUri, docId),
            documentId = docId,
            name = c.getString(1) ?: docId,
            mimeType = c.getString(2) ?: "application/octet-stream",
            size = if (c.isNull(3)) -1 else c.getLong(3),
            lastModified = if (c.isNull(4)) 0 else c.getLong(4),
        )
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
