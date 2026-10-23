package com.zyagodin.booksound.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zyagodin.booksound.AppContainer
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.data.library.BookDetails
import com.zyagodin.booksound.importer.ImportSelection
import com.zyagodin.booksound.playback.PlayerUiState
import com.zyagodin.booksound.server.ServerTransfer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DetailState {
    data object Loading : DetailState
    data object Gone : DetailState
    data class Loaded(val details: BookDetails) : DetailState
}

class BookDetailViewModel(private val container: AppContainer, private val bookId: String) : ViewModel() {
    /** The phone's book; a book only on the server has a page too, until it is downloaded and becomes the phone's. */
    val state: StateFlow<DetailState> = combine(container.library.observeDetails(bookId), container.server.serverDetails(bookId)) { local, remote ->
        when {
            local != null && !local.item.entry.book.sync.deleted -> DetailState.Loaded(local)
            remote != null -> DetailState.Loaded(remote)
            else -> DetailState.Gone
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Loading)

    /** The book moving to or from the server. */
    val transfer: StateFlow<ServerTransfer?> = container.server.transfers
        .map { list -> list.lastOrNull { it.bookId == bookId && it.isActive } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val player: StateFlow<PlayerUiState> = container.player.state
    val watchConnected: StateFlow<Boolean> = container.watchSender.watchConnected

    /** Signed in to a server that doesn't have this book yet. */
    val canUpload: StateFlow<Boolean> = kotlinx.coroutines.flow.combine(container.server.account, container.server.serverBookIds()) { account, ids ->
        account != null && bookId !in ids
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The server has this book. */
    val onServer: StateFlow<Boolean> = container.server.serverBookIds().map { bookId in it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The user may delete books from the server too (an admin). */
    val canDeleteOnServer: StateFlow<Boolean> = container.server.canDeleteOnServer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        container.watchSender.refreshWatch()
    }

    fun play() = container.player.play(bookId)
    fun pause() = container.player.pause()
    fun playChapter(chapter: Chapter) = container.player.play(bookId, chapter.startMs)
    fun setFinished(finished: Boolean) = viewModelScope.launch { container.library.setFinished(bookId, finished) }
    fun rescan() = viewModelScope.launch { container.scanner.scan() }
    fun sendToWatch(title: String) = container.watchSender.send(bookId, title)
    fun uploadToServer(title: String) = container.server.upload(bookId, title)
    fun download() = viewModelScope.launch { container.server.download(bookId) }
    fun cancelTransfer(transfer: ServerTransfer) = container.server.cancel(transfer.bookId, transfer.direction)

    fun deleteFromServer() = container.server.deleteFromServer(bookId)

    // The app's scope: the page closes right away.
    fun remove(deleteFile: Boolean, deleteFromServer: Boolean) = container.appScope.launch {
        if (deleteFromServer) container.server.deleteFromServer(bookId)
        if (container.player.state.value.bookId == bookId) container.player.stop()
        container.library.remove(bookId, deleteFile)
    }

    fun startEdit(): String {
        val session = container.importSessions.create(ImportSelection.ExistingBook(bookId))
        session.analysisJob = container.appScope.launch { container.importAnalyzer.analyze(session) }
        return session.id
    }
}
