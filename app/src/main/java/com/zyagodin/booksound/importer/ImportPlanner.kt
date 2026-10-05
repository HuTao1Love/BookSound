package com.zyagodin.booksound.importer

import android.content.Context
import android.net.Uri
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.metadata.AudioContainer
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.EmbeddedPicture
import com.zyagodin.booksound.core.naming.FileNameSanitizer
import com.zyagodin.booksound.core.naming.LibraryLayout
import com.zyagodin.booksound.core.organize.ChapterPlanner
import com.zyagodin.booksound.core.organize.ConversionPlanner
import com.zyagodin.booksound.core.organize.ConversionStrategy
import com.zyagodin.booksound.core.organize.DraftPart
import com.zyagodin.booksound.core.organize.ImportSourceFile
import com.zyagodin.booksound.data.library.LibraryRepository
import com.zyagodin.booksound.data.settings.SettingsRepository
import com.zyagodin.booksound.storage.DocumentStore
import java.util.UUID

/** Something at the destination needs the user's decision before importing. */
sealed interface ImportConflict {
    /** A different book (or a foreign file) already uses the target file name. */
    data class PathTaken(val existingTitle: String?, val existingBookId: String?, val path: String) : ImportConflict

    /** The selected file is a BookSound book that is already in the library. */
    data class AlreadyInLibrary(val existingTitle: String, val existingBookId: String) : ImportConflict
}

enum class ImportDecision { REPLACE, KEEP_BOTH }

sealed interface PlanOutcome {
    data class Ready(val request: ImportRequest) : PlanOutcome
    data class NeedsDecision(val conflict: ImportConflict) : PlanOutcome
    data object LibraryUnavailable : PlanOutcome
}

/**
 * Turns reviewed import data into an [ImportRequest]: picks the book id, checks the destination
 * for conflicts and estimates the output. Without a decision any conflict is returned instead of
 * silently overwriting or renaming. Shared by the import editor and automatic torrent imports.
 */
