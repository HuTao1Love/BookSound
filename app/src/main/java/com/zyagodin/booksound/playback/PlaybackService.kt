package com.zyagodin.booksound.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.os.BundleCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.zyagodin.booksound.BookSoundApp
import com.zyagodin.booksound.MainActivity
import com.zyagodin.booksound.R
import com.zyagodin.booksound.data.library.metadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Hosts the player and its MediaSession. Runs as a foreground service while playing so playback
 * continues with the UI closed or the screen locked, and exposes system media controls
 * (notification, lock screen, Bluetooth/headset buttons, Android Auto/Wear via the session).
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private val container by lazy { (application as BookSoundApp).container }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var exoPlayer: ExoPlayer
    private lateinit var player: AudiobookPlayer
    private var session: MediaSession? = null
    private var periodicSave: Job? = null
    private var currentBookId: String? = null
    /** Speed to apply once the player switches to the given book. */
    private val pendingSpeed = mutableMapOf<String, Float>()
    private var applyingBookSettings = false
    /** Book whose item has reached READY since it was set; only its position is trustworthy. */
    private var readyBookId: String? = null
    /** Voice equalizer in the audio path; its preset follows the book being played. */
    private val voiceEq = VoiceEqAudioProcessor()

    override fun onCreate() {
        super.onCreate()
        val settings = container.settings
        exoPlayer = ExoPlayer.Builder(this, audioOnlyRenderers())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones/Bluetooth disconnect
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            exoPlayer.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger("BookSoundPlayer"))
        }
        player = AudiobookPlayer(
            exoPlayer,
            skipBackMs = { settings.state.value.skipBackSeconds * 1000L },
            skipForwardMs = { settings.state.value.skipForwardSeconds * 1000L },
            smartRewind = {
                settings.state.value.takeIf { it.smartRewind }
                    ?.let { AudiobookPlayer.SmartRewind(it.smartRewindSeconds * 1000L, it.smartRewindAfterSeconds * 1000L) }
            },
        )
        player.addListener(PlayerEvents())

        val openPlayer = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openPlayer)
            .setCallback(SessionCallback())
            .setMediaButtonPreferences(mediaButtons())
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) },
        )
        container.sleepTimer.attach(
            player,
            fadeOut = { settings.state.value.sleepFadeOut },
            shakeToReset = { settings.state.value.shakeToReset },
        )

        // The voice equalizer preset can change from the player sheet or settings at any time.
        scope.launch {
            settings.state.map { it.voicePresetFor(currentBookId) }.distinctUntilChanged().collect { applyVoicePreset() }
        }

        // Keep notification buttons in sync with the configured skip intervals.
        scope.launch {
            settings.state.map { it.skipBackSeconds to it.skipForwardSeconds }.distinctUntilChanged().drop(1).collect {
                session?.setMediaButtonPreferences(mediaButtons())
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * Audiobooks only need an audio renderer; building it here puts [voiceEq] into the audio
     * path (before speed changes, so the filters see the original voice).
     */
    private fun audioOnlyRenderers() = RenderersFactory { handler, _, audioListener, _, _ ->
        val sink = DefaultAudioSink.Builder(this)
            .setAudioProcessors(arrayOf(voiceEq))
            .build()
        arrayOf<Renderer>(MediaCodecAudioRenderer(this, MediaCodecSelector.DEFAULT, handler, audioListener, sink))
    }

    private fun applyVoicePreset() {
        voiceEq.preset = container.settings.state.value.voicePresetFor(currentBookId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        savePosition()
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        savePosition()
        container.sleepTimer.detach(player)
        scope.cancel()
        session?.run {
            release()
            session = null
        }
        exoPlayer.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- notification buttons

    private fun mediaButtons(): List<CommandButton> {
        val s = container.settings.state.value
        val backIcon = when (s.skipBackSeconds) {
            5 -> CommandButton.ICON_SKIP_BACK_5
            10 -> CommandButton.ICON_SKIP_BACK_10
            15 -> CommandButton.ICON_SKIP_BACK_15
            30 -> CommandButton.ICON_SKIP_BACK_30
            else -> CommandButton.ICON_SKIP_BACK
        }
        val forwardIcon = when (s.skipForwardSeconds) {
            5 -> CommandButton.ICON_SKIP_FORWARD_5
            10 -> CommandButton.ICON_SKIP_FORWARD_10
            15 -> CommandButton.ICON_SKIP_FORWARD_15
            30 -> CommandButton.ICON_SKIP_FORWARD_30
            else -> CommandButton.ICON_SKIP_FORWARD
        }
        return listOf(
            CommandButton.Builder(backIcon)
                .setPlayerCommand(Player.COMMAND_SEEK_BACK)
                .setDisplayName(getString(R.string.action_skip_back, s.skipBackSeconds))
                .setSlots(CommandButton.SLOT_BACK)
                .build(),
            CommandButton.Builder(forwardIcon)
                .setPlayerCommand(Player.COMMAND_SEEK_FORWARD)
                .setDisplayName(getString(R.string.action_skip_forward, s.skipForwardSeconds))
                .setSlots(CommandButton.SLOT_FORWARD)
                .build(),
            CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS)
                .setDisplayName(getString(R.string.action_previous_chapter))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
            CommandButton.Builder(CommandButton.ICON_NEXT)
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT)
                .setDisplayName(getString(R.string.action_next_chapter))
                .setSlots(CommandButton.SLOT_OVERFLOW)
                .build(),
        )
    }

    // ---------------------------------------------------------------- persistence

    /** Saves the position of the item currently in the player (never a stale/other book id). */
    private fun savePosition(finished: Boolean? = null) {
        val bookId = player.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() } ?: return
        if (applyingBookSettings) return
        // Until the item has been READY once, currentPosition may not reflect the restored start
        // position yet; saving then could overwrite real progress with 0.
        if (bookId != readyBookId && finished == null) return
        val position = player.currentPosition
        val speed = player.playbackParameters.speed
        container.appScope.launch(Dispatchers.IO) {
            container.library.savePosition(bookId, position, speed, finished)
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
            val bookId = mediaItem?.mediaId?.takeIf { it.isNotEmpty() }
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) readyBookId = null
            bookId?.let { pendingSpeed.remove(it) }?.let { speed ->
                applyingBookSettings = true
                player.playbackParameters = PlaybackParameters(speed)
                applyingBookSettings = false
            }
            if (bookId == currentBookId) return
            currentBookId = bookId
            applyVoicePreset()
            player.chapters = emptyList()
            if (bookId == null) return
            scope.launch {
                player.chapters = container.library.chapters(bookId)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) readyBookId = player.currentMediaItem?.mediaId
            if (playbackState == Player.STATE_IDLE) readyBookId = null
            if (playbackState == Player.STATE_ENDED) {
                savePosition(finished = true)
                container.sleepTimer.cancel()
            }
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            savePosition()
        }

        override fun onPlayerError(error: PlaybackException) {
            val bookId = currentBookId ?: return
            // The file may have been deleted or the folder permission revoked: flag it so the
            // library shows the problem instead of a silent failure.
            scope.launch(Dispatchers.IO) {
                val book = container.library.book(bookId) ?: return@launch
                if (!container.documents.exists(Uri.parse(book.fileUri))) {
                    container.library.setMissing(bookId, System.currentTimeMillis())
                }
            }
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
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            scope.launch {
                val resolved = mediaItems.mapNotNull { buildItem(it.mediaId) }
                future.set(resolved.toMutableList())
            }
            return future
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            scope.launch {
                val bookId = container.settings.current().lastBookId
                if (bookId == null) {
                    future.setException(IllegalStateException("Nothing to resume"))
                } else {
                    future.setFuture(resolve(bookId, null))
                }
            }
            return future
        }

        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
            val event = intent.extras?.let { BundleCompat.getParcelable(it, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) } ?: return false
            if (event.action != KeyEvent.ACTION_DOWN) return event.keyCode in HANDLED_KEYS
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> player.nextChapter()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> player.previousChapter()
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> player.seekForward()
                KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> player.seekBack()
                else -> return false
            }
            return true
        }
    }

    private fun resolve(bookId: String, explicitStart: Long?): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch {
            // Persist the outgoing book before switching (not when re-setting the same book).
            if (player.currentMediaItem?.mediaId != bookId) savePosition()
            readyBookId = null
            val item = buildItem(bookId)
            if (item == null) {
                future.setException(IllegalArgumentException("Unknown book $bookId"))
                return@launch
            }
            val book = container.library.book(bookId)
            val state = container.library.playbackState(bookId)
            val defaultSpeed = container.settings.current().defaultSpeed
            val start = explicitStart ?: when {
                state == null -> 0L
                state.finished -> 0L
                else -> state.positionMs.coerceAtMost(((book?.durationMs ?: 0L) - 1_000).coerceAtLeast(0))
            }
            pendingSpeed[bookId] = state?.speed ?: defaultSpeed
            when {
                // A chosen position (chapter, bookmark) is played exactly as chosen.
                explicitStart != null -> player.restorePause(0L)
                // Another book, or the player was restarted since the pause: smart rewind uses
                // the time the book was last heard. The same loaded book keeps its own pause time.
                player.currentMediaItem?.mediaId != bookId -> player.restorePause(state?.takeIf { !it.finished }?.lastPlayedAt ?: 0L)
            }
            if (bookId == currentBookId) {
                player.chapters = container.library.chapters(bookId)
            }
            container.settings.setLastBook(bookId)
            if (state?.finished == true && explicitStart == null) {
                container.library.setFinished(bookId, false)
            }
            future.set(MediaSession.MediaItemsWithStartPosition(listOf(item), 0, start))
        }
        return future
    }

    private suspend fun buildItem(bookId: String): MediaItem? {
        val book = container.library.book(bookId)?.takeIf { !it.deleted } ?: return null
        val metadata = book.metadata()
        val subtitle = listOfNotNull(metadata.series?.let { s -> metadata.seriesIndex?.let { "$s #$it" } ?: s }).firstOrNull()
        return MediaItem.Builder()
            .setMediaId(book.id)
            .setUri(Uri.parse(book.fileUri))
            .setMimeType("audio/mp4")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(metadata.title)
                    .setArtist(metadata.author)
                    .setAlbumTitle(subtitle ?: metadata.title)
                    .setAlbumArtist(metadata.author)
                    .setComposer(metadata.narrator)
                    .setArtworkUri(book.coverPath?.let { Uri.fromFile(File(it)) })
                    .setDurationMs(book.durationMs)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setExtras(Bundle().apply { putString(EXTRA_BOOK_ID, book.id) })
                    .build(),
            )
            .build()
    }

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        private const val SAVE_INTERVAL_MS = 10_000L
        private val HANDLED_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
        )
    }
}
