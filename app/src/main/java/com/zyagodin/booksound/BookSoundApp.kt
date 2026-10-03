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
import com.zyagodin.booksound.importer.AudioTranscoder
import com.zyagodin.booksound.importer.ImportAnalyzer
import com.zyagodin.booksound.importer.ImportJournal
import com.zyagodin.booksound.importer.ImportManager
import com.zyagodin.booksound.importer.ImportPipeline
import com.zyagodin.booksound.importer.ImportSessionStore
import com.zyagodin.booksound.playback.PlayerConnection
import com.zyagodin.booksound.playback.SleepTimer
import com.zyagodin.booksound.storage.DocumentStore
import com.zyagodin.booksound.sync.RoomSyncLocalStore
import com.zyagodin.booksound.sync.SyncCoordinator
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
        container = AppContainer(this)
        container.appScope.launch(Dispatchers.IO) {
            // Undo anything an interrupted import left behind before new work starts.
            container.importJournal.recover()
            container.covers.clearAllDrafts()
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
    val sleepTimer = SleepTimer(appScope)
    val player = PlayerConnection(app, appScope) { settings.state.value.lastBookId }
    val coverSearch = CoverSearchRepository(http)
    val importSessions = ImportSessionStore()
    val importJournal = ImportJournal(app, documents)
    val importAnalyzer = ImportAnalyzer(app, documents, library, covers)

    val importManager: ImportManager = ImportManager(
        context = app,
        scope = appScope,
        pipeline = ImportPipeline(documents, library, covers, settings, importJournal, AudioTranscoder(app)),
        documents = documents,
        covers = covers,
        onBookFileChanged = { bookId -> reopenIfPlaying(bookId) },
    )

    val scanner = LibraryScanner(documents, library, covers, settings) { importManager.activeJobIds() }

    val sync = SyncCoordinator(appScope, RoomSyncLocalStore(database, settings), device = { library.device() })

    /** Details of the book currently loaded in the player, for the mini player and player screen. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val nowPlaying: StateFlow<BookDetails?> = player.state
        .map { it.bookId }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else library.observeDetails(id) }
        .stateIn(appScope, SharingStarted.Eagerly, null)

    /** After a book's file was rewritten, reload it in the player at the same position. */
    private fun reopenIfPlaying(bookId: String) {
        appScope.launch(Dispatchers.Main) {
            val state = player.state.value
            if (state.bookId == bookId) player.play(bookId, state.positionMs, playWhenReady = state.playWhenReady)
        }
    }
}
