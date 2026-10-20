package com.zyagodin.booksound.importer

import android.content.Context
import android.net.Uri
import android.util.Log
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.metadata.AudioProbe
import com.zyagodin.booksound.core.model.EmbeddedPicture
import com.zyagodin.booksound.core.organize.ChapterPlanner
import com.zyagodin.booksound.core.organize.CoverCandidate
import com.zyagodin.booksound.core.organize.CoverOrigin
import com.zyagodin.booksound.core.organize.DraftPart
import com.zyagodin.booksound.core.organize.ImportDraft
import com.zyagodin.booksound.core.organize.ImportSourceFile
import com.zyagodin.booksound.core.organize.SourceAnalysis
import com.zyagodin.booksound.core.organize.SourceAnalyzer
import com.zyagodin.booksound.core.organize.SourceCandidate
import com.zyagodin.booksound.cover.CoverImages
import com.zyagodin.booksound.cover.CoverStore
import com.zyagodin.booksound.data.library.LibraryRepository
import com.zyagodin.booksound.data.library.metadata
import com.zyagodin.booksound.storage.DocumentStore
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
                is ImportSelection.Documents -> analyzeFiles(collectDocuments(session, selection), sourceNameOf(selection)) {
                    session.analysis.value = it
                }
                is ImportSelection.Folder -> analyzeFiles(collectFolder(session, selection), sourceNameOf(selection)) {
                    session.analysis.value = it
                }
                is ImportSelection.Torrent -> error("Torrent sessions are loaded by TorrentManager")
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

    /**
     * Reads downloaded files (with their folders below the download root) without a review session. Unlike the
     * interactive import, the draft's cover candidates are not normalized here.
     */
    suspend fun analyzeLocalFiles(files: List<Pair<File, List<String>>>, sourceName: String): AnalysisState = withContext(Dispatchers.IO) {
        val audio = mutableListOf<Candidate>()
        val images = mutableListOf<Candidate>()
        for ((file, relativeDir) in files) {
            val c = Candidate(Uri.fromFile(file), file.name, relativeDir, file.length())
            if (isImage(file.name)) images += c else audio += c
        }
        try {
            analyzeFiles(audio to images, sourceName) {}
        } catch (e: IOException) {
            AnalysisState.Failed(AnalysisFailure.SOURCE_UNAVAILABLE)
        }
    }

    // ---------------------------------------------------------------- sources

    private fun sourceNameOf(selection: ImportSelection): String = when (selection) {
        is ImportSelection.Folder -> runCatching {
            documents.rootDocumentId(selection.treeUri).substringAfterLast(':').substringAfterLast('/')
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Audiobook"
        is ImportSelection.Documents -> selection.uris.firstOrNull()?.let { documents.describe(it)?.first }
            ?.substringBeforeLast('.') ?: "Audiobook"
        is ImportSelection.ExistingBook, is ImportSelection.Torrent -> ""
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

    private fun isImage(name: String) = SourceAnalyzer.isImage(name)

    private fun isAudioName(name: String) = SourceAnalyzer.isAudioName(name)

    // ---------------------------------------------------------------- analysis

    private suspend fun analyzeFiles(
        found: Pair<List<Candidate>, List<Candidate>>,
        sourceName: String,
        report: (AnalysisState.Analyzing) -> Unit,
    ): AnalysisState {
        val (audio, images) = found
        val byId = (audio + images).associateBy { it.uri.toString() }
        val coroutine = coroutineContext
        val result = SourceAnalyzer.analyze(
            audio = audio.map { it.toSource() },
            images = images.map { it.toSource() },
            sourceName = sourceName,
            open = { documents.openSource(byId.getValue(it.id).uri, it.name) },
            readImage = { img, max -> documents.readBytes(byId.getValue(img.id).uri, max) },
            untitledPart = { context.getString(R.string.chapter_number, it) },
            onFileFailed = { c, e -> Log.w(TAG, "Parser failure for ${c.name}", e) },
            beforeFile = { i, c ->
                coroutine.ensureActive()
                report(AnalysisState.Analyzing(i, audio.size, c.name))
            },
        )
        return when (result) {
            SourceAnalysis.NoAudio -> AnalysisState.Failed(AnalysisFailure.NO_AUDIO_FILES)
            is SourceAnalysis.NothingReadable -> AnalysisState.Failed(AnalysisFailure.ALL_FILES_FAILED, result.skipped)
            is SourceAnalysis.Ready -> {
                report(AnalysisState.Analyzing(audio.size, audio.size, null))
                AnalysisState.Ready(result.draft, result.files, result.skipped)
            }
        }
    }

    private fun Candidate.toSource() = SourceCandidate(uri.toString(), name, dir, size)

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
    }
}
