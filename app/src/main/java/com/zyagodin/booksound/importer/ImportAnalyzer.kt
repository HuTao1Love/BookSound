package com.zyagodin.booksound.importer

import android.content.Context
import android.net.Uri
import android.util.Log
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.metadata.AudioProbe
import com.zyagodin.booksound.core.metadata.CorruptedFileException
import com.zyagodin.booksound.core.metadata.UnsupportedFormatException
import com.zyagodin.booksound.core.model.EmbeddedPicture
import com.zyagodin.booksound.core.organize.ChapterPlanner
import com.zyagodin.booksound.core.organize.CoverCandidate
import com.zyagodin.booksound.core.organize.CoverOrigin
import com.zyagodin.booksound.core.organize.DraftPart
import com.zyagodin.booksound.core.organize.ImportDraft
import com.zyagodin.booksound.core.organize.ImportDraftBuilder
import com.zyagodin.booksound.core.organize.ImportSourceFile
import com.zyagodin.booksound.cover.CoverImages
import com.zyagodin.booksound.cover.CoverStore
import com.zyagodin.booksound.data.library.LibraryRepository
import com.zyagodin.booksound.data.library.metadata
import com.zyagodin.booksound.storage.DocumentStore
import com.zyagodin.booksound.storage.NotSeekableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/** Turns a user selection into an editable [ImportDraft] by reading every file's metadata. */
class ImportAnalyzer(
    private val context: Context,
    private val documents: DocumentStore,
    private val library: LibraryRepository,
    private val covers: CoverStore,
) {
    private data class Candidate(val uri: Uri, val name: String, val dir: List<String>, val size: Long)

    suspend fun analyze(session: ImportSession) = withContext(Dispatchers.IO) {
        val result = try {
            when (val selection = session.selection) {
                is ImportSelection.ExistingBook -> analyzeExisting(session, selection.bookId)
                is ImportSelection.Documents -> analyzeFiles(session, collectDocuments(session, selection), sourceNameOf(selection))
                is ImportSelection.Folder -> analyzeFiles(session, collectFolder(session, selection), sourceNameOf(selection))
            }
        } catch (e: SecurityException) {
            AnalysisState.Failed(AnalysisFailure.SOURCE_UNAVAILABLE)
        } catch (e: IOException) {
            AnalysisState.Failed(AnalysisFailure.SOURCE_UNAVAILABLE)
        }
        if (result is AnalysisState.Ready) {
            val candidates = result.draft.covers.mapNotNull { c ->
                CoverImages.normalize(c.picture.bytes)?.let { c.copy(picture = it) }
            }
            session.coverCandidates.value = candidates
            if (session.cover.value == null) {
                session.cover.value = candidates.firstOrNull()?.let { SelectedCover(it.picture, covers.draftFile(session.id, it.picture), it) }
            }
            if (session.form.value == null) session.form.value = result.draft.toForm()
            session.existingBookId = result.draft.embeddedBookId
        }
        session.analysis.value = result
    }

    // ---------------------------------------------------------------- sources

    private fun sourceNameOf(selection: ImportSelection): String = when (selection) {
        is ImportSelection.Folder -> runCatching {
            documents.rootDocumentId(selection.treeUri).substringAfterLast(':').substringAfterLast('/')
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Audiobook"
        is ImportSelection.Documents -> selection.uris.firstOrNull()?.let { documents.describe(it)?.first }
            ?.substringBeforeLast('.') ?: "Audiobook"
        is ImportSelection.ExistingBook -> ""
    }

    private fun collectDocuments(session: ImportSession, selection: ImportSelection.Documents): Pair<List<Candidate>, List<Candidate>> {
        val held = mutableListOf<Uri>()
        val audio = mutableListOf<Candidate>()
        val images = mutableListOf<Candidate>()
        for (uri in selection.uris) {
            val (name, size) = documents.describe(uri) ?: continue
            if (runCatching { documents.takePersistablePermission(uri, write = false) }.isSuccess) held += uri
            val c = Candidate(uri, name, emptyList(), size)
            if (isImage(name)) images += c else audio += c
        }
        session.heldPermissions = held
        return audio to images
    }

    private fun collectFolder(session: ImportSession, selection: ImportSelection.Folder): Pair<List<Candidate>, List<Candidate>> {
        val tree = selection.treeUri
        // Keep access if the activity goes away while the import is running.
        if (runCatching { documents.takePersistablePermission(tree, write = false) }.isSuccess) session.heldPermissions = listOf(tree)
        val audio = mutableListOf<Candidate>()
        val images = mutableListOf<Candidate>()
        documents.walk(tree, documents.rootDocumentId(tree)) { entry, path ->
            val c = Candidate(entry.uri, entry.name, path, entry.size)
            when {
                entry.name.startsWith(".") -> Unit
                isImage(entry.name) -> images += c
                isAudioName(entry.name) -> audio += c
            }
        }
        return audio to images
    }

    private fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    private fun isAudioName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AudioProbe.SUPPORTED_EXTENSIONS || ext in AudioProbe.KNOWN_UNSUPPORTED_EXTENSIONS
    }

    // ---------------------------------------------------------------- analysis

    private suspend fun analyzeFiles(
        session: ImportSession,
        found: Pair<List<Candidate>, List<Candidate>>,
        sourceName: String,
    ): AnalysisState {
        val (audio, images) = found
        if (audio.isEmpty()) return AnalysisState.Failed(AnalysisFailure.NO_AUDIO_FILES)
        val parsed = mutableListOf<ImportSourceFile>()
        val skipped = mutableListOf<SkippedFile>()
        audio.forEachIndexed { i, c ->
            coroutineContext.ensureActive()
            session.analysis.value = AnalysisState.Analyzing(i, audio.size, c.name)
            try {
                val result = documents.openSource(c.uri, c.name).use { AudioProbe.probe(it, c.name) }
                parsed += ImportSourceFile(c.uri.toString(), c.name, c.dir, c.size, result)
            } catch (e: UnsupportedFormatException) {
                skipped += SkippedFile(c.name, SkipReason.UNSUPPORTED, e.message)
            } catch (e: CorruptedFileException) {
                skipped += SkippedFile(c.name, SkipReason.CORRUPTED, e.message)
            } catch (e: NotSeekableException) {
                skipped += SkippedFile(c.name, SkipReason.UNREADABLE, e.message)
            } catch (e: IOException) {
                skipped += SkippedFile(c.name, SkipReason.UNREADABLE, e.message)
            } catch (e: SecurityException) {
                skipped += SkippedFile(c.name, SkipReason.UNREADABLE, e.message)
            } catch (e: RuntimeException) {
                Log.w(TAG, "Parser failure for ${c.name}", e)
                skipped += SkippedFile(c.name, SkipReason.CORRUPTED, e.message)
            }
        }
        if (parsed.isEmpty()) return AnalysisState.Failed(AnalysisFailure.ALL_FILES_FAILED, skipped)
        session.analysis.value = AnalysisState.Analyzing(audio.size, audio.size, null)

        val folderImages = images
            .filter { it.size in 1..MAX_IMAGE_BYTES }
            .take(MAX_FOLDER_IMAGES)
            .mapNotNull { img ->
                val bytes = documents.readBytes(img.uri, MAX_IMAGE_BYTES.toInt()) ?: return@mapNotNull null
                val mime = EmbeddedPicture.sniffMimeType(bytes) ?: return@mapNotNull null
                img.name to EmbeddedPicture(bytes, mime)
            }
        val draft = ImportDraftBuilder.build(sourceName, parsed, folderImages) { context.getString(R.string.chapter_number, it) }
        return AnalysisState.Ready(draft, parsed, skipped)
    }

    private suspend fun analyzeExisting(session: ImportSession, bookId: String): AnalysisState {
        val book = library.book(bookId) ?: return AnalysisState.Failed(AnalysisFailure.BOOK_NOT_FOUND)
        val uri = Uri.parse(book.fileUri)
        val name = book.relativePath.substringAfterLast('/')
        val parsed = try {
            documents.openSource(uri, name).use { AudioProbe.probe(it, name) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return AnalysisState.Failed(AnalysisFailure.SOURCE_UNAVAILABLE)
        }
        val chapters = library.chapters(bookId)
        val file = ImportSourceFile(book.fileUri, name, emptyList(), book.fileSize, parsed)
        val coverCandidates = buildList {
            book.coverPath?.let { File(it) }?.takeIf { it.exists() }?.readBytes()?.let { bytes ->
                EmbeddedPicture.sniffMimeType(bytes)?.let { add(CoverCandidate(EmbeddedPicture(bytes, it), CoverOrigin.EMBEDDED, name)) }
            } ?: parsed.cover?.let { add(CoverCandidate(it, CoverOrigin.EMBEDDED, name)) }
        }
        val draft = ImportDraft(
            metadata = book.metadata(),
            parts = listOf(DraftPart(book.fileUri, name, book.title, parsed.durationMs ?: book.durationMs, ChapterPlanner.marksOf(chapters))),
            covers = coverCandidates,
            sourceName = book.title,
            embeddedBookId = book.id,
        )
        session.existingBookId = book.id
        return AnalysisState.Ready(draft, listOf(file), emptyList())
    }

    private fun ImportDraft.toForm() = EditorForm(
        title = metadata.title,
        author = metadata.author.orEmpty(),
        narrator = metadata.narrator.orEmpty(),
        series = metadata.series.orEmpty(),
        seriesIndex = metadata.seriesIndex.orEmpty(),
        year = metadata.year.orEmpty(),
        description = metadata.description.orEmpty(),
        parts = parts,
    )

    companion object {
        private const val TAG = "ImportAnalyzer"
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        private const val MAX_IMAGE_BYTES = 15L * 1024 * 1024
        private const val MAX_FOLDER_IMAGES = 6
    }
}
