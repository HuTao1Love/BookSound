package com.zyagodin.booksound

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.zyagodin.booksound.cover.CoverSearchRepository
import com.zyagodin.booksound.cover.CoverStore
import com.zyagodin.booksound.data.db.AppDatabase
import com.zyagodin.booksound.data.library.LibraryRepository
import com.zyagodin.booksound.data.library.LibraryScanner
import com.zyagodin.booksound.data.settings.SettingsRepository
import com.zyagodin.booksound.importer.ImportAnalyzer
import com.zyagodin.booksound.importer.ImportJournal
import com.zyagodin.booksound.importer.ImportManager
import com.zyagodin.booksound.importer.ImportPipeline
import com.zyagodin.booksound.importer.ImportPlanner
import com.zyagodin.booksound.importer.ImportSessionStore
import com.zyagodin.booksound.importer.RemoteTranscoder
import com.zyagodin.booksound.playback.PlayerConnection
import com.zyagodin.booksound.playback.SleepTimer
import com.zyagodin.booksound.storage.DocumentStore
import com.zyagodin.booksound.sync.RoomSyncLocalStore
import com.zyagodin.booksound.sync.SyncCoordinator
import com.zyagodin.booksound.torrent.NetworkMonitor
import com.zyagodin.booksound.torrent.TorrentEngine
import com.zyagodin.booksound.torrent.TorrentManager
import com.zyagodin.booksound.torrent.TorrentKeepAlive
import com.zyagodin.booksound.torrent.TorrentStore
import com.zyagodin.booksound.update.UpdateManager
import com.zyagodin.booksound.wear.PlaybackOnlyStore
import com.zyagodin.booksound.wear.WatchSender
import com.zyagodin.booksound.wear.WatchSyncBackend
import android.util.Log
import com.zyagodin.booksound.data.library.BookDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class BookSoundApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // The ":converter" process only runs ConverterService; it needs none of the app.
        if (getProcessName() != packageName) return
        container = AppContainer(this)
        container.appScope.launch(Dispatchers.IO) {
            // Undo anything an interrupted import left behind before new work starts.
            container.importJournal.recover()
            container.covers.clearAllDrafts()
            // Then continue torrent downloads and conversions exactly where they stopped.
            container.torrents.start()
        }
        // Positions saved on the watch while the app wasn't running.
        container.sync.requestSync()
        container.appScope.launch {
            container.torrents.needsForeground.collect { needed -> if (needed) TorrentKeepAlive.start(this@BookSoundApp) }
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(container.http)
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("image_cache")).maxSizePercent(0.02).build() }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()
}

/** Manual dependency graph; one instance per process. */
class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** True while an activity is started; used to decide whether to post result notifications. */
    @Volatile var appVisible: Boolean = false

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    val settings = SettingsRepository(app, appScope)
    val database = AppDatabase.create(app)
    val documents = DocumentStore(app)
    val covers = CoverStore(app)
    val library = LibraryRepository(database, documents, covers, settings)
    val sleepTimer = SleepTimer(app, appScope, settings)
    val player = PlayerConnection(app, appScope) { settings.state.value.lastBookId }
    val coverSearch = CoverSearchRepository(http, BuildConfig.GOOGLE_BOOKS_API_KEY)
    val importSessions = ImportSessionStore()
    val importJournal = ImportJournal(app, documents)
    val importAnalyzer = ImportAnalyzer(app, documents, library, covers)

    val importManager: ImportManager = ImportManager(
        context = app,
        scope = appScope,
        pipeline = ImportPipeline(
            documents, library, covers, settings, importJournal,
            transcoder = RemoteTranscoder(app),
            codecLimit = settings.state.map { ImportPipeline.codecLimit(it.parallelCodecs) }
                .stateIn(appScope, SharingStarted.Eagerly, ImportPipeline.codecLimit(settings.state.value.parallelCodecs)),
        ),
        documents = documents,
        covers = covers,
        parallelImports = settings.state.map { it.parallelImports }.stateIn(appScope, SharingStarted.Eagerly, settings.state.value.parallelImports),
        onBookFileChanged = { bookId -> reopenIfPlaying(bookId) },
    )

    val scanner = LibraryScanner(documents, library, covers, settings) { importManager.activeJobIds() }

    val importPlanner = ImportPlanner(app, settings, documents, library)
    val network = NetworkMonitor(app)
    private val torrentStore = TorrentStore(app)

    /** Torrent downloads that become books once downloaded and reviewed. */
    val torrents = TorrentManager(
        context = app,
        scope = appScope,
        store = torrentStore,
        engine = TorrentEngine(torrentStore.sessionStateFile) { message, error -> Log.w("TorrentEngine", message, error) },
        network = network,
        http = http,
        documents = documents,
        analyzer = importAnalyzer,
        planner = importPlanner,
        imports = importManager,
        library = library,
        covers = covers,
    ).also { manager -> importSessions.torrentSessionStarter = manager::loadSession }

    /** Listening positions are shared with the watch app; see [WatchSyncBackend]. */
    val sync = SyncCoordinator(
        appScope,
        PlaybackOnlyStore(RoomSyncLocalStore(database, settings)),
        device = { library.device() },
        backend = WatchSyncBackend(app),
    )

    /** Sends books to the watch app. */
    val watchSender = WatchSender(app, appScope, library)

    /** Updates the phone and watch apps from GitHub releases. */
    val updates = UpdateManager(app, appScope, http)

    /**
     * Details of the book currently loaded in the player, for the mini player and player screen.
     * Without the saved listening state (position, speed, finished): it is saved every few seconds
     * while playing and would redraw both for nothing. The live position is in [PlayerConnection.state].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val nowPlaying: StateFlow<BookDetails?> = player.state
        .map { it.bookId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else library.observeDetails(id) }
        .map { it?.withoutListeningState() }
        .distinctUntilChanged()
        .stateIn(appScope, SharingStarted.Eagerly, null)

    private fun BookDetails.withoutListeningState() =
        copy(item = item.copy(entry = item.entry.copy(positionMs = 0, finished = false, lastPlayedAt = null)), speed = null)

    /** After a book's file was rewritten, reload it in the player at the same position. */
    private fun reopenIfPlaying(bookId: String) {
        appScope.launch(Dispatchers.Main) {
            val state = player.state.value
            if (state.bookId == bookId) player.play(bookId, state.positionMs, playWhenReady = state.playWhenReady)
        }
    }
}
