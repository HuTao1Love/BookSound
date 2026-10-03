package com.zyagodin.booksound.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zyagodin.booksound.AppContainer
import com.zyagodin.booksound.core.library.LibraryQuery
import com.zyagodin.booksound.core.library.LibrarySearch
import com.zyagodin.booksound.core.library.ProgressFilter
import com.zyagodin.booksound.core.library.SeriesGroup
import com.zyagodin.booksound.core.library.SortField
import com.zyagodin.booksound.core.library.defaultDescending
import com.zyagodin.booksound.data.library.LibraryItem
import com.zyagodin.booksound.data.library.ScanResult
import com.zyagodin.booksound.data.settings.LibraryLayoutMode
import com.zyagodin.booksound.importer.ImportJob
import com.zyagodin.booksound.importer.ImportSelection
import com.zyagodin.booksound.torrent.TorrentItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryUiState(
    val loading: Boolean = true,
    val items: List<LibraryItem> = emptyList(),
    val totalCount: Int = 0,
    val query: String = "",
    val sort: SortField = SortField.RECENT,
    val descending: Boolean = true,
    val filter: ProgressFilter = ProgressFilter.ALL,
    val layout: LibraryLayoutMode = LibraryLayoutMode.GRID,
    val continueListening: LibraryItem? = null,
    val activeImports: List<ImportJob> = emptyList(),
    /** Torrents still downloading, waiting for review or being converted. */
    val activeTorrents: List<TorrentItem> = emptyList(),
    val scanning: Boolean = false,
    val groups: List<SeriesSection> = emptyList(),
)

/** One section of the series view; [series] is null for books without a series. */
data class SeriesSection(val series: String?, val items: List<LibraryItem>, val group: SeriesGroup)

sealed interface LibraryEvent {
    data class ScanFinished(val result: ScanResult) : LibraryEvent
    data class Removed(val title: String, val fileDeleted: Boolean, val fileDeleteFailed: Boolean) : LibraryEvent
}

class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    private val query = MutableStateFlow("")
    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<LibraryEvent> = _events

    val state: StateFlow<LibraryUiState> = combine(
        container.library.library,
        query,
        container.settings.state,
        combine(container.importManager.jobs, container.torrents.items) { jobs, torrents -> jobs to torrents },
        container.scanner.scanning,
    ) { items, q, settings, (jobs, torrents), scanning ->
        val libraryQuery = LibraryQuery(q, settings.librarySort, settings.libraryDescending, settings.libraryFilter)
        val byId = items.associateBy { it.id }
        val ordered = LibrarySearch.apply(items.map { it.entry }, libraryQuery).mapNotNull { byId[it.book.id.value] }
        val groups = if (settings.libraryLayout == LibraryLayoutMode.SERIES) {
            LibrarySearch.groupBySeries(ordered.map { it.entry }).map { g ->
                SeriesSection(g.series, g.entries.mapNotNull { byId[it.book.id.value] }, g)
            }
        } else emptyList()
        val continueItem = items
            .filter { !it.entry.finished && it.entry.positionMs > 0 && it.entry.lastPlayedAt != null && !it.isMissing }
            .maxByOrNull { it.entry.lastPlayedAt!! }
        LibraryUiState(
            loading = false,
            items = ordered,
            totalCount = items.size,
            query = q,
            sort = settings.librarySort,
            descending = settings.libraryDescending,
            filter = settings.libraryFilter,
            layout = settings.libraryLayout,
            continueListening = continueItem.takeIf { q.isBlank() && settings.libraryFilter == ProgressFilter.ALL },
            // Torrent conversions are shown by the torrent banner.
            activeImports = jobs.filter { it.isActive && it.request.torrentId == null },
            activeTorrents = torrents.filter { it.record.isActive },
            scanning = scanning,
            groups = groups,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun setQuery(text: String) {
        query.value = text
    }

    fun setSort(field: SortField) = viewModelScope.launch {
        val current = container.settings.state.value
        val descending = if (current.librarySort == field) !current.libraryDescending else field.defaultDescending
        container.settings.setLibrarySort(field, descending)
    }

    fun setFilter(filter: ProgressFilter) = viewModelScope.launch { container.settings.setLibraryFilter(filter) }

    fun toggleLayout() = viewModelScope.launch {
        val next = when (container.settings.state.value.libraryLayout) {
            LibraryLayoutMode.SERIES -> LibraryLayoutMode.GRID
            LibraryLayoutMode.GRID -> LibraryLayoutMode.LIST
            LibraryLayoutMode.LIST -> LibraryLayoutMode.SERIES
        }
        container.settings.setLibraryLayout(next)
    }

    fun refresh() = viewModelScope.launch {
        _events.emit(LibraryEvent.ScanFinished(container.scanner.scan()))
    }

    fun play(item: LibraryItem) = container.player.play(item.id)

    fun setFinished(item: LibraryItem, finished: Boolean) = viewModelScope.launch {
        container.library.setFinished(item.id, finished)
    }

    fun remove(item: LibraryItem, deleteFile: Boolean) = viewModelScope.launch {
        if (container.player.state.value.bookId == item.id) container.player.stop()
        val ok = container.library.remove(item.id, deleteFile)
        _events.emit(LibraryEvent.Removed(item.metadata.title, deleteFile && ok, deleteFile && !ok))
    }

    /** Creates an import session and starts reading the files in the background. */
    fun startImport(selection: ImportSelection): String {
        val session = container.importSessions.create(selection)
        session.analysisJob = container.appScope.launch { container.importAnalyzer.analyze(session) }
        return session.id
    }
}
