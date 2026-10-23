package com.zyagodin.booksound.ui.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zyagodin.booksound.AppContainer
import com.zyagodin.booksound.cast.CastSessionState
import com.zyagodin.booksound.cast.CastTarget
import com.zyagodin.booksound.cast.CastUiState
import com.zyagodin.booksound.core.audio.VoicePreset
import com.zyagodin.booksound.data.db.ListeningStartEntity
import com.zyagodin.booksound.data.library.BookDetails
import com.zyagodin.booksound.data.settings.AppSettings
import com.zyagodin.booksound.importer.ImportSelection
import com.zyagodin.booksound.playback.AudioOutput
import com.zyagodin.booksound.playback.PlayerUiState
import com.zyagodin.booksound.playback.SleepTimerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel(private val container: AppContainer) : ViewModel() {
    val cast: StateFlow<CastUiState> = container.cast.state
    val output: Flow<AudioOutput> = container.audioOutputs.current

    /** The phone's player, or the Cast device while the book plays there. */
    val player: StateFlow<PlayerUiState> = combine(container.player.state, container.cast.state) { local, cast ->
        val remote = cast.session?.takeIf { it.stage == CastSessionState.Stage.CASTING && it.bookId == local.bookId }
        remote?.let {
            local.copy(isPlaying = it.playing, playWhenReady = it.playing || it.buffering, isBuffering = it.buffering, positionMs = it.positionMs, speed = it.speed, problem = null)
        } ?: local
    }.stateIn(viewModelScope, SharingStarted.Eagerly, container.player.state.value)
    val book: StateFlow<BookDetails?> = container.nowPlaying
    val sleep: StateFlow<SleepTimerState> = container.sleepTimer.state
    val settings: StateFlow<AppSettings> = container.settings.state

    /** Where the listening sessions of the book playing began, the latest first. */
    val listeningStarts: StateFlow<List<ListeningStartEntity>> = container.nowPlaying
        .map { it?.item?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> id?.let { container.library.observeListeningStarts(it) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val connection get() = container.player
    private val casting get() = container.cast.isCasting

    init {
        container.cast.prepare()
    }

    fun togglePlay() { if (casting) container.cast.togglePlay() else connection.togglePlayPause() }
    fun seekTo(ms: Long) { if (casting) container.cast.seekTo(ms) else connection.seekTo(ms) }
    fun skipBack() { if (casting) container.cast.seekBy(-settings.value.skipBackSeconds * 1000L) else connection.skipBack() }
    fun skipForward() { if (casting) container.cast.seekBy(settings.value.skipForwardSeconds * 1000L) else connection.skipForward() }
    fun setSpeed(speed: Float) { if (casting) container.cast.setSpeed(speed) else connection.setSpeed(speed) }

    fun nextChapter() {
        if (!casting) return connection.nextChapter()
        val position = player.value.positionMs
        book.value?.chapters?.firstOrNull { it.startMs > position }?.let { container.cast.seekTo(it.startMs) }
    }

    fun previousChapter() {
        if (!casting) return connection.previousChapter()
        val chapters = book.value?.chapters.orEmpty()
        val position = player.value.positionMs
        val current = chapters.indexOfLast { it.startMs <= position }
        // Like the phone's player: a few seconds in, back to the chapter's start; otherwise the one before.
        val target = when {
            current < 0 -> 0L
            position - chapters[current].startMs > RESTART_CHAPTER_MS || current == 0 -> chapters[current].startMs
            else -> chapters[current - 1].startMs
        }
        container.cast.seekTo(target)
    }

    fun castTo(target: CastTarget) = container.cast.connect(target)
    fun stopCasting() = container.cast.disconnect()
    fun startCastDiscovery() = container.cast.startDiscovery()
    fun stopCastDiscovery() = container.cast.stopDiscovery()
    fun clearCastProblem() = container.cast.clearProblem()
    fun openOutputPicker(context: Context) = container.audioOutputs.openPicker(context)
    fun retry() = connection.retry()
    fun stop() = connection.stop()

    fun makeDefaultSpeed(speed: Float) = viewModelScope.launch { container.settings.setDefaultSpeed(speed) }

    fun startSleep(minutes: Int) = viewModelScope.launch {
        container.sleepTimer.start(minutes)
        container.settings.setSleepTimerMinutes(minutes)
    }

    /** Remembers [preset] for the book; choosing the default preset makes the book follow it again. */
    fun setVoicePreset(bookId: String, preset: VoicePreset) = viewModelScope.launch {
        container.settings.setBookVoicePreset(bookId, preset.takeIf { it != container.settings.state.value.voicePreset })
    }

    /** Remembers the volume boost for the book; the default level makes the book follow it again. */
    fun setVolumeBoost(bookId: String, db: Int) = viewModelScope.launch {
        container.settings.setBookVolumeBoost(bookId, db.takeIf { it != container.settings.state.value.volumeBoostDb })
    }

    /** Remembers skipping silence for the book; the default makes the book follow it again. */
    fun setSkipSilence(bookId: String, enabled: Boolean) = viewModelScope.launch {
        container.settings.setBookSkipSilence(bookId, enabled.takeIf { it != container.settings.state.value.skipSilence })
    }

    fun makeDefaultSound(bookId: String) = viewModelScope.launch { container.settings.makeBookSoundDefault(bookId) }

    fun sleepEndOfChapter() = container.sleepTimer.startEndOfChapter()
    fun extendSleep(minutes: Int) = container.sleepTimer.extend(minutes)
    fun cancelSleep() = container.sleepTimer.cancel()

    fun rescan() = viewModelScope.launch { container.scanner.scan() }

    fun startEdit(bookId: String): String {
        val session = container.importSessions.create(ImportSelection.ExistingBook(bookId))
        session.analysisJob = container.appScope.launch { container.importAnalyzer.analyze(session) }
        return session.id
    }

    private companion object {
        const val RESTART_CHAPTER_MS = 3_000L
    }
}
