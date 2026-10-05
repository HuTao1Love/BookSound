package com.zyagodin.booksound.ui.importer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zyagodin.booksound.AppContainer
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.ChapterMark
import com.zyagodin.booksound.core.model.EmbeddedPicture
import com.zyagodin.booksound.core.model.SeriesIndex
import com.zyagodin.booksound.core.naming.LibraryLayout
import com.zyagodin.booksound.core.organize.ChapterPlanner
import com.zyagodin.booksound.core.organize.ConversionPlanner
import com.zyagodin.booksound.core.organize.ConversionStrategy
import com.zyagodin.booksound.core.organize.NameField
import com.zyagodin.booksound.core.organize.NameTemplate
import com.zyagodin.booksound.core.torrent.AudiobookLayout
import com.zyagodin.booksound.cover.CoverSearchRepository
import com.zyagodin.booksound.cover.CoverSearchResult
import com.zyagodin.booksound.cover.OnlineCover
import com.zyagodin.booksound.data.settings.AppSettings
import com.zyagodin.booksound.importer.AnalysisState
import com.zyagodin.booksound.importer.EditorForm
import com.zyagodin.booksound.importer.ImportConflict
import com.zyagodin.booksound.importer.ImportDecision
import com.zyagodin.booksound.importer.ImportPlanner
import com.zyagodin.booksound.importer.ImportSession
import com.zyagodin.booksound.importer.OnlineCoverState
import com.zyagodin.booksound.importer.PlanOutcome
import com.zyagodin.booksound.importer.SelectedCover
import com.zyagodin.booksound.torrent.TorrentItem
import com.zyagodin.booksound.torrent.TorrentPhase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    /** Set when reviewing a torrent that is still downloading. */
    val torrent: TorrentEditorInfo? = null,
    /** Folder, file or torrent name the details are read from; empty when editing a book. */
    val sourceName: String = "",
    val templates: List<TemplateOption> = emptyList(),
    val appliedTemplate: String? = null,
)

/** A saved name template and what it reads from the current source name (null: doesn't fit). */
data class TemplateOption(val template: String, val fields: Map<NameField, String>?)

