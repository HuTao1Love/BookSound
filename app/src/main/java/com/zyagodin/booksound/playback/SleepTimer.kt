package com.zyagodin.booksound.playback

import android.content.Context
import android.os.SystemClock
import androidx.media3.common.Player
import com.zyagodin.booksound.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.ZonedDateTime

sealed interface SleepTimerState {
    data object Off : SleepTimerState

    /**
     * Counts down only while audio is playing, so a paused book never "uses up" the timer.
     * [auto]: started by the night schedule rather than by the user; it turns off when the night ends.
     */
    data class Countdown(val remainingMs: Long, val totalMs: Long, val auto: Boolean = false) : SleepTimerState

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
 *
 * Repeat ([com.zyagodin.booksound.data.settings.AppSettings.sleepRepeat]): a timer that ran out is
 * remembered and starts again as soon as the book plays again, until the user turns it off.
 *
 * Night schedule ([com.zyagodin.booksound.data.settings.AppSettings.sleepAutoNight]): while the book
 * plays during the night hours and no timer runs, a timer of the default duration starts by itself
 * (so after it pauses the book, playing again starts it again). It turns off when the night ends; a
 * timer the user picked keeps running. Turned off by the user, it stays off until the night ends.
 */
class SleepTimer(context: Context, private val scope: CoroutineScope, private val settings: SettingsRepository) {
    private val _state = MutableStateFlow<SleepTimerState>(SleepTimerState.Off)
    val state: StateFlow<SleepTimerState> = _state

    private var player: AudiobookPlayer? = null
    private var fadeOut: () -> Boolean = { true }
    private var shakeToReset: () -> Boolean = { false }
    private var ticker: Job? = null
    private val shake = ShakeDetector(context) { onShake() }

    /** Minutes of the last countdown started, to repeat it without the "+5 min" extensions. */
    private var countdownMinutes = 0

    /** A repeated "end of chapter" timer should skip the chapter it already stopped at. */
    private var skipEndedChapter = false

    /** The user turned the timer off at night: the night schedule doesn't start it before this (wall clock). */
    private var autoSuppressedUntil = 0L

    /** Starts the night timer on play, and when the night begins while the book is already playing. */
    private var nightWatch: Job? = null

    /** Playback stopped since the last tick: the time until the next tick was not all listened to. */
    private var pausedSinceTick = false

