package com.zyagodin.booksound.ui.importer

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zyagodin.booksound.AppContainer
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.metadata.AudioContainer
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.ChapterMark
import com.zyagodin.booksound.core.model.SeriesIndex
import com.zyagodin.booksound.core.naming.FileNameSanitizer
import com.zyagodin.booksound.core.naming.LibraryLayout
import com.zyagodin.booksound.core.organize.ChapterPlanner
import com.zyagodin.booksound.core.organize.ConversionPlanner
import com.zyagodin.booksound.core.organize.ConversionStrategy
import com.zyagodin.booksound.cover.CoverSearchResult
import com.zyagodin.booksound.cover.OnlineCover
import com.zyagodin.booksound.data.settings.AppSettings
import com.zyagodin.booksound.importer.AnalysisState
import com.zyagodin.booksound.importer.ConflictPolicy
import com.zyagodin.booksound.importer.EditorForm
import com.zyagodin.booksound.importer.ImportRequest
import com.zyagodin.booksound.importer.ImportSession
import com.zyagodin.booksound.importer.OnlineCoverState
import com.zyagodin.booksound.importer.SelectedCover
import com.zyagodin.booksound.importer.SourcePart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class EditorUi(
    val expired: Boolean = false,
    val analysis: AnalysisState? = null,
    val form: EditorForm? = null,
    val cover: SelectedCover? = null,
    val online: OnlineCoverState = OnlineCoverState.Idle,
    val isEdit: Boolean = false,
    val destination: String = "",
    val strategy: ConversionStrategy = ConversionStrategy.TRANSCODE,
    val estimatedBytes: Long = 0,
    val totalDurationMs: Long = 0,
    val chapterCount: Int = 0,
    val bitrateKbps: Int = 64,
    val seriesIndexInvalid: Boolean = false,
    val downloadingCover: Boolean = false,
    val busy: Boolean = false,
)

/** Something at the destination needs the user's decision before importing. */
sealed interface ImportConflict {
    /** A different book (or a foreign file) already uses the target file name. */
    data class PathTaken(val existingTitle: String?, val existingBookId: String?, val path: String) : ImportConflict

    /** The selected file is a BookSound book that is already in the library. */
    data class AlreadyInLibrary(val existingTitle: String, val existingBookId: String) : ImportConflict
}

sealed interface ConfirmOutcome {
    data class Started(val jobId: String) : ConfirmOutcome
    data class NeedsDecision(val conflict: ImportConflict) : ConfirmOutcome
    data object LibraryUnavailable : ConfirmOutcome
}

