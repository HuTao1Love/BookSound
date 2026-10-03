package com.zyagodin.booksound.playback

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface SleepTimerState {
    data object Off : SleepTimerState

    /** Counts down only while audio is playing, so a paused book never "uses up" the timer. */
    data class Countdown(val remainingMs: Long, val totalMs: Long) : SleepTimerState

    data class EndOfChapter(val remainingMs: Long?) : SleepTimerState
}

/**
 * Sleep timer driven by the playback service. The UI talks to this object directly (same
 * process); the service attaches the player it controls.
 */
class SleepTimer(private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = _state

    private var player: AudiobookPlayer? = null
    private var fadeOut: () -> Boolean = { true }
    private var ticker: Job? = null

    fun attach(player: AudiobookPlayer, fadeOut: () -> Boolean) {
        this.player = player
        this.fadeOut = fadeOut
        if (_state.value != SleepTimerState.Off) startTicker()
    }

    fun detach(player: AudiobookPlayer) {
        if (this.player === player) {
            this.player = null
            ticker?.cancel()
            _state.value = SleepTimerState.Off
        }
    }

    fun start(minutes: Int) {
        val total = minutes * 60_000L
        _state.value = SleepTimerState.Countdown(total, total)
        startTicker()
    }

    fun startEndOfChapter() {
        _state.value = SleepTimerState.EndOfChapter(null)
        startTicker()
    }

    fun extend(minutes: Int) {
        when (val s = _state.value) {
            is SleepTimerState.Countdown -> _state.value = s.copy(remainingMs = s.remainingMs + minutes * 60_000L, totalMs = s.totalMs + minutes * 60_000L)
            else -> start(minutes)
        }
        restoreVolume()
    }

    fun cancel() {
        ticker?.cancel()
        ticker = null
        _state.value = SleepTimerState.Off
        restoreVolume()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch(Dispatchers.Main) {
            var last = System.currentTimeMillis()
            while (isActive) {
                delay(TICK_MS)
                val now = System.currentTimeMillis()
                val elapsed = now - last
                last = now
                val p = player ?: continue
                when (val s = _state.value) {
                    SleepTimerState.Off -> return@launch
                    is SleepTimerState.Countdown -> {
                        if (!p.isPlaying) continue
                        val remaining = s.remainingMs - elapsed
                        if (remaining <= 0) {
                            finish(p)
                            return@launch
                        }
                        _state.value = s.copy(remainingMs = remaining)
                        applyFade(p, remaining)
                    }
                    is SleepTimerState.EndOfChapter -> {
                        val chapter = p.chapters.getOrNull(p.currentChapterIndex())
                        val speed = p.playbackParameters.speed.coerceAtLeast(0.1f)
                        val remaining = chapter?.let { ((it.endMs - p.currentPosition) / speed).toLong() }
                            ?: ((p.duration - p.currentPosition) / speed).toLong().takeIf { p.duration > 0 }
                        _state.value = s.copy(remainingMs = remaining)
                        if (!p.isPlaying || remaining == null) continue
                        if (remaining <= TICK_MS) {
                            delay(remaining.coerceAtLeast(0))
                            finish(p)
                            return@launch
                        }
                        applyFade(p, remaining)
                    }
                }
            }
        }
    }

    private fun applyFade(p: Player, remainingMs: Long) {
        if (!fadeOut()) return
        p.volume = if (remainingMs < FADE_MS) (remainingMs.toFloat() / FADE_MS).coerceIn(0.05f, 1f) else 1f
    }

    private fun finish(p: Player) {
        p.pause()
        p.volume = 1f
        _state.value = SleepTimerState.Off
    }

    private fun restoreVolume() {
        player?.let { if (it.volume < 1f) it.volume = 1f }
    }

    companion object {
        private const val TICK_MS = 500L
        private const val FADE_MS = 15_000L
    }
}
