package com.zyagodin.booksound.ui.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zyagodin.booksound.AppContainer
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.library.LibraryQuery
import com.zyagodin.booksound.core.library.LibrarySearch
import com.zyagodin.booksound.core.library.ProgressFilter
import com.zyagodin.booksound.core.library.SeriesGroup
import com.zyagodin.booksound.core.library.SortField
import com.zyagodin.booksound.core.library.defaultDescending
import com.zyagodin.booksound.data.library.BookLocation
import com.zyagodin.booksound.data.library.LibraryItem
import com.zyagodin.booksound.data.library.LocationFilter
import com.zyagodin.booksound.data.library.ScanResult
import com.zyagodin.booksound.importer.ImportJob
import com.zyagodin.booksound.importer.ImportSelection
import com.zyagodin.booksound.server.ServerInfo
import com.zyagodin.booksound.server.ServerTransfer
import com.zyagodin.booksound.torrent.TorrentItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUiState(
    val loading: Boolean = true,
    val items: List<LibraryItem> = emptyList(),
    val totalCount: Int = 0,
    val query: String = "",
    val sort: SortField = SortField.RECENT,
    val descending: Boolean = true,
    val filter: ProgressFilter = ProgressFilter.ALL,
    val location: LocationFilter = LocationFilter.ALL,
    /** Some books of the library are only on the server, so filtering by where they are means something. */
    val hasServerBooks: Boolean = false,
    val activeImports: List<ImportJob> = emptyList(),
    /** Torrents still downloading, waiting for review or being converted. */
    val activeTorrents: List<TorrentItem> = emptyList(),
    /** Books on their way to or from the server, or to the watch. */
    val activeTransfers: Int = 0,
    val scanning: Boolean = false,
    val groups: List<SeriesSection> = emptyList(),
)

/** One section of the series view; [series] is null for books without a series. */
data class SeriesSection(val series: String?, val items: List<LibraryItem>, val group: SeriesGroup) {
    /** Every book of the section is only on the server. */
    val onServerOnly: Boolean get() = items.isNotEmpty() && items.all { it.onServerOnly }

    /** Books without a series make two sections when some are only on the server, hence their own key. */
    val key: String get() = if (series == null && onServerOnly) "standalone:server" else "series:" + series.orEmpty()
}

sealed interface LibraryEvent {
    data class ScanFinished(val result: ScanResult) : LibraryEvent
    /** [keptOnServer]: removed from the phone only, so the book is still listed as the server's. */
    data class Removed(val title: String, val fileDeleted: Boolean, val fileDeleteFailed: Boolean, val keptOnServer: Boolean = false) : LibraryEvent {
        fun message(context: Context): String {
            val removed = when {
                fileDeleteFailed -> context.getString(R.string.removed_file_not_deleted, title)
                fileDeleted -> context.getString(R.string.removed_with_file, title)
                else -> context.getString(R.string.removed_kept_file, title)
            }
            return if (keptOnServer) removed + " " + context.getString(R.string.removed_kept_on_server) else removed
        }
    }
}

private data class Downloads(val jobs: List<ImportJob>, val torrents: List<TorrentItem>, val transfers: Int)

class LibraryViewModel(private val container: AppContainer) : ViewModel() {
    private val query = MutableStateFlow("")
    private val _events = MutableSharedFlow<LibraryEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<LibraryEvent> = _events

