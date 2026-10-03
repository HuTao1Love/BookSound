package com.zyagodin.booksound.data.library

import android.net.Uri
import android.util.Log
import com.zyagodin.booksound.core.io.readUpTo
import com.zyagodin.booksound.core.metadata.AudioContainer
import com.zyagodin.booksound.core.metadata.AudioProbe
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.ChapterMark
import com.zyagodin.booksound.core.naming.LibraryLayout
import com.zyagodin.booksound.core.organize.ChapterPlanner
import com.zyagodin.booksound.core.organize.DraftPart
import com.zyagodin.booksound.core.organize.NamePatternParser
import com.zyagodin.booksound.cover.CoverImages
import com.zyagodin.booksound.cover.CoverStore
import com.zyagodin.booksound.data.settings.SettingsRepository
import com.zyagodin.booksound.storage.DocEntry
import com.zyagodin.booksound.storage.DocumentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

sealed interface ScanResult {
    data class Done(val added: Int, val relinked: Int, val missing: Int, val failed: Int) : ScanResult
    data object NoLibraryFolder : ScanResult
    data object PermissionLost : ScanResult
}

/**
 * Reconciles the database with the library folder: finds books whose files were moved inside the
 * folder (by their embedded BookId), adopts m4b/m4a files copied there manually, marks missing
 * files and removes leftovers of interrupted imports.
 */
