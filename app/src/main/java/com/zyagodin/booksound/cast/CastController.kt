package com.zyagodin.booksound.cast

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.images.WebImage
import com.zyagodin.booksound.data.library.LibraryRepository
import com.zyagodin.booksound.data.library.metadata
import com.zyagodin.booksound.data.settings.SettingsRepository
import com.zyagodin.booksound.playback.PlayerConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

/** A Cast device found on the network. */
data class CastTarget(val id: String, val name: String, val description: String?, val kind: Kind) {
    enum class Kind { TV, SPEAKER, GROUP }
}

data class CastUiState(
    /** False without Google Play services' Cast support. */
    val supported: Boolean = true,
    val devices: List<CastTarget> = emptyList(),
    val session: CastSessionState? = null,
    /** Why the last attempt failed, until the next one. */
    val problem: CastProblem? = null,
)

enum class CastProblem { NO_NETWORK, CONNECTION_FAILED, PLAYBACK_FAILED }

data class CastSessionState(
    val deviceName: String,
    val stage: Stage,
    val bookId: String? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val speed: Float = 1f,
) {
    enum class Stage { CONNECTING, LOADING, CASTING }
}

/**
 * Plays the current book on a Google Cast device (a Chromecast, a TV, a Nest speaker): the book
 * file is lent over the local network by [LanServer], and the device's Default Media Receiver
 * streams it from there. While casting, the phone's player stays paused and the player screen
 * controls the device.
 */