    val state: StateFlow<LibraryUiState> = combine(
        container.server.libraryWithServer(),
        query,
        container.settings.state,
        combine(container.importManager.jobs, container.torrents.items, container.server.transfers, container.watchSender.transfers) { jobs, torrents, server, watch ->
            Downloads(jobs, torrents, server.count { it.isActive } + watch.count { it.isActive })
        },
        container.scanner.scanning,
    ) { items, q, settings, (jobs, torrents, transfers), scanning ->
        val libraryQuery = LibraryQuery(q, settings.librarySort, settings.libraryDescending, settings.libraryFilter)
        val byId = items.associateBy { it.id }
        // Books on the phone come first, then the ones only on the server, each part in the chosen order.
        val shown = items.filter { settings.libraryLocation.matches(it) }
        val (here, there) = LibrarySearch.apply(shown.map { it.entry }, libraryQuery).mapNotNull { byId[it.book.id.value] }
            .partition { !it.onServerOnly }
        val ordered = here + there
        // Likewise the sections: a series with a book on the phone stays among the phone's ones; the books
        // without a series split in two carousels.
        val groups = LibrarySearch.groupBySeries(ordered.map { it.entry }).flatMap { g ->
            val books = g.entries.mapNotNull { byId[it.book.id.value] }
            if (g.series != null) {
                listOf(SeriesSection(g.series, books, g))
            } else {
                books.partition { !it.onServerOnly }.toList().filter { it.isNotEmpty() }
                    .map { part -> SeriesSection(null, part, SeriesGroup(null, part.map { it.entry })) }
            }
        }.sortedBy { it.onServerOnly }
        LibraryUiState(
            loading = false,
            items = ordered,
            totalCount = items.size,
            query = q,
            sort = settings.librarySort,
            descending = settings.libraryDescending,
            filter = settings.libraryFilter,
            location = settings.libraryLocation,
            hasServerBooks = items.any { it.onServerOnly },
            // Torrent conversions are shown in the downloads.
            activeImports = jobs.filter { it.isActive && it.request.torrentId == null },
            activeTorrents = torrents.filter { it.record.isActive },
            activeTransfers = transfers,
            scanning = scanning,
            groups = groups,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /** The server's version and API, for the warning shown when they don't fit this app. */
    val serverInfo: StateFlow<ServerInfo?> = container.server.info

    /** Books moving to or from the server, by id; apart from [state] since progress changes many times a second. */
    val transfers: StateFlow<Map<String, ServerTransfer>> = activeServerTransfers(container)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Free bytes on the phone while they are below [LOW_STORAGE_BYTES], else null. Checked while the
     * library is on screen: with the disk full, imports, downloads and saving the listening position fail.
     */
    val lowStorage: StateFlow<Long?> = flow {
        while (true) {
            emit(withContext(Dispatchers.IO) { container.dataDir.usableSpace }.takeIf { it < LOW_STORAGE_BYTES })
            delay(LOW_STORAGE_CHECK_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setQuery(text: String) {
        query.value = text
    }

    fun setSort(field: SortField) = viewModelScope.launch {
        val current = container.settings.state.value
        val descending = if (current.librarySort == field) !current.libraryDescending else field.defaultDescending
        container.settings.setLibrarySort(field, descending)
    }

    fun setFilter(filter: ProgressFilter) = viewModelScope.launch { container.settings.setLibraryFilter(filter) }
    fun setLocation(location: LocationFilter) = viewModelScope.launch { container.settings.setLibraryLocation(location) }


    fun refresh() = viewModelScope.launch {
        _events.emit(LibraryEvent.ScanFinished(container.scanner.scan()))
    }

    fun play(item: LibraryItem) = container.player.play(item.id)

    fun setFinished(item: LibraryItem, finished: Boolean) = viewModelScope.launch {
        container.library.setFinished(item.id, finished)
    }

    fun checkUpdates() = container.updates.check()

    fun download(item: LibraryItem) = downloadFromServer(container, item)
    fun upload(item: LibraryItem) = container.server.upload(item.id, item.metadata.title)
    fun cancelTransfer(transfer: ServerTransfer) = container.server.cancel(transfer.bookId, transfer.direction)

    /** The user may delete books from the server too (an admin). */
    val canDeleteOnServer: StateFlow<Boolean> = container.server.canDeleteOnServer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun deleteFromServer(item: LibraryItem) = container.server.deleteFromServer(item.id)

    fun remove(item: LibraryItem, deleteFile: Boolean, deleteFromServer: Boolean) = viewModelScope.launch {
        if (deleteFromServer) container.server.deleteFromServer(item.id)
        if (container.player.state.value.bookId == item.id) container.player.stop()
        val ok = container.library.remove(item.id, deleteFile)
        _events.emit(LibraryEvent.Removed(item.metadata.title, deleteFile && ok, deleteFile && !ok, item.location == BookLocation.SYNCED && !deleteFromServer))
    }

    /** Creates an import session and starts reading the files in the background. */
    fun startImport(selection: ImportSelection): String {
        val session = container.importSessions.create(selection)
        session.analysisJob = container.appScope.launch { container.importAnalyzer.analyze(session) }
        return session.id
    }

    private companion object {
        const val LOW_STORAGE_BYTES = 500L * 1024 * 1024
        const val LOW_STORAGE_CHECK_MS = 30_000L
    }
}

/** The active server transfers by book id, for the covers' progress. */
internal fun activeServerTransfers(container: AppContainer) =
    container.server.transfers.map { list -> list.filter { it.isActive }.associateBy { it.bookId } }

/** Queues the download of a book that is only on the server. */
internal fun downloadFromServer(container: AppContainer, item: LibraryItem) = container.appScope.launch {
    container.server.download(item.id)
}
