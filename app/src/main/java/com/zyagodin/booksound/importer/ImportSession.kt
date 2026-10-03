package com.zyagodin.booksound.importer

import android.net.Uri
import com.zyagodin.booksound.core.model.EmbeddedPicture
import com.zyagodin.booksound.cover.OnlineCover
import com.zyagodin.booksound.core.organize.CoverCandidate
import com.zyagodin.booksound.core.organize.DraftPart
import com.zyagodin.booksound.core.organize.ImportDraft
import com.zyagodin.booksound.core.organize.ImportSourceFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A file that was found but cannot be imported, shown to the user with the reason. */
data class SkippedFile(val name: String, val reason: SkipReason, val detail: String? = null)

enum class SkipReason { UNSUPPORTED, CORRUPTED, UNREADABLE }

sealed interface AnalysisState {
    data class Analyzing(val done: Int, val total: Int, val currentName: String?) : AnalysisState
    data class Ready(
        val draft: ImportDraft,
        val files: List<ImportSourceFile>,
        val skipped: List<SkippedFile>,
    ) : AnalysisState
    data class Failed(val reason: AnalysisFailure, val skipped: List<SkippedFile> = emptyList()) : AnalysisState
}

enum class AnalysisFailure { NO_AUDIO_FILES, ALL_FILES_FAILED, SOURCE_UNAVAILABLE, BOOK_NOT_FOUND }

/** Fields the user edits in the review screen. */
data class EditorForm(
    val title: String,
    val author: String,
    val narrator: String,
    val series: String,
    val seriesIndex: String,
    val year: String,
    val description: String,
    val parts: List<DraftPart>,
)

/** Online cover search shared by the editor (inline suggestions) and the cover picker. */
sealed interface OnlineCoverState {
    data object Idle : OnlineCoverState
    data object Loading : OnlineCoverState
    data class Results(val query: String, val covers: List<OnlineCover>) : OnlineCoverState
    data object Offline : OnlineCoverState
    data object Failed : OnlineCoverState
}

/** Cover the user picked; [file] is a local preview copy. */
data class SelectedCover(val picture: EmbeddedPicture, val file: java.io.File, val source: CoverCandidate?)

/**
 * State of one import being reviewed. Lives in [ImportSessionStore] (not in a ViewModel) so the
 * editor and the cover picker share it, and it survives configuration changes such as folding.
 */
class ImportSession(val id: String, val selection: ImportSelection) {
    val analysis = MutableStateFlow<AnalysisState>(AnalysisState.Analyzing(0, 0, null))
    val form = MutableStateFlow<EditorForm?>(null)
    val cover = MutableStateFlow<SelectedCover?>(null)
    val coverCandidates = MutableStateFlow<List<CoverCandidate>>(emptyList())
    val online = MutableStateFlow<OnlineCoverState>(OnlineCoverState.Idle)
    var autoSearchDone = false
    /** Persisted read grants taken for the picked sources; handed over to the import job. */
    var heldPermissions: List<Uri> = emptyList()
    /** BookSound id embedded in the source, or the edited book's id. */
    var existingBookId: String? = null
    var analysisJob: Job? = null
    val isEdit: Boolean get() = selection is ImportSelection.ExistingBook
}

class ImportSessionStore {
    private val sessions = ConcurrentHashMap<String, ImportSession>()

    fun create(selection: ImportSelection): ImportSession {
        val session = ImportSession(UUID.randomUUID().toString(), selection)
        sessions[session.id] = session
        return session
    }

    operator fun get(id: String): ImportSession? = sessions[id]

    fun remove(id: String): ImportSession? = sessions.remove(id)?.also { it.analysisJob?.cancel() }
}
