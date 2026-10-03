package com.zyagodin.booksound.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zyagodin.booksound.AppContainer
import com.zyagodin.booksound.data.library.BookDetails
import com.zyagodin.booksound.data.settings.AppSettings
import com.zyagodin.booksound.importer.ImportSelection
import com.zyagodin.booksound.playback.PlayerUiState
import com.zyagodin.booksound.playback.SleepTimerState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class PlayerViewModel(private val container: AppContainer) : ViewModel() {
    val player: StateFlow<PlayerUiState> = container.player.state
    val book: StateFlow<BookDetails?> = container.nowPlaying
    val sleep: StateFlow<SleepTimerState> = container.sleepTimer.state
    val settings: StateFlow<AppSettings> = container.settings.state

    private val connection get() = container.player

    fun togglePlay() = connection.togglePlayPause()
    fun seekTo(ms: Long) = connection.seekTo(ms)
    fun skipBack() = connection.skipBack()
    fun skipForward() = connection.skipForward()
    fun nextChapter() = connection.nextChapter()
    fun previousChapter() = connection.previousChapter()
    fun setSpeed(speed: Float) = connection.setSpeed(speed)
    fun retry() = connection.retry()
    fun stop() = connection.stop()

    fun makeDefaultSpeed(speed: Float) = viewModelScope.launch { container.settings.setDefaultSpeed(speed) }

    fun startSleep(minutes: Int) = viewModelScope.launch {
        container.sleepTimer.start(minutes)
        container.settings.setSleepTimerMinutes(minutes)
    }

    fun sleepEndOfChapter() = container.sleepTimer.startEndOfChapter()
    fun extendSleep(minutes: Int) = container.sleepTimer.extend(minutes)
    fun cancelSleep() = container.sleepTimer.cancel()

    fun rescan() = viewModelScope.launch { container.scanner.scan() }

    fun startEdit(bookId: String): String {
        val session = container.importSessions.create(ImportSelection.ExistingBook(bookId))
        session.analysisJob = container.appScope.launch { container.importAnalyzer.analyze(session) }
        return session.id
    }
}
