package com.zyagodin.booksound.watch.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class WatchPlayerState(
    val bookId: String? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val isBuffering: Boolean = false,
    /** Waiting for Bluetooth headphones (see WatchSettings.speaker). */
    val waitingForHeadphones: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val hasError: Boolean = false,
)

/** The UI's handle on [WatchPlaybackService], through a MediaController. */
class WatchPlayer(private val context: Context) {
    private val _state = MutableStateFlow(WatchPlayerState())
    val state: StateFlow<WatchPlayerState> = _state

    private var future: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish(player)
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, WatchPlaybackService::class.java))
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
            pending.toList().also { pending.clear() }.forEach { it(c) }
        }, ContextCompat.getMainExecutor(context))
    }

    fun disconnect() {
        controller?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
    }

    /** Re-reads the live position; the UI calls it while visible and playing. */
    fun refreshPosition() {
        controller?.let { c -> _state.value = _state.value.copy(positionMs = c.currentPosition.coerceAtLeast(0)) }
    }

    private fun withController(action: (MediaController) -> Unit) {
        controller?.let(action) ?: run {
            pending += action
            connect()
        }
    }

    fun play(bookId: String, startPositionMs: Long? = null) = withController { c ->
        val sameBook = c.currentMediaItem?.mediaId == bookId && c.playbackState != Player.STATE_IDLE
        when {
            sameBook && startPositionMs == null -> Unit
            sameBook -> c.seekTo(startPositionMs!!)
            else -> {
                val item = MediaItem.Builder().setMediaId(bookId).build()
                if (startPositionMs != null) c.setMediaItem(item, startPositionMs) else c.setMediaItem(item)
                c.prepare()
            }
        }
        c.play()
    }

    fun togglePlayPause() = withController { c ->
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playWhenReady) c.pause() else c.play()
    }

    fun skipBack() = withController { it.seekBack() }
    fun skipForward() = withController { it.seekForward() }
    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs.coerceAtLeast(0)) }
    fun setSpeed(speed: Float) = withController { it.playbackParameters = PlaybackParameters(speed) }

    fun stop() = withController {
        it.stop()
        it.clearMediaItems()
    }

    /** Restarts the service so it picks up the speaker setting (fixed when the player is built). */
    fun restartIfIdle() {
        if (_state.value.isPlaying) return
        disconnect()
        context.stopService(Intent(context, WatchPlaybackService::class.java))
        connect()
    }

    private fun publish(p: Player) {
        _state.value = WatchPlayerState(
            bookId = p.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() },
            isPlaying = p.isPlaying,
            playWhenReady = p.playWhenReady,
            isBuffering = p.playbackState == Player.STATE_BUFFERING,
            waitingForHeadphones = p.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT,
            positionMs = p.currentPosition.coerceAtLeast(0),
            durationMs = p.duration.takeIf { it > 0 } ?: 0L,
            speed = p.playbackParameters.speed,
            hasError = p.playerError != null,
        )
    }
}