    private val repeatOnPlay = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                repeatIfRemembered()
                watchNight()
            } else {
                pausedSinceTick = true
                nightWatch?.cancel()
            }
        }
    }

    fun attach(player: AudiobookPlayer, fadeOut: () -> Boolean, shakeToReset: () -> Boolean) {
        this.player = player
        this.fadeOut = fadeOut
        this.shakeToReset = shakeToReset
        player.addListener(repeatOnPlay)
        if (_state.value != SleepTimerState.Off) startTicker()
    }

    fun detach(player: AudiobookPlayer) {
        if (this.player === player) {
            player.removeListener(repeatOnPlay)
            this.player = null
            ticker?.cancel()
            nightWatch?.cancel()
            _state.value = SleepTimerState.Off
            shake.stop()
        }
    }

    fun start(minutes: Int) = start(minutes, auto = false)

    private fun start(minutes: Int, auto: Boolean) {
        val total = minutes * 60_000L
        countdownMinutes = minutes
        skipEndedChapter = false
        _state.value = SleepTimerState.Countdown(total, total, auto)
        restoreVolume()
        startTicker()
    }

    fun startEndOfChapter() {
        skipEndedChapter = false
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

    /** Turned off by the user: also forgets the timer to repeat, and keeps the night timer off until morning. */
    fun cancel() {
        stop()
        remember(null)
        val now = ZonedDateTime.now()
        val night = night()
        if (settings.state.value.sleepAutoNight && night.contains(now)) autoSuppressedUntil = night.endAfter(now).toInstant().toEpochMilli()
    }

    /** The book played to its end: the timer stops, but with repeat on it starts again with the next book. */
    fun bookEnded() {
        if (_state.value != SleepTimerState.Off && !isAuto() && settings.state.value.sleepRepeat) remember(repeatValue())
        stop()
    }

    private fun stop() {
        ticker?.cancel()
        ticker = null
        _state.value = SleepTimerState.Off
        skipEndedChapter = false
        shake.stop()
        restoreVolume()
    }

    private fun repeatValue(): Int = when (_state.value) {
        is SleepTimerState.EndOfChapter -> REPEAT_END_OF_CHAPTER
        else -> countdownMinutes
    }

    private fun isAuto(): Boolean = (_state.value as? SleepTimerState.Countdown)?.auto == true

    private fun night(): NightWindow = settings.state.value.let { NightWindow(it.sleepNightStartMinute, it.sleepNightEndMinute) }

    private fun watchNight() {
        if (nightWatch?.isActive == true) return
        nightWatch = scope.launch(Dispatchers.Main) {
            while (isActive) {
                if (player?.isPlaying == true) startIfNight()
                delay(NIGHT_CHECK_MS)
            }
        }
    }

    private fun startIfNight() {
        if (_state.value != SleepTimerState.Off) return
        val s = settings.state.value
        if (!s.sleepAutoNight || System.currentTimeMillis() < autoSuppressedUntil) return
        if (night().contains(ZonedDateTime.now())) start(s.sleepTimerMinutes, auto = true)
    }

    private fun remember(timer: Int?) {
        // On the main thread, so quick successive changes are written in order.
        scope.launch(Dispatchers.Main) { settings.setSleepRepeatTimer(timer) }
    }

    private fun repeatIfRemembered() {
        if (_state.value != SleepTimerState.Off) return
        val s = settings.state.value
        if (!s.sleepRepeat) return
        when (val timer = s.sleepRepeatTimer) {
            null -> return
            REPEAT_END_OF_CHAPTER -> {
                startEndOfChapter()
                skipEndedChapter = true
            }
            else -> start(timer)
        }
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
            var last = SystemClock.elapsedRealtime()
            var wasPlaying = player?.isPlaying == true
            pausedSinceTick = false
            while (isActive) {
                delay(TICK_MS)
                val now = SystemClock.elapsedRealtime()
                val elapsed = now - last
                last = now
                val p = player ?: continue
                val playing = p.isPlaying
                // Only time the book played throughout counts. While the book is paused (say,
                // another app took the audio focus) Android may freeze the process or let the CPU
                // sleep for a long time, and that gap must not be charged in one go on resume.
                val played = if (playing && wasPlaying && !pausedSinceTick) elapsed else 0L
                wasPlaying = playing
                pausedSinceTick = false
                // Listen for shakes only while they can do something: timer on, book playing.
                if (playing && shakeToReset()) shake.start() else shake.stop()
                when (val s = _state.value) {
                    SleepTimerState.Off -> {
                        shake.stop()
                        return@launch
                    }
                    is SleepTimerState.Countdown -> {
                        if (s.auto && !night().contains(ZonedDateTime.now())) {
                            // Morning: the night timer is done.
                            stop()
                            return@launch
                        }
                        if (!playing) continue
                        val remaining = s.remainingMs - played
                        if (remaining <= 0) {
                            finish(p)
                            return@launch
                        }
                        _state.value = s.copy(remainingMs = remaining)
                        applyFade(p, remaining)
                    }
                    is SleepTimerState.EndOfChapter -> {
                        val current = p.currentChapterIndex()
                        var target = maxOf(current, s.chapterIndex)
                        if (skipEndedChapter && p.isPlaying) {
                            // Repeated after stopping at a chapter end: playback resumes at (or,
                            // with smart rewind, just before) that end, so stop after the next one.
                            skipEndedChapter = false
                            val end = p.chapters.getOrNull(target)?.endMs ?: p.duration.takeIf { p.duration > 0 }
                            if (end != null && end - p.currentPosition < REPEAT_SKIP_MS) {
                                if (target + 1 >= p.chapters.size) {
                                    // Nothing left to stop after: the book plays out.
                                    stop()
                                    return@launch
                                }
                                target++
                            }
                        }
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
        // The night timer starts again on play anyway, and must not be repeated into the day.
        if (!isAuto()) remember(if (settings.state.value.sleepRepeat) repeatValue() else null)
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
        private const val NIGHT_CHECK_MS = 30_000L

        /** Stored as the timer to repeat for "end of chapter" (otherwise minutes). */
        private const val REPEAT_END_OF_CHAPTER = 0

        /** A chapter with less than this left counts as the one the timer already stopped at. */
        private const val REPEAT_SKIP_MS = 60_000L
    }
}