/** Download state shown while the user reviews a torrent. */
data class TorrentEditorInfo(
    val phase: TorrentPhase,
    val progress: Float,
    val downloadBytes: Long,
    /** Download size of each part, by [com.zyagodin.booksound.core.organize.DraftPart.sourceId]. */
    val partSizes: Map<String, Long>,
    val layout: AudiobookLayout?,
    /** The details were confirmed before; the editor now only saves changes. */
    val reviewed: Boolean,
    val online: Boolean,
)

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
    private val torrentId: String? = session?.torrentId
    private val downloading = MutableStateFlow(false)
    private val busy = MutableStateFlow(false)
    private val torrentItem = torrentId?.let { id -> container.torrents.items.map { list -> list.firstOrNull { it.id == id } } } ?: flowOf(null)

    val ui: StateFlow<EditorUi> = if (session == null) {
        flowOf(EditorUi(expired = true)).stateIn(viewModelScope, SharingStarted.Eagerly, EditorUi(expired = true))
    } else {
        combine(
            combine(session.analysis, session.form, session.cover, session.online) { a, f, c, o -> Quad(a, f, c, o) },
            combine(container.settings.state, session.appliedTemplate) { settings, applied -> settings to applied },
            downloading,
            busy,
            torrentItem,
        ) { (analysis, form, cover, online), (settings, applied), isDownloading, isBusy, torrent ->
            withTemplates(buildUi(analysis, form, cover, online, settings, isDownloading, isBusy, torrent), settings, applied)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUi(analysis = session.analysis.value))
    }

    private data class Quad(val a: AnalysisState, val f: EditorForm?, val c: SelectedCover?, val o: OnlineCoverState)

    init {
        if (session != null && torrentId != null) {
            // While the editor is open the torrent is not converted, and every change is saved
            // right away so nothing is lost if the app is closed or killed.
            container.torrents.beginEditing(torrentId)
            viewModelScope.launch { autosaveTorrentReview(session, torrentId) }
        }
    }

    init {
        // No cover found in the files: look one up online right away so suggestions are ready.
        val s = session
        if (s != null) {
            viewModelScope.launch {
                s.analysis.collect { analysis ->
                    if (analysis is AnalysisState.Ready && !s.templateChecked) {
                        s.templateChecked = true
                        autoApplyTemplate(s, analysis)
                    }
                    if (analysis is AnalysisState.Ready && !s.autoSearchDone && s.cover.value == null &&
                        container.settings.current().autoCoverSearch
                    ) {
                        s.autoSearchDone = true
                        val form = s.form.value ?: return@collect
                        searchOnline(form.title, form.author, form.series)
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
        torrent: TorrentItem?,
    ): EditorUi {
        val ready = analysis as? AnalysisState.Ready
        if (ready == null || form == null) return EditorUi(analysis = analysis, isEdit = session?.isEdit == true)
        val metadata = form.toMetadata(ready.draft.sourceName, ready.draft.metadata)
        if (torrentId != null) return buildTorrentUi(ready, form, metadata, cover, online, settings, isDownloading, isBusy, torrent)
        val strategy = strategyFor(ready, form)
        val duration = form.parts.sumOf { it.durationMs }
        val inputBytes = form.parts.sumOf { p -> ready.files.firstOrNull { it.id == p.sourceId }?.sizeBytes ?: 0L }
        val bitrate = ConversionPlanner.outputBitrateKbps(
            settings.encoderBitrateKbps,
            form.parts.mapNotNull { p -> ready.files.firstOrNull { it.id == p.sourceId } },
        )
        return EditorUi(
            analysis = analysis,
            form = form,
            cover = cover,
            online = online,
            isEdit = session?.isEdit == true,
            destination = LibraryLayout.pathFor(metadata, ready.draft.sourceName).relativePath,
            strategy = strategy,
            estimatedBytes = ConversionPlanner.estimateOutputBytes(strategy, inputBytes, duration, bitrate, cover?.picture?.bytes?.size ?: 0),
            totalDurationMs = duration,
            chapterCount = ChapterPlanner.plan(form.parts).size,
            bitrateKbps = bitrate,
            seriesIndexInvalid = !SeriesIndex.isValid(form.seriesIndex),
            downloadingCover = isDownloading,
            busy = isBusy,
        )
    }

    private fun buildTorrentUi(
        analysis: AnalysisState.Ready,
        form: EditorForm,
        metadata: BookMetadata,
        cover: SelectedCover?,
        online: OnlineCoverState,
        settings: AppSettings,
        isDownloading: Boolean,
        isBusy: Boolean,
        torrent: TorrentItem?,
    ): EditorUi {
        val record = torrent?.record
        val sizes = torrentId?.let { container.torrents.partSizes(it) }.orEmpty()
        val layout = record?.layout
        return EditorUi(
            analysis = analysis,
            form = form,
            cover = cover,
            online = online,
            destination = LibraryLayout.pathFor(metadata, analysis.draft.sourceName).relativePath,
            strategy = if (layout == AudiobookLayout.SINGLE_M4B) ConversionStrategy.REMUX_SINGLE else ConversionStrategy.TRANSCODE,
            estimatedBytes = form.parts.sumOf { sizes[it.sourceId] ?: 0L },
            chapterCount = if (layout == AudiobookLayout.SINGLE_M4B) 0 else form.parts.size,
            bitrateKbps = settings.encoderBitrateKbps,
            seriesIndexInvalid = !SeriesIndex.isValid(form.seriesIndex),
            downloadingCover = isDownloading,
            busy = isBusy,
            torrent = TorrentEditorInfo(
                phase = record?.phase ?: TorrentPhase.DOWNLOADING,
                progress = torrent?.live?.progress ?: record?.progress ?: 0f,
                downloadBytes = form.parts.sumOf { sizes[it.sourceId] ?: 0L },
                partSizes = sizes,
                layout = layout,
                reviewed = record?.reviewed == true,
                online = torrent?.online ?: true,
            ),
        )
    }

    private fun strategyFor(ready: AnalysisState.Ready, form: EditorForm): ConversionStrategy =
        container.importPlanner.strategyFor(form.parts, ready.files.associateBy { it.id })

    @OptIn(FlowPreview::class)
    private suspend fun autosaveTorrentReview(session: ImportSession, torrentId: String) {
        combine(session.form.filterNotNull(), session.cover) { form, cover -> form to cover }
            .distinctUntilChanged()
            .debounce(400)
            .collect { (form, cover) -> saveTorrentReview(session, torrentId, form, cover, confirm = false) }
    }

    private fun saveTorrentReview(session: ImportSession, torrentId: String, form: EditorForm, cover: SelectedCover?, confirm: Boolean) {
        val ready = session.analysis.value as? AnalysisState.Ready ?: return
        container.torrents.saveReview(torrentId, form.toMetadata(ready.draft.sourceName, ready.draft.metadata), form.parts, cover?.picture, confirm)
    }

    // ---------------------------------------------------------------- name templates

    private var templateCache: Pair<Pair<List<String>, String>, List<TemplateOption>>? = null

    private fun withTemplates(ui: EditorUi, settings: AppSettings, applied: String?): EditorUi {
        val ready = ui.analysis as? AnalysisState.Ready ?: return ui
        if (session?.isEdit != false) return ui
        val source = ready.draft.sourceName
        val key = settings.nameTemplates to source
        val options = templateCache?.takeIf { it.first == key }?.second
            ?: settings.nameTemplates.map { TemplateOption(it, NameTemplate.parse(it, source)) }.also { templateCache = key to it }
        return ui.copy(sourceName = source, templates = options, appliedTemplate = applied)
    }

    /** Fills the details from the source name with [template]. */
    fun applyTemplate(template: String) {
        val s = session ?: return
        val ready = s.analysis.value as? AnalysisState.Ready ?: return
        val fields = NameTemplate.parse(template, ready.draft.sourceName) ?: return
        update { it.withFields(fields) }
        s.appliedTemplate.value = template
        viewModelScope.launch { container.settings.setLastNameTemplate(template) }
    }

    fun addTemplate(template: String) {
        val t = template.trim()
        if (t.isEmpty() || NameTemplate.validate(t) != null) return
        viewModelScope.launch {
            val current = container.settings.current().nameTemplates
            if (t !in current) container.settings.setNameTemplates(current + t)
            if (session != null && (session.analysis.value as? AnalysisState.Ready)?.let { NameTemplate.parse(t, it.draft.sourceName) } != null) {
                applyTemplate(t)
            }
        }
    }

    fun deleteTemplate(template: String) {
        viewModelScope.launch {
            container.settings.setNameTemplates(container.settings.current().nameTemplates - template)
            if (container.settings.current().lastNameTemplate == template) container.settings.setLastNameTemplate(null)
        }
        session?.appliedTemplate?.let { applied -> if (applied.value == template) applied.value = null }
    }

    /** Applies the template used last time when it fits this name, e.g. the next book of a series. */
    private suspend fun autoApplyTemplate(s: ImportSession, ready: AnalysisState.Ready) {
        if (s.isEdit) return
        val last = container.settings.current().lastNameTemplate ?: return
        val fields = NameTemplate.parse(last, ready.draft.sourceName) ?: return
        s.form.update { it?.withFields(fields) }
        s.appliedTemplate.value = last
    }

    private fun EditorForm.withFields(fields: Map<NameField, String>) = copy(
        title = fields[NameField.TITLE] ?: title,
        author = fields[NameField.AUTHOR] ?: author,
        narrator = fields[NameField.NARRATOR] ?: narrator,
        series = fields[NameField.SERIES] ?: series,
        seriesIndex = fields[NameField.NUMBER] ?: seriesIndex,
        year = fields[NameField.YEAR] ?: year,
    )

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

    fun searchOnline(title: String, author: String?, series: String? = null) {
        val s = session ?: return
        s.online.value = OnlineCoverState.Loading
        viewModelScope.launch {
            s.online.value = when (val r = container.coverSearch.search(title, author, series)) {
                is CoverSearchResult.Found -> OnlineCoverState.Results(listOfNotNull(CoverSearchRepository.withSeries(title, series), author).joinToString(" "), r.covers)
                CoverSearchResult.Offline -> OnlineCoverState.Offline
                CoverSearchResult.Failed -> OnlineCoverState.Failed
            }
        }
    }

    /** Downloads [cover] and makes it the selected cover. Returns false if it could not be used. */
    /** Downloads an online result; the UI crops it to a square if needed, then calls [useCover]. */
    fun downloadOnline(cover: OnlineCover, onResult: (EmbeddedPicture?) -> Unit) {
        viewModelScope.launch {
            downloading.value = true
            val picture = container.coverSearch.download(cover)
            downloading.value = false
            onResult(picture)
        }
    }

    fun useCover(picture: EmbeddedPicture) {
        val s = session ?: return
        viewModelScope.launch {
            s.cover.value = SelectedCover(picture, withContext(Dispatchers.IO) { container.covers.draftFile(s.id, picture) }, null)
        }
    }

    // ---------------------------------------------------------------- confirm

    /**
     * Validates the destination and enqueues the import. Without a [decision], any conflict is
     * returned to the UI instead of silently overwriting or renaming. For a torrent, the reviewed
     * details are stored and the conversion starts by itself once the download is complete.
     */
    fun confirm(decision: ImportDecision? = null, onOutcome: (ConfirmOutcome) -> Unit) {
        val s = session ?: return
        val ready = s.analysis.value as? AnalysisState.Ready ?: return
        val form = s.form.value ?: return
        if (busy.value) return
        if (torrentId != null) {
            saveTorrentReview(s, torrentId, form, s.cover.value, confirm = true)
            closeSession(s)
            onOutcome(ConfirmOutcome.Started(torrentId))
            return
        }
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

    private suspend fun prepare(s: ImportSession, ready: AnalysisState.Ready, form: EditorForm, decision: ImportDecision?): ConfirmOutcome {
        val input = ImportPlanner.Input(
            metadata = form.toMetadata(ready.draft.sourceName, ready.draft.metadata),
            parts = form.parts,
            files = ready.files.associateBy { it.id },
            cover = s.cover.value?.picture,
            sourceName = ready.draft.sourceName,
            isEdit = s.isEdit,
            existingBookId = s.existingBookId,
            heldPermissions = s.heldPermissions,
        )
        return when (val outcome = container.importPlanner.plan(input, decision)) {
            PlanOutcome.LibraryUnavailable -> ConfirmOutcome.LibraryUnavailable
            is PlanOutcome.NeedsDecision -> ConfirmOutcome.NeedsDecision(outcome.conflict)
            is PlanOutcome.Ready -> {
                val jobId = container.importManager.enqueue(outcome.request)
                // The job now owns the source permissions; drop the session.
                closeSession(s)
                ConfirmOutcome.Started(jobId)
            }
        }
    }

    private fun closeSession(s: ImportSession) {
        container.importSessions.remove(s.id)
        container.covers.clearDrafts(s.id)
    }

    /** Torrent review: stops and deletes the torrent instead of keeping it for later. */
    fun removeTorrent() {
        val s = session ?: return
        torrentId?.let(container.torrents::remove)
        closeSession(s)
    }

    override fun onCleared() {
        if (session != null && torrentId != null) {
            // Persist the latest edits immediately (the debounced autosave may not have run yet).
            session.form.value?.let { saveTorrentReview(session, torrentId, it, session.cover.value, confirm = false) }
            container.torrents.endEditing(torrentId)
        }
        super.onCleared()
    }

    /**
     * Abandons the import and releases what the session held. A torrent keeps downloading with
     * the edits saved so far; a torrent that turned out not to be an audiobook is removed.
     */
    fun cancel() {
        val s = session ?: return
        if (torrentId != null && (s.analysis.value as? AnalysisState.Failed)?.reason == com.zyagodin.booksound.importer.AnalysisFailure.TORRENT_INVALID) {
            container.torrents.remove(torrentId)
        }
        closeSession(s)
        if (torrentId == null) s.heldPermissions.forEach { container.documents.releasePersistablePermission(it) }
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
