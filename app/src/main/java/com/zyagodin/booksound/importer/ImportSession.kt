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

    /** Magnet link: waiting for peers to send the torrent's file list. */
    data class AwaitingTorrent(val name: String?, val online: Boolean) : AnalysisState
    data class Ready(
        val draft: ImportDraft,
        val files: List<ImportSourceFile>,
        val skipped: List<SkippedFile>,
    ) : AnalysisState
    data class Failed(
        val reason: AnalysisFailure,
        val skipped: List<SkippedFile> = emptyList(),
        /** Reason-specific explanation, e.g. why a torrent is not a single audiobook. */
        val detail: String? = null,
    ) : AnalysisState
}

enum class AnalysisFailure {
    NO_AUDIO_FILES, ALL_FILES_FAILED, SOURCE_UNAVAILABLE, BOOK_NOT_FOUND,

    /** The torrent does not contain a single MP3 or M4B audiobook. */
    TORRENT_INVALID,

    /** The torrent was removed, or its file list could not be fetched. */
    TORRENT_UNAVAILABLE,

    /** The torrent's book is already being converted or is in the library. */
    TORRENT_BUSY,
}

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
    /** The last used name template was considered for auto-applying (only once per session). */
    var templateChecked = false
    val appliedTemplate = MutableStateFlow<String?>(null)
    /** Persisted read grants taken for the picked sources; handed over to the import job. */
    var heldPermissions: List<Uri> = emptyList()
    /** BookSound id embedded in the source, or the edited book's id. */
    var existingBookId: String? = null
    var analysisJob: Job? = null
    val isEdit: Boolean get() = selection is ImportSelection.ExistingBook
    val torrentId: String? get() = (selection as? ImportSelection.Torrent)?.torrentId
}

class ImportSessionStore {
    private val sessions = ConcurrentHashMap<String, ImportSession>()

    /**
     * Starts loading a newly created torrent session. Torrent sessions have deterministic ids, so
     * an editor (or cover picker) restored after the process was killed gets its session back,
     * rebuilt from the torrent's persisted review.
     */
    var torrentSessionStarter: ((ImportSession) -> Unit)? = null

    fun create(selection: ImportSelection): ImportSession {
        val session = ImportSession(UUID.randomUUID().toString(), selection)
        sessions[session.id] = session
        return session
    }

    /** The review session of a torrent, created (and loaded) on first use. */
    fun torrent(torrentId: String): ImportSession {
        var created = false
        val session = sessions.computeIfAbsent(TORRENT_PREFIX + torrentId) { id ->
            created = true
            ImportSession(id, ImportSelection.Torrent(torrentId))
        }
        if (created) torrentSessionStarter?.invoke(session)
        return session
    }

    operator fun get(id: String): ImportSession? =
        sessions[id] ?: if (id.startsWith(TORRENT_PREFIX)) torrent(id.removePrefix(TORRENT_PREFIX)) else null

    fun remove(id: String): ImportSession? = sessions.remove(id)?.also { it.analysisJob?.cancel() }

    fun removeTorrent(torrentId: String) = remove(TORRENT_PREFIX + torrentId)

    companion object {
        const val TORRENT_PREFIX = "torrent-"
    }
}