class ImportEditorViewModel(
    private val container: AppContainer,
    private val context: Context,
    sessionId: String,
) : ViewModel() {
    private val session: ImportSession? = container.importSessions[sessionId]
    private val downloading = MutableStateFlow(false)
    private val busy = MutableStateFlow(false)

    val ui: StateFlow<EditorUi> = if (session == null) {
        flowOf(EditorUi(expired = true)).stateIn(viewModelScope, SharingStarted.Eagerly, EditorUi(expired = true))
    } else {
        combine(
            combine(session.analysis, session.form, session.cover, session.online) { a, f, c, o -> Quad(a, f, c, o) },
            container.settings.state,
            downloading,
            busy,
        ) { (analysis, form, cover, online), settings, isDownloading, isBusy ->
            buildUi(analysis, form, cover, online, settings, isDownloading, isBusy)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUi(analysis = session.analysis.value))
    }

    private data class Quad(val a: AnalysisState, val f: EditorForm?, val c: SelectedCover?, val o: OnlineCoverState)

    init {
        // No cover found in the files: look one up online right away so suggestions are ready.
        val s = session
        if (s != null) {
            viewModelScope.launch {
                s.analysis.collect { analysis ->
                    if (analysis is AnalysisState.Ready && !s.autoSearchDone && s.cover.value == null &&
                        container.settings.current().autoCoverSearch
                    ) {
                        s.autoSearchDone = true
                        val form = s.form.value ?: return@collect
                        searchOnline(form.title, form.author)
                    }
                }
            }
        }
    }

    private fun buildUi(
        analysis: AnalysisState,
        form: EditorForm?,
        cover: SelectedCover?,
        online: OnlineCoverState,
        settings: AppSettings,
        isDownloading: Boolean,
        isBusy: Boolean,
    ): EditorUi {
        val ready = analysis as? AnalysisState.Ready
        if (ready == null || form == null) return EditorUi(analysis = analysis, isEdit = session?.isEdit == true)
        val metadata = form.toMetadata(ready.draft.sourceName, ready.draft.metadata)
        val strategy = strategyFor(ready, form)
        val duration = form.parts.sumOf { it.durationMs }
        val inputBytes = form.parts.sumOf { p -> ready.files.firstOrNull { it.id == p.sourceId }?.sizeBytes ?: 0L }
        return EditorUi(
            analysis = analysis,
            form = form,
            cover = cover,
            online = online,
            isEdit = session?.isEdit == true,
            destination = LibraryLayout.pathFor(metadata, ready.draft.sourceName).relativePath,
            strategy = strategy,
            estimatedBytes = ConversionPlanner.estimateOutputBytes(strategy, inputBytes, duration, settings.encoderBitrateKbps, cover?.picture?.bytes?.size ?: 0),
            totalDurationMs = duration,
            chapterCount = ChapterPlanner.plan(form.parts).size,
            bitrateKbps = settings.encoderBitrateKbps,
            seriesIndexInvalid = !SeriesIndex.isValid(form.seriesIndex),
            downloadingCover = isDownloading,
            busy = isBusy,
        )
    }

    private fun strategyFor(ready: AnalysisState.Ready, form: EditorForm): ConversionStrategy {
        val parsed = form.parts.mapNotNull { p -> ready.files.firstOrNull { it.id == p.sourceId }?.parsed }
        return if (parsed.isEmpty()) ConversionStrategy.TRANSCODE else ConversionPlanner.plan(parsed)
    }

    // ---------------------------------------------------------------- editing

    fun update(change: (EditorForm) -> EditorForm) {
        session?.form?.update { it?.let(change) }
    }

    fun movePart(from: Int, to: Int) = update { f ->
        if (from !in f.parts.indices || to !in f.parts.indices) f
        else f.copy(parts = f.parts.toMutableList().apply { add(to, removeAt(from)) })
    }

    fun removePart(index: Int) = update { f ->
        if (f.parts.size <= 1) f else f.copy(parts = f.parts.filterIndexed { i, _ -> i != index })
    }

    fun renamePart(index: Int, title: String) = update { f ->
        f.copy(parts = f.parts.mapIndexed { i, p -> if (i == index) p.copy(title = title) else p })
    }

    fun renameChapter(partIndex: Int, chapterIndex: Int, title: String) = update { f ->
        f.copy(
            parts = f.parts.mapIndexed { i, p ->
                if (i != partIndex) p else p.copy(chapters = p.chapters.mapIndexed { ci, c -> if (ci == chapterIndex) ChapterMark(c.startMs, title) else c })
            },
        )
    }

    fun removeCover() {
        session?.cover?.value = null
    }

    fun searchOnline(title: String, author: String?) {
        val s = session ?: return
        s.online.value = OnlineCoverState.Loading
        viewModelScope.launch {
            s.online.value = when (val r = container.coverSearch.search(title, author)) {
                is CoverSearchResult.Found -> OnlineCoverState.Results(listOfNotNull(title, author).joinToString(" "), r.covers)
                CoverSearchResult.Offline -> OnlineCoverState.Offline
                CoverSearchResult.Failed -> OnlineCoverState.Failed
            }
        }
    }

    /** Downloads [cover] and makes it the selected cover. Returns false if it could not be used. */
    fun chooseOnline(cover: OnlineCover, onResult: (Boolean) -> Unit) {
        val s = session ?: return
        viewModelScope.launch {
            downloading.value = true
            val picture = container.coverSearch.download(cover)
            downloading.value = false
            if (picture != null) {
                s.cover.value = SelectedCover(picture, withContext(Dispatchers.IO) { container.covers.draftFile(s.id, picture) }, null)
            }
            onResult(picture != null)
        }
    }

    // ---------------------------------------------------------------- confirm

    /**
     * Validates the destination and enqueues the import. Without a [decision], any conflict is
     * returned to the UI instead of silently overwriting or renaming.
     */
    fun confirm(decision: Decision? = null, onOutcome: (ConfirmOutcome) -> Unit) {
        val s = session ?: return
        val ready = s.analysis.value as? AnalysisState.Ready ?: return
        val form = s.form.value ?: return
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) { prepare(s, ready, form, decision) }
                onOutcome(outcome)
            } finally {
                busy.value = false
            }
        }
    }

    enum class Decision { REPLACE, KEEP_BOTH }

    private suspend fun prepare(s: ImportSession, ready: AnalysisState.Ready, form: EditorForm, decision: Decision?): ConfirmOutcome {
        val settings = container.settings.current()
        val tree = settings.libraryTreeUri?.let(Uri::parse) ?: return ConfirmOutcome.LibraryUnavailable
        if (!container.documents.hasPersistedPermission(tree, write = true)) return ConfirmOutcome.LibraryUnavailable

        val metadata = form.toMetadata(ready.draft.sourceName, ready.draft.metadata)
        val path = LibraryLayout.pathFor(metadata, ready.draft.sourceName)
        val editingId = if (s.isEdit) s.existingBookId else null
        val books = container.library.allBooks().filter { !it.deleted }

        var bookId = editingId ?: UUID.randomUUID().toString()
        var replacesBookId: String? = editingId
        var policy = ConflictPolicy.KEEP_BOTH

        // Re-importing a file BookSound produced: same identity, ask before duplicating it.
        val embedded = s.existingBookId
        if (!s.isEdit && embedded != null) {
            val existing = books.firstOrNull { it.id == embedded }
            if (existing == null) {
                bookId = embedded // restores identity, e.g. after reinstalling the app
            } else when (decision) {
                null -> return ConfirmOutcome.NeedsDecision(ImportConflict.AlreadyInLibrary(existing.title, existing.id))
                Decision.REPLACE -> {
                    bookId = existing.id
                    replacesBookId = existing.id
                    policy = ConflictPolicy.REPLACE
                }
                Decision.KEEP_BOTH -> bookId = UUID.randomUUID().toString()
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
                    null -> return ConfirmOutcome.NeedsDecision(ImportConflict.PathTaken(owner?.title, owner?.id, path.relativePath))
                    Decision.REPLACE -> {
                        policy = ConflictPolicy.REPLACE
                        if (!s.isEdit) replacesBookId = owner?.id
                    }
                    Decision.KEEP_BOTH -> policy = ConflictPolicy.KEEP_BOTH
                }
            }
        }

        val files = ready.files.associateBy { it.id }
        val parts = form.parts.map { p ->
            val f = files[p.sourceId]
            SourcePart(Uri.parse(p.sourceId), p.displayName, f?.sizeBytes ?: 0L, p.durationMs, f?.parsed?.container ?: AudioContainer.UNKNOWN)
        }
        val strategy = strategyFor(ready, form)
        val cover = s.cover.value?.picture
        val inputBytes = parts.sumOf { it.sizeBytes.coerceAtLeast(0) }
        val request = ImportRequest(
            jobId = UUID.randomUUID().toString(),
            bookId = bookId,
            parts = parts,
            metadata = metadata,
            chapters = ChapterPlanner.plan(form.parts) { context.getString(R.string.chapter_number, it) },
            cover = cover,
            strategy = strategy,
            bitrateKbps = settings.encoderBitrateKbps,
            downmixToMono = settings.downmixToMono,
            conflictPolicy = policy,
            replacesBookId = replacesBookId,
            isEdit = s.isEdit,
            sourceName = ready.draft.sourceName,
            heldPermissions = s.heldPermissions,
            estimatedOutputBytes = ConversionPlanner.estimateOutputBytes(strategy, inputBytes, parts.sumOf { it.durationMs }, settings.encoderBitrateKbps, cover?.bytes?.size ?: 0),
        )
        val jobId = container.importManager.enqueue(request)
        // The job now owns the source permissions; drop the session.
        container.importSessions.remove(s.id)
        container.covers.clearDrafts(s.id)
        return ConfirmOutcome.Started(jobId)
    }

    private fun fileExists(tree: Uri, directories: List<String>, fileName: String): Boolean = runCatching {
        var docId = container.documents.rootDocumentId(tree)
        for (dir in directories) {
            val key = FileNameSanitizer.collisionKey(dir)
            docId = container.documents.children(tree, docId).firstOrNull { it.isDirectory && FileNameSanitizer.collisionKey(it.name) == key }?.documentId
                ?: return false
        }
        val key = FileNameSanitizer.collisionKey(fileName)
        container.documents.children(tree, docId).any { !it.isDirectory && FileNameSanitizer.collisionKey(it.name) == key }
    }.getOrDefault(false)

    /** Abandons the import and releases what the session held. */
    fun cancel() {
        val s = session ?: return
        container.importSessions.remove(s.id)
        container.covers.clearDrafts(s.id)
        s.heldPermissions.forEach { container.documents.releasePersistablePermission(it) }
    }
}

fun EditorForm.toMetadata(fallbackTitle: String, base: BookMetadata? = null): BookMetadata = BookMetadata(
    title = title.ifBlank { fallbackTitle },
    author = author,
    narrator = narrator,
    series = series,
    seriesIndex = seriesIndex.takeIf { series.isNotBlank() },
    year = year,
    description = description,
    genre = base?.genre,
    language = base?.language,
).normalized()