class CastController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val player: PlayerConnection,
) {
    private val server = LanServer(context)
    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
        .build()
    private val router by lazy { MediaRouter.getInstance(context) }
    private var cast: CastContext? = null
    private var discovering = false

    private val _state = MutableStateFlow(CastUiState())
    val state: StateFlow<CastUiState> = _state

    val isCasting: Boolean get() = _state.value.session?.stage == CastSessionState.Stage.CASTING

    /** Sets Cast up, so a cast that outlived the app is picked up again; when the player opens. */
    fun prepare() {
        scope.launch(Dispatchers.Main) { castContext() }
    }

    // ---------------------------------------------------------------- discovery

    /** Looks for devices while the cast sheet is open; actively, so they show up quickly. */
    fun startDiscovery() = scope.launch(Dispatchers.Main) {
        if (castContext() == null || discovering) return@launch
        discovering = true
        router.addCallback(selector, routes, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        publishRoutes()
    }

    fun stopDiscovery() = scope.launch(Dispatchers.Main) {
        if (!discovering) return@launch
        discovering = false
        router.removeCallback(routes)
    }

    private val routes = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
    }

    private fun publishRoutes() {
        val devices = router.routes
            .filter { !it.isDefaultOrBluetooth && it.isEnabled && it.matchesSelector(selector) }
            .map { route ->
                val kind = when (route.deviceType) {
                    MediaRouter.RouteInfo.DEVICE_TYPE_TV -> CastTarget.Kind.TV
                    MediaRouter.RouteInfo.DEVICE_TYPE_GROUP -> CastTarget.Kind.GROUP
                    else -> if (route.isGroup) CastTarget.Kind.GROUP else CastTarget.Kind.SPEAKER
                }
                CastTarget(route.id, route.name, route.description?.takeIf { it.isNotBlank() }, kind)
            }
            .sortedBy { it.name.lowercase() }
        _state.update { it.copy(devices = devices) }
    }

    // ---------------------------------------------------------------- session

    /** Connects to [target]; the book is sent once the session starts. */
    fun connect(target: CastTarget) = scope.launch(Dispatchers.Main) {
        if (castContext() == null) return@launch
        val route = router.routes.firstOrNull { it.id == target.id } ?: return@launch
        _state.update { it.copy(session = CastSessionState(target.name, CastSessionState.Stage.CONNECTING), problem = null) }
        router.selectRoute(route)
    }

    /** Stops casting; the phone continues from where the device was. */
    fun disconnect() = scope.launch(Dispatchers.Main) {
        cast?.sessionManager?.endCurrentSession(true)
        _state.update { it.copy(session = null) }
    }

    fun togglePlay() = withClient { client ->
        if (client.isPlaying || client.isBuffering) client.pause() else client.play()
    }

    fun seekTo(positionMs: Long) = withClient { client ->
        client.seek(MediaSeekOptions.Builder().setPosition(positionMs.coerceAtLeast(0)).build())
        _state.update { s -> s.copy(session = s.session?.copy(positionMs = positionMs.coerceAtLeast(0))) }
    }

    fun seekBy(deltaMs: Long) = seekTo((_state.value.session?.positionMs ?: 0L) + deltaMs)

    fun setSpeed(speed: Float) = withClient { client ->
        // The Default Media Receiver plays 0.5×–2×.
        client.setPlaybackRate(speed.coerceIn(0.5f, 2f).toDouble())
    }

    private fun withClient(action: (RemoteMediaClient) -> Unit) = scope.launch(Dispatchers.Main) {
        cast?.sessionManager?.currentCastSession?.remoteMediaClient?.let(action)
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            _state.update { it.copy(session = CastSessionState(deviceName(session), CastSessionState.Stage.CONNECTING), problem = null) }
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            scope.launch(Dispatchers.Main) { load(session) }
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            // After the app was restarted mid-cast: carry on with what the device plays.
            attach(session)
            val status = session.remoteMediaClient?.mediaStatus
            if (status == null) scope.launch(Dispatchers.Main) { load(session) } else publishStatus(session)
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) = failed(CastProblem.CONNECTION_FAILED)

        override fun onSessionEnding(session: CastSession) = handBack(session)

        override fun onSessionEnded(session: CastSession, error: Int) {
            session.remoteMediaClient?.run {
                unregisterCallback(statusCallback)
                removeProgressListener(progress)
            }
            server.stop()
            _state.update { it.copy(session = null) }
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            _state.update { it.copy(session = null) }
        }
    }

    private suspend fun load(session: CastSession) {
        val client = session.remoteMediaClient ?: return failed(CastProblem.CONNECTION_FAILED)
        val local = player.state.value
        val bookId = local.bookId ?: settings.current().lastBookId ?: return failed(CastProblem.PLAYBACK_FAILED)
        val book = withContext(Dispatchers.IO) { library.book(bookId) } ?: return failed(CastProblem.PLAYBACK_FAILED)
        val saved = withContext(Dispatchers.IO) { library.playbackState(bookId) }
        val position = if (local.bookId == bookId && local.connected) local.positionMs else saved?.positionMs ?: 0L
        val speed = if (local.bookId == bookId && local.connected) local.speed else saved?.speed ?: 1f
        _state.update { it.copy(session = CastSessionState(deviceName(session), CastSessionState.Stage.LOADING, bookId, positionMs = position, speed = speed)) }

        val links = withContext(Dispatchers.IO) {
            val audio = server.share(Uri.parse(book.fileUri), "audio/mp4", book.fileSize, "book.m4b")
            val cover = book.coverPath?.let { File(it) }?.takeIf { it.exists() }?.let { server.share(Uri.fromFile(it), "image/jpeg", it.length(), "cover.jpg") }
            audio to cover
        }
        val audioUrl = links.first ?: run {
            cast?.sessionManager?.endCurrentSession(true)
            return failed(CastProblem.NO_NETWORK)
        }
        val metadata = book.metadata()
        val castMetadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MUSIC_TRACK).apply {
            putString(MediaMetadata.KEY_TITLE, metadata.title)
            metadata.author?.let { putString(MediaMetadata.KEY_ARTIST, it) }
            putString(MediaMetadata.KEY_ALBUM_TITLE, metadata.series ?: metadata.title)
            links.second?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val media = MediaInfo.Builder(audioUrl)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType("audio/mp4")
            .setMetadata(castMetadata)
            .setStreamDuration(book.durationMs.takeIf { it > 0 } ?: MediaInfo.UNKNOWN_DURATION)
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(media)
            .setAutoplay(true)
            .setCurrentTime(position)
            .setPlaybackRate(speed.coerceIn(0.5f, 2f).toDouble())
            .build()

        // One book at a time: the phone goes quiet while the device plays.
        player.pause()
        attach(session)
        client.load(request).setResultCallback { result ->
            if (!result.status.isSuccess) {
                Log.w(TAG, "Load failed: ${result.status}")
                failed(CastProblem.PLAYBACK_FAILED)
            }
        }
    }

    private fun attach(session: CastSession) {
        session.remoteMediaClient?.run {
            unregisterCallback(statusCallback)
            registerCallback(statusCallback)
            removeProgressListener(progress)
            addProgressListener(progress, PROGRESS_INTERVAL_MS)
        }
    }

    private val statusCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            cast?.sessionManager?.currentCastSession?.let(::publishStatus)
        }
    }

    private val progress = RemoteMediaClient.ProgressListener { positionMs, _ ->
        _state.update { s -> s.copy(session = s.session?.copy(positionMs = positionMs)) }
    }

    private fun publishStatus(session: CastSession) {
        val client = session.remoteMediaClient ?: return
        val status = client.mediaStatus ?: return
        if (status.playerState == MediaStatus.PLAYER_STATE_IDLE && status.idleReason == MediaStatus.IDLE_REASON_ERROR) {
            failed(CastProblem.PLAYBACK_FAILED)
            return
        }
        _state.update { s ->
            val current = s.session ?: CastSessionState(deviceName(session), CastSessionState.Stage.CASTING, bookId = player.state.value.bookId)
            s.copy(
                session = current.copy(
                    stage = if (status.playerState == MediaStatus.PLAYER_STATE_IDLE && current.stage == CastSessionState.Stage.LOADING) current.stage else CastSessionState.Stage.CASTING,
                    playing = status.playerState == MediaStatus.PLAYER_STATE_PLAYING,
                    buffering = status.playerState == MediaStatus.PLAYER_STATE_BUFFERING || status.playerState == MediaStatus.PLAYER_STATE_LOADING,
                    positionMs = client.approximateStreamPosition,
                    speed = status.playbackRate.toFloat(),
                ),
            )
        }
    }

    /** The device's position becomes the book's position on the phone. */
    private fun handBack(session: CastSession) {
        val cast = _state.value.session ?: return
        val bookId = cast.bookId ?: return
        if (cast.stage != CastSessionState.Stage.CASTING) return
        val position = session.remoteMediaClient?.approximateStreamPosition?.takeIf { it > 0 } ?: cast.positionMs
        val local = player.state.value
        if (local.connected && local.bookId == bookId) {
            player.seekTo(position)
        } else {
            scope.launch(Dispatchers.IO) { library.savePosition(bookId, position, cast.speed, null) }
        }
    }

    private fun failed(problem: CastProblem) {
        _state.update { it.copy(problem = problem, session = if (problem == CastProblem.PLAYBACK_FAILED) it.session else null) }
        if (problem == CastProblem.PLAYBACK_FAILED) cast?.sessionManager?.endCurrentSession(true)
    }

    fun clearProblem() = _state.update { it.copy(problem = null) }

    private fun deviceName(session: CastSession): String = session.castDevice?.friendlyName.orEmpty()

    /** The shared CastContext, or null where Cast isn't available (no Play services). */
    private suspend fun castContext(): CastContext? {
        cast?.let { return it }
        return try {
            CastContext.getSharedInstance(context, ContextCompat.getMainExecutor(context)).await().also {
                cast = it
                it.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cast is not available", e)
            _state.update { it.copy(supported = false) }
            null
        }
    }

    private companion object {
        const val TAG = "CastController"
        const val PROGRESS_INTERVAL_MS = 500L
    }
}