class LibraryScanner(
    private val documents: DocumentStore,
    private val library: LibraryRepository,
    private val covers: CoverStore,
    private val settings: SettingsRepository,
    private val activeImportIds: () -> Set<String>,
) {
    private val mutex = Mutex()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    suspend fun scan(): ScanResult = mutex.withLock {
        _scanning.value = true
        try {
            withContext(Dispatchers.IO) { doScan() }
        } finally {
            _scanning.value = false
        }
    }

    private suspend fun doScan(): ScanResult {
        val treeString = settings.current().libraryTreeUri ?: return ScanResult.NoLibraryFolder
        val tree = Uri.parse(treeString)
        if (!documents.hasPersistedPermission(tree, write = true)) return ScanResult.PermissionLost
        val rootId = runCatching { documents.rootDocumentId(tree) }.getOrElse { return ScanResult.PermissionLost }

        val files = mutableListOf<Pair<DocEntry, List<String>>>()
        try {
            documents.walk(tree, rootId) { entry, path -> files += entry to path }
        } catch (e: SecurityException) {
            return ScanResult.PermissionLost
        } catch (e: Exception) {
            Log.w(TAG, "Library walk failed", e)
            return ScanResult.PermissionLost
        }

        val active = activeImportIds()
        val now = System.currentTimeMillis()
        val known = library.allBooks()
        val byUri = known.associateBy { it.fileUri }
        val byId = known.associateBy { it.id }
        val seen = HashSet<String>()
        var added = 0
        var relinked = 0
        var failed = 0

        for ((entry, path) in files) {
            coroutineContext.ensureActive()
            if (LibraryLayout.isTemporaryName(entry.name)) {
                // Leftover of an interrupted import (the app was killed). Never touch running jobs.
                if (active.none { entry.name.contains(it) } && now - entry.lastModified > STALE_TEMP_MS) {
                    documents.delete(entry.uri)
                }
                continue
            }
            val ext = entry.name.substringAfterLast('.', "").lowercase()
            if (ext !in LIBRARY_EXTENSIONS) continue
            val relativePath = (path + entry.name).joinToString("/")

            val existing = byUri[entry.uri.toString()]
            if (existing != null) {
                seen += existing.id
                if (existing.missingSince != null || existing.fileSize != entry.size || existing.relativePath != relativePath) {
                    library.updateLocation(existing.id, entry.uri, relativePath, entry.size, entry.lastModified)
                }
                if (!existing.deleted && (existing.coverPath == null || !java.io.File(existing.coverPath).exists())) {
                    restoreCover(existing.id, entry)
                }
                continue
            }

            try {
                val parsed = documents.openSource(entry.uri, entry.name).use { AudioProbe.probe(it, entry.name) }
                if (parsed.container != AudioContainer.MP4) continue
                val embeddedId = parsed.bookId
                val knownBook = embeddedId?.let { byId[it] }
                if (knownBook != null) {
                    // Same book, moved/renamed inside the library folder. A second copy of a book whose
                    // original file still exists is ignored rather than added twice.
                    seen += knownBook.id
                    if (!documents.exists(Uri.parse(knownBook.fileUri))) {
                        library.updateLocation(knownBook.id, entry.uri, relativePath, entry.size, entry.lastModified)
                        relinked++
                    }
                    continue
                }
                val bookId = embeddedId ?: contentDerivedId(entry)
                if (byId[bookId] != null) {
                    seen += bookId
                    continue
                }
                val tags = parsed.tags
                val guess = NamePatternParser.parse(entry.name)
                val metadata = BookMetadata(
                    title = tags.title ?: tags.album ?: guess.title ?: entry.name.substringBeforeLast('.'),
                    author = tags.artist ?: tags.albumArtist ?: guess.author ?: path.firstOrNull()?.takeIf { it != LibraryLayout.UNKNOWN_AUTHOR },
                    narrator = tags.narrator ?: tags.composer,
                    series = tags.series ?: if (path.size >= 2) path[1] else null,
                    seriesIndex = tags.seriesPart ?: guess.seriesIndex,
                    year = tags.year,
                    genre = tags.genre,
                    description = tags.description ?: tags.comment,
                    language = tags.language,
                ).normalized()
                val duration = parsed.durationMs ?: 0L
                val chapters = ChapterPlanner.plan(
                    listOf(DraftPart(entry.uri.toString(), entry.name, metadata.title, duration, parsed.chapters.ifEmpty { listOf(ChapterMark(0, metadata.title)) })),
                )
                val cover = parsed.cover?.let { CoverImages.normalize(it.bytes) }
                val coverFile = cover?.let { covers.save(bookId, it) }
                library.saveImportedBook(
                    bookId = bookId,
                    metadata = metadata,
                    durationMs = duration,
                    chapters = chapters,
                    fileUri = entry.uri,
                    relativePath = relativePath,
                    fileSize = entry.size,
                    fileSha256 = null,
                    coverPath = coverFile?.absolutePath,
                    coverSha256 = cover?.let { CoverImages.sha256(it.bytes) },
                    keepAddedAt = false,
                )
                seen += bookId
                added++
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Skipping ${entry.name}", e)
                failed++
            }
        }

        var missing = 0
        for (book in known) {
            if (book.deleted || book.id in seen) continue
            if (documents.exists(Uri.parse(book.fileUri))) {
                // File exists outside the scanned tree (e.g. folder changed); keep it available.
                if (book.missingSince != null) library.setMissing(book.id, null)
                continue
            }
            if (book.missingSince == null) library.setMissing(book.id, now)
            missing++
        }
        return ScanResult.Done(added, relinked, missing, failed)
    }

    private suspend fun restoreCover(bookId: String, entry: DocEntry) {
        runCatching {
            val parsed = documents.openSource(entry.uri, entry.name).use { AudioProbe.probe(it, entry.name) }
            val cover = parsed.cover?.let { CoverImages.normalize(it.bytes) } ?: return
            library.setCoverPath(bookId, covers.save(bookId, cover).absolutePath)
        }
    }

    /** Stable id for files without an embedded BookSound id: derived from size and leading content. */
    private fun contentDerivedId(entry: DocEntry): String {
        val digest = MessageDigest.getInstance("SHA-256")
        documents.openSource(entry.uri, entry.name).use { source ->
            digest.update(source.readUpTo(0, 4 * 1024 * 1024))
            digest.update(source.size.toString().toByteArray())
        }
        return UUID.nameUUIDFromBytes(digest.digest()).toString()
    }

    companion object {
        private const val TAG = "LibraryScanner"
        private const val STALE_TEMP_MS = 60 * 60 * 1000L
        val LIBRARY_EXTENSIONS = setOf("m4b", "m4a")
    }
}