class ImportPlanner(
    private val context: Context,
    private val settings: SettingsRepository,
    private val documents: DocumentStore,
    private val library: LibraryRepository,
) {
    data class Input(
        val metadata: BookMetadata,
        /** Ordered parts with their final titles and embedded chapters. */
        val parts: List<DraftPart>,
        /** Parsed source files by id ([DraftPart.sourceId]). */
        val files: Map<String, ImportSourceFile>,
        val cover: EmbeddedPicture?,
        val sourceName: String,
        /** Editing an existing library book (keeps its id, progress and "added" date). */
        val isEdit: Boolean,
        /** BookSound id embedded in the source, or the edited book's id. */
        val existingBookId: String?,
        val heldPermissions: List<Uri>,
        /** Id for a new book; fixed by callers that may have to redo the same import. */
        val newBookId: String? = null,
        val torrentId: String? = null,
        val strictValidation: Boolean = false,
    )

    fun strategyFor(parts: List<DraftPart>, files: Map<String, ImportSourceFile>): ConversionStrategy {
        val parsed = parts.mapNotNull { files[it.sourceId]?.parsed }
        return if (parsed.isEmpty()) ConversionStrategy.TRANSCODE else ConversionPlanner.plan(parsed)
    }

    suspend fun plan(input: Input, decision: ImportDecision?): PlanOutcome {
        val current = settings.current()
        val tree = current.libraryTreeUri?.let(Uri::parse) ?: return PlanOutcome.LibraryUnavailable
        if (!documents.hasPersistedPermission(tree, write = true)) return PlanOutcome.LibraryUnavailable

        val metadata = input.metadata
        val path = LibraryLayout.pathFor(metadata, input.sourceName)
        val editingId = if (input.isEdit) input.existingBookId else null
        val books = library.allBooks().filter { !it.deleted }

        var bookId = editingId ?: input.newBookId ?: UUID.randomUUID().toString()
        var replacesBookId: String? = editingId
        var policy = ConflictPolicy.KEEP_BOTH

        // Re-importing a file BookSound produced: same identity, ask before duplicating it.
        val embedded = input.existingBookId
        if (!input.isEdit && embedded != null) {
            val existing = books.firstOrNull { it.id == embedded }
            if (existing == null) {
                bookId = embedded // restores identity, e.g. after reinstalling the app
            } else when (decision) {
                null -> return PlanOutcome.NeedsDecision(ImportConflict.AlreadyInLibrary(existing.title, existing.id))
                ImportDecision.REPLACE -> {
                    bookId = existing.id
                    replacesBookId = existing.id
                    policy = ConflictPolicy.REPLACE
                }
                ImportDecision.KEEP_BOTH -> bookId = input.newBookId ?: UUID.randomUUID().toString()
            }
        }

        if (policy != ConflictPolicy.REPLACE) {
            val key = FileNameSanitizer.collisionKey(path.relativePath)
            val sameBook = editingId?.let { id -> books.firstOrNull { it.id == id } }
            val owner = books.firstOrNull { FileNameSanitizer.collisionKey(it.relativePath) == key && it.id != editingId }
            val ownPath = sameBook != null && FileNameSanitizer.collisionKey(sameBook.relativePath) == key
            val taken = owner != null || (!ownPath && fileExists(tree, path.directories, path.fileName))
            if (taken) {
                when (decision) {
                    null -> return PlanOutcome.NeedsDecision(ImportConflict.PathTaken(owner?.title, owner?.id, path.relativePath))
                    ImportDecision.REPLACE -> {
                        policy = ConflictPolicy.REPLACE
                        if (!input.isEdit) replacesBookId = owner?.id
                    }
                    ImportDecision.KEEP_BOTH -> policy = ConflictPolicy.KEEP_BOTH
                }
            }
        }

        val parts = input.parts.map { p ->
            val f = input.files[p.sourceId]
            SourcePart(
                uri = Uri.parse(p.sourceId),
                displayName = p.displayName,
                sizeBytes = f?.sizeBytes ?: 0L,
                durationMs = p.durationMs,
                container = f?.parsed?.container ?: AudioContainer.UNKNOWN,
                sampleRate = f?.parsed?.stream?.sampleRate,
                channels = f?.parsed?.stream?.channels,
            )
        }
        val strategy = strategyFor(input.parts, input.files)
        val inputBytes = parts.sumOf { it.sizeBytes.coerceAtLeast(0) }
        val bitrate = ConversionPlanner.outputBitrateKbps(current.encoderBitrateKbps, input.parts.mapNotNull { input.files[it.sourceId] })
        return PlanOutcome.Ready(
            ImportRequest(
                jobId = UUID.randomUUID().toString(),
                bookId = bookId,
                parts = parts,
                metadata = metadata,
                chapters = ChapterPlanner.plan(input.parts) { context.getString(R.string.chapter_number, it) },
                cover = input.cover,
                strategy = strategy,
                bitrateKbps = bitrate,
                downmixToMono = current.downmixToMono,
                conflictPolicy = policy,
                replacesBookId = replacesBookId,
                isEdit = input.isEdit,
                sourceName = input.sourceName,
                heldPermissions = input.heldPermissions,
                estimatedOutputBytes = ConversionPlanner.estimateOutputBytes(
                    strategy, inputBytes, parts.sumOf { it.durationMs }, bitrate, input.cover?.bytes?.size ?: 0,
                ),
                torrentId = input.torrentId,
                strictValidation = input.strictValidation,
            ),
        )
    }

    private fun fileExists(tree: Uri, directories: List<String>, fileName: String): Boolean = runCatching {
        var docId = documents.rootDocumentId(tree)
        for (dir in directories) {
            val key = FileNameSanitizer.collisionKey(dir)
            docId = documents.children(tree, docId).firstOrNull { it.isDirectory && FileNameSanitizer.collisionKey(it.name) == key }?.documentId
                ?: return false
        }
        val key = FileNameSanitizer.collisionKey(fileName)
        documents.children(tree, docId).any { !it.isDirectory && FileNameSanitizer.collisionKey(it.name) == key }
    }.getOrDefault(false)
}
