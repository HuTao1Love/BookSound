package com.zyagodin.booksound.watch.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.ui.WearUnsuitableOutputPlaybackSuppressionResolverListener
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.zyagodin.booksound.core.sync.PlaybackRecord
import com.zyagodin.booksound.playback.AudiobookPlayer
import com.zyagodin.booksound.watch.R
import com.zyagodin.booksound.watch.WatchApp
import com.zyagodin.booksound.watch.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Plays books stored on the watch, in the foreground so it continues with the screen off, and
 * exposes the media session to the watch's media controls and Bluetooth headphones.
 */
@OptIn(UnstableApi::class)
class WatchPlaybackService : MediaSessionService() {

    private val container by lazy { (application as WatchApp).container }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var player: AudiobookPlayer
    private var session: MediaSession? = null
    private var periodicSave: Job? = null
    /** Book whose item has reached READY since it was set; only its position is trustworthy. */
    private var readyBookId: String? = null
    private val currentBook = MutableStateFlow<String?>(null)
    /** Set while applying a position or speed that wasn't chosen here: not saved as listened here. */
    private var following = false
    /** Speed to apply once the player switches to the given book. */
    private val pendingSpeed = mutableMapOf<String, Float>()

    override fun onCreate() {
        super.onCreate()
        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            // Wear OS: without headphones, wait for them instead of playing aloud (unless allowed).
            .setSuppressPlaybackOnUnsuitableOutput(!container.settings.speaker.value)
            .build()
        // Opens the system's Bluetooth picker when playback waits for headphones.
        exoPlayer.addListener(WearUnsuitableOutputPlaybackSuppressionResolverListener(this))
        player = AudiobookPlayer(
            exoPlayer,
            skipBackMs = { SKIP_BACK_MS },
            skipForwardMs = { SKIP_FORWARD_MS },
            smartRewind = { AudiobookPlayer.SmartRewind(SMART_REWIND_MS, SMART_REWIND_AFTER_MS) },
        )
        player.addListener(PlayerEvents())

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) },
        )

        // A position from the phone for the loaded book: a paused player continues from there.
        scope.launch {
            combine(currentBook.filterNotNull(), container.library.books) { id, books -> books.firstOrNull { it.id == id }?.playback }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { followOtherDevice(it) }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        savePosition()
        scope.cancel()
        session?.run {
            release()
            session = null
        }
        exoPlayer.release()
        super.onDestroy()
    }

    private fun followOtherDevice(record: PlaybackRecord) {
        if (record.stamp.updatedBy == container.settings.deviceId) return
        if (player.isPlaying || readyBookId != record.bookId.value || player.currentMediaItem?.mediaId != record.bookId.value) return
        if (abs(player.currentPosition - record.positionMs) < FOLLOW_THRESHOLD_MS && player.playbackParameters.speed == record.speed) return
        withoutSaving {
            player.seekTo(record.positionMs)
            player.playbackParameters = PlaybackParameters(record.speed)
        }
        player.restorePause(record.lastPlayedAt ?: 0L)
    }

    private inline fun withoutSaving(block: () -> Unit) {
        following = true
        try {
            block()
        } finally {
            following = false
        }
    }

    // ---------------------------------------------------------------- persistence

    private fun savePosition(finished: Boolean? = null) {
        val bookId = player.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() } ?: return
        if (following) return
        if (bookId != readyBookId && finished == null) return
        val position = player.currentPosition
        val speed = player.playbackParameters.speed
        val publish = !player.isPlaying
        container.scope.launch {
            val record = container.library.savePosition(bookId, position, speed, finished) ?: return@launch
            if (publish) container.positions.publish(record)
        }
    }

    private fun startPeriodicSave() {
        if (periodicSave?.isActive == true) return
        periodicSave = scope.launch {
            while (isActive) {
                delay(SAVE_INTERVAL_MS)
                savePosition()
            }
        }
    }

    private inner class PlayerEvents : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) startPeriodicSave() else {
                periodicSave?.cancel()
                savePosition()
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) savePosition()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) readyBookId = null
            val bookId = mediaItem?.mediaId?.takeIf { it.isNotEmpty() }
            bookId?.let { pendingSpeed.remove(it) }?.let { speed -> withoutSaving { player.playbackParameters = PlaybackParameters(speed) } }
            currentBook.value = bookId
            player.chapters = bookId?.let { container.library.book(it)?.chapters }.orEmpty()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) readyBookId = player.currentMediaItem?.mediaId
            if (playbackState == Player.STATE_IDLE) readyBookId = null
            if (playbackState == Player.STATE_ENDED) savePosition(finished = true)
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            savePosition()
        }
    }

    // ---------------------------------------------------------------- session

    private inner class SessionCallback : MediaSession.Callback {
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val bookId = mediaItems.getOrNull(maxOf(startIndex, 0))?.mediaId
                ?: return Futures.immediateFailedFuture(IllegalArgumentException("No media id"))
            return resolve(bookId, startPositionMs.takeIf { it != C.TIME_UNSET })
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.mapNotNull { buildItem(it.mediaId) }.toMutableList())

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val bookId = container.settings.lastBookId ?: return Futures.immediateFailedFuture(IllegalStateException("Nothing to resume"))
            return resolve(bookId, null)
        }
    }

    private fun resolve(bookId: String, explicitStart: Long?): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        if (player.currentMediaItem?.mediaId != bookId) savePosition()
        val book = container.library.book(bookId)
        val item = buildItem(bookId)
        if (book == null || item == null) return Futures.immediateFailedFuture(IllegalArgumentException("Unknown book $bookId"))
        readyBookId = null
        val state = book.playback
        val start = explicitStart ?: when {
            state == null || state.finished -> 0L
            else -> state.positionMs.coerceAtMost((book.durationMs - 1_000).coerceAtLeast(0))
        }
        pendingSpeed[bookId] = state?.speed ?: 1f
        when {
            explicitStart != null -> player.restorePause(0L)
            player.currentMediaItem?.mediaId != bookId -> player.restorePause(state?.takeIf { !it.finished }?.lastPlayedAt ?: 0L)
        }
        player.chapters = book.chapters
        container.settings.lastBookId = bookId
        return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(listOf(item), 0, start))
    }

    private fun buildItem(bookId: String): MediaItem? {
        val book = container.library.book(bookId) ?: return null
        val file = container.library.audioFile(bookId).takeIf { it.exists() } ?: return null
        val cover = container.library.coverFile(bookId).takeIf { it.exists() }
        return MediaItem.Builder()
            .setMediaId(bookId)
            .setUri(Uri.fromFile(file))
            .setMimeType("audio/mp4")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(book.metadata.title)
                    .setArtist(book.metadata.author)
                    .setArtworkUri(cover?.let { Uri.fromFile(it) })
                    .setDurationMs(book.durationMs)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 10_000L
        const val FOLLOW_THRESHOLD_MS = 2_000L
        const val SKIP_BACK_MS = 15_000L
        const val SKIP_FORWARD_MS = 30_000L
        const val SMART_REWIND_MS = 10_000L
        const val SMART_REWIND_AFTER_MS = 60_000L
    }
}
