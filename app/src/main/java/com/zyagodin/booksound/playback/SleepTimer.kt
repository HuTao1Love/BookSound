package com.zyagodin.booksound.playback

import android.content.Context
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

    /**
     * Pauses at the end of chapter [chapterIndex], or at the end of the book when it has no
     * chapters (-1). Skipping ahead moves the stop to the end of the chapter now playing.
     */
    data class EndOfChapter(val remainingMs: Long?, val chapterIndex: Int = -1) : SleepTimerState
}

/**
 * Sleep timer driven by the playback service. The UI talks to this object directly (same
 * process); the service attaches the player it controls.
 *
 * Shake to reset: while the timer runs and the book plays, shaking the phone starts the countdown
 * over (in "end of chapter" mode: plays one more chapter) and undoes the fade-out.
 */
class SleepTimer(context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = _state

    private var player: AudiobookPlayer? = null
    private var fadeOut: () -> Boolean = { true }
    private var shakeToReset: () -> Boolean = { false }
    private var ticker: Job? = null
    private val shake = ShakeDetector(context) { onShake() }

    fun attach(player: AudiobookPlayer, fadeOut: () -> Boolean, shakeToReset: () -> Boolean) {
        this.player = player
        this.fadeOut = fadeOut
        this.shakeToReset = shakeToReset
        if (_state.value != SleepTimerState.Off) startTicker()
    }

    fun detach(player: AudiobookPlayer) {
        if (this.player === player) {
            this.player = null
            ticker?.cancel()
            _state.value = SleepTimerState.Off
            shake.stop()
        }
    }

    fun start(minutes: Int) {
        val total = minutes * 60_000L
        _state.value = SleepTimerState.Countdown(total, total)
        restoreVolume()
        startTicker()
    }

    fun startEndOfChapter() {
        _state.value = SleepTimerState.EndOfChapter(null, player?.currentChapterIndex() ?: -1)
        restoreVolume()
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
        shake.stop()
        restoreVolume()
    }

    private fun onShake() {
        val p = player ?: return
        when (val s = _state.value) {
            SleepTimerState.Off -> return
            is SleepTimerState.Countdown -> _state.value = s.copy(remainingMs = s.totalMs)
            is SleepTimerState.EndOfChapter -> {
                val next = s.chapterIndex + 1
                if (s.chapterIndex >= 0 && next < p.chapters.size) _state.value = s.copy(chapterIndex = next)
            }
        }
        restoreVolume()
        shake.confirm()
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
                // Listen for shakes only while they can do something: timer on, book playing.
                if (p.isPlaying && shakeToReset()) shake.start() else shake.stop()
                when (val s = _state.value) {
                    SleepTimerState.Off -> {
                        shake.stop()
                        return@launch
                    }
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
                        val current = p.currentChapterIndex()
                        val target = maxOf(current, s.chapterIndex)
                        val chapter = p.chapters.getOrNull(target)
                        val speed = p.playbackParameters.speed.coerceAtLeast(0.1f)
                        val remaining = chapter?.let { ((it.endMs - p.currentPosition) / speed).toLong() }
                            ?: ((p.duration - p.currentPosition) / speed).toLong().takeIf { p.duration > 0 }
                        _state.value = s.copy(remainingMs = remaining, chapterIndex = target)
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
        shake.stop()
    }

    private fun restoreVolume() {
        player?.let { if (it.volume < 1f) it.volume = 1f }
    }

    companion object {
        private const val TICK_MS = 500L
        private const val FADE_MS = 15_000L
    }
}
