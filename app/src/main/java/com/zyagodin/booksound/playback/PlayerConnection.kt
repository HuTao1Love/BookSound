package com.zyagodin.booksound.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class PlaybackProblem { FILE_UNAVAILABLE, FILE_DAMAGED, UNSUPPORTED, OTHER }

data class PlayerUiState(
    val connected: Boolean = false,
    val bookId: String? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val problem: PlaybackProblem? = null,
) {
    val hasBook: Boolean get() = bookId != null
}

/**
 * The UI's handle on the playback service, via a MediaController. Commands go through the
 * session exactly like those from the notification or a Bluetooth device.
 */
class PlayerConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val lastBookProvider: () -> String? = { null },
) {

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var ticker: Job? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()
    private var connectionUsers = 0

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    /** Binds to the playback service. Balanced by [disconnect]; safe to call repeatedly. */
    fun connect() {
        connectionUsers++
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val f = MediaController.Builder(context, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: run {
                future = null
                return@addListener
            }
            controller = c
            c.addListener(listener)
            publish(c)
            val queued = pending.toList()
            pending.clear()
            queued.forEach { it(c) }
            // A fresh service (e.g. after it stopped while paused) starts empty: reload the last book.
            if (queued.isEmpty() && c.mediaItemCount == 0) lastBookProvider()?.let { play(it, playWhenReady = false) }
        }, ContextCompat.getMainExecutor(context))
    }

    fun disconnect() {
        connectionUsers = (connectionUsers - 1).coerceAtLeast(0)
        if (connectionUsers > 0) return
        ticker?.cancel()
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        _state.update { it.copy(connected = false) }
    }

    private fun withController(action: (MediaController) -> Unit) {
        val c = controller
        if (c != null) action(c) else {
            pending += action
            if (future == null) connect()
        }
    }

    // ---------------------------------------------------------------- commands

    /** Starts (or resumes) a book. The service resolves the file and the saved position. */
    fun play(bookId: String, startPositionMs: Long? = null, playWhenReady: Boolean = true) = withController { c ->
        val sameBook = c.currentMediaItem?.mediaId == bookId && c.playbackState != Player.STATE_IDLE
        if (sameBook && startPositionMs == null) {
            if (playWhenReady) c.play()
            return@withController
        }
        if (sameBook && startPositionMs != null) {
            c.seekTo(startPositionMs)
            if (playWhenReady) c.play()
            return@withController
        }
        val item = MediaItem.Builder().setMediaId(bookId).build()
        if (startPositionMs != null) c.setMediaItem(item, startPositionMs) else c.setMediaItem(item)
        c.prepare()
        c.playWhenReady = playWhenReady
    }

    /** Loads the last book paused, so the mini player is ready after an app restart. */
    fun restore(bookId: String) = withController { c ->
        if (c.mediaItemCount == 0) play(bookId, playWhenReady = false)
    }

    fun togglePlayPause() = withController { c ->
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.isPlaying || c.playWhenReady && c.playbackState == Player.STATE_BUFFERING) c.pause() else c.play()
    }

    fun pause() = withController { it.pause() }
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs.coerceAtLeast(0)) }
    fun skipBack() = withController { it.seekBack() }
    fun skipForward() = withController { it.seekForward() }
    fun nextChapter() = withController { it.seekToNext() }
    fun previousChapter() = withController { it.seekToPrevious() }
    fun setSpeed(speed: Float) = withController { it.playbackParameters = PlaybackParameters(speed.coerceIn(0.5f, 3f)) }

    /** Stops playback and clears the player (e.g. the book was removed). */
    fun stop() = withController {
        it.stop()
        it.clearMediaItems()
    }

    fun retry() = withController {
        it.prepare()
        it.play()
    }

    // ---------------------------------------------------------------- state

    private fun publish(p: Player) {
        _state.value = PlayerUiState(
            connected = true,
            bookId = p.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() },
            isPlaying = p.isPlaying,
            playWhenReady = p.playWhenReady,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = p.duration.takeIf { it > 0 } ?: _state.value.durationMs.takeIf { _state.value.bookId == p.currentMediaItem?.mediaId } ?: 0L,
            speed = p.playbackParameters.speed,
            problem = p.playerError?.let(::classify),
        )
        if (p.isPlaying) startTicker() else ticker?.cancel()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch(Dispatchers.Main) {
            while (isActive) {
                val c = controller ?: break
                _state.update { it.copy(positionMs = c.currentPosition.coerceAtLeast(0)) }
                delay(250)
            }
        }
    }

    private fun classify(error: PlaybackException): PlaybackProblem = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> PlaybackProblem.FILE_UNAVAILABLE
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_DECODING_FAILED -> PlaybackProblem.FILE_DAMAGED
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> PlaybackProblem.UNSUPPORTED
        else -> PlaybackProblem.OTHER
    }
}
