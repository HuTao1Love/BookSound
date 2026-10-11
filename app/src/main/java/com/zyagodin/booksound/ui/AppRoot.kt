package com.zyagodin.booksound.ui

import android.net.Uri
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.zyagodin.booksound.AppContainer
import com.zyagodin.booksound.MainActivity
import com.zyagodin.booksound.ui.components.BackgroundWork
import com.zyagodin.booksound.ui.components.BackgroundWorkDialog
import com.zyagodin.booksound.ui.components.MiniPlayer
import com.zyagodin.booksound.ui.components.rememberBackgroundWorkRequest
import com.zyagodin.booksound.ui.components.MiniPlayerHeight
import com.zyagodin.booksound.ui.detail.BookDetailScreen
import com.zyagodin.booksound.ui.detail.DetailPlaceholder
import com.zyagodin.booksound.ui.importer.CoverPickerScreen
import com.zyagodin.booksound.ui.importer.ImportEditorScreen
import com.zyagodin.booksound.ui.importer.ImportsScreen
import com.zyagodin.booksound.ui.library.LibraryScreen
import com.zyagodin.booksound.ui.library.SeriesScreen
import com.zyagodin.booksound.ui.navigation.BookKey
import com.zyagodin.booksound.ui.navigation.CoverPickerKey
import com.zyagodin.booksound.ui.navigation.ImportEditorKey
import com.zyagodin.booksound.ui.navigation.ImportsKey
import com.zyagodin.booksound.ui.navigation.LibraryKey
import com.zyagodin.booksound.ui.navigation.RemovedBooksKey
import com.zyagodin.booksound.ui.navigation.SeriesKey
import com.zyagodin.booksound.ui.navigation.SettingsKey
import com.zyagodin.booksound.ui.onboarding.OnboardingScreen
import com.zyagodin.booksound.ui.player.PlayerScreen
import com.zyagodin.booksound.ui.player.PlayerSheetState
import com.zyagodin.booksound.ui.player.rememberPlayerSheetState
import com.zyagodin.booksound.ui.settings.RemovedBooksScreen
import com.zyagodin.booksound.ui.settings.SettingsScreen
import com.zyagodin.booksound.ui.theme.BookSoundTheme
import com.zyagodin.booksound.ui.torrent.AddTorrentDialog
import com.zyagodin.booksound.torrent.TorrentSource
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The "Add torrent" dialog is open; [initial] is a link or file handed over by another app. */
private class AddTorrentRequest(val initial: TorrentSource?)

/** Extra bottom padding screens must leave for the floating mini player. */
val LocalBottomOverlayPadding = compositionLocalOf { 0.dp }

/** Navigation actions available to every screen. */
class AppNavigator(
    private val backStack: NavBackStack<NavKey>,
    private val showAddTorrent: (TorrentSource?) -> Unit = {},
    /** The library and a book are shown side by side. */
    private val twoPane: () -> Boolean = { false },
    /** The player is not a screen in the back stack but slides over them; see [PlayerSheetState]. */
    private val player: PlayerSheetState? = null,
) {
    /** Text to search the library for, asked for from another screen; the library takes it. */
    var pendingSearch by mutableStateOf<String?>(null)

    fun openBook(bookId: String) {
        player?.collapse()
        // In list-detail mode, selecting another book replaces the open detail instead of stacking.
        if (backStack.lastOrNull() is BookKey) backStack[backStack.lastIndex] = BookKey(bookId) else backStack.add(BookKey(bookId))
    }

    fun openSeries(series: String) {
        // In list-detail mode the library stays visible next to a book: the series replaces that book.
        if (backStack.lastOrNull() is BookKey) backStack.removeLastOrNull()
        if ((backStack.lastOrNull() as? SeriesKey)?.series != series) backStack.add(SeriesKey(series))
    }

    /** Searches the library, e.g. for an author tapped on a book's page. */
    fun searchLibrary(text: String) {
        // Side by side the library is next to the book: the book stays open.
        val book = (backStack.lastOrNull() as? BookKey)?.takeIf { twoPane() }
        backToLibrary()
        if (book != null) backStack.add(book)
        pendingSearch = text
    }

    fun openPlayer() {
        player?.expand()
    }

    fun openImportEditor(sessionId: String) {
        player?.collapse()
        backStack.add(ImportEditorKey(sessionId))
    }

    fun openCoverPicker(sessionId: String) = backStack.add(CoverPickerKey(sessionId))

    fun openImports(replaceCurrent: Boolean = false) {
        player?.collapse()
        if (replaceCurrent) backStack.removeLastOrNull()
        if (backStack.lastOrNull() != ImportsKey) backStack.add(ImportsKey)
    }

    /** Opens the "Add torrent" dialog, optionally with a link or file opened from another app. */
    fun addTorrent(initial: TorrentSource? = null) = showAddTorrent(initial)

    fun openSettings() = backStack.add(SettingsKey)
    fun openRemovedBooks() = backStack.add(RemovedBooksKey)
    fun back() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }

    fun backToLibrary() {
        player?.collapse()
        while (backStack.size > 1) backStack.removeLastOrNull()
    }
}

@Composable
fun AppRoot(container: AppContainer, intents: Flow<String>) {
    val settings by container.settings.state.collectAsStateWithLifecycle()
    BookSoundTheme(settings.themeMode, settings.amoledBlack) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            val treeUri = settings.libraryTreeUri
            var accessVersion by remember { mutableIntStateOf(0) }
            val hasAccess = remember(treeUri, accessVersion) {
                treeUri != null && container.documents.hasPersistedPermission(Uri.parse(treeUri), write = true)
            }
            when {
                !settings.loaded -> Box(Modifier.fillMaxSize())
                !hasAccess -> OnboardingScreen(folderLost = treeUri != null, onGranted = { accessVersion++ })
                else -> MainNavigation(container, intents)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun MainNavigation(container: AppContainer, intents: Flow<String>) {
    val backStack = rememberNavBackStack(LibraryKey)
    var addTorrent by remember { mutableStateOf<AddTorrentRequest?>(null) }
    // On a wide window the library and the book split the screen roughly in half (the Fold's inner
    // screen folds right there); the default 360 dp list pane is too narrow for the library.
    val windowWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
    val listPaneWidth = (windowWidth / 2).coerceIn(360.dp, 520.dp)
    val directive = calculatePaneScaffoldDirective(currentWindowAdaptiveInfoV2()).copy(defaultPanePreferredWidth = listPaneWidth)
    val twoPane = rememberUpdatedState(directive.maxHorizontalPartitions > 1)
    val player = rememberPlayerSheetState()
    val navigator = remember(backStack, player) {
        AppNavigator(backStack, showAddTorrent = { addTorrent = AddTorrentRequest(it) }, twoPane = { twoPane.value }, player = player)
    }
    val listDetail = rememberListDetailSceneStrategy<NavKey>(directive = directive)
    // The position changes four times a second while playing: only the mini player reads it, the
    // navigation follows whether a book is loaded.
    val playerState = container.player.state.collectAsStateWithLifecycle()
    val hasBook by remember { derivedStateOf { playerState.value.hasBook } }
    val nowPlaying by container.nowPlaying.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var askBackgroundWork by remember { mutableStateOf(false) }
    val requestBackgroundWork = rememberBackgroundWorkRequest { }

    /** Downloads run in the background: once, ask to be exempt from battery optimization. */
    fun maybeAskBackgroundWork() {
        if (!container.settings.state.value.backgroundPromptShown && !BackgroundWork.isUnrestricted(context)) askBackgroundWork = true
    }

    LaunchedEffect(Unit) {
        container.scanner.scan()
    }
    LaunchedEffect(intents) {
        intents.collect { action ->
            when (action) {
                MainActivity.ACTION_OPEN_PLAYER -> if (container.player.state.value.hasBook) navigator.openPlayer()
                MainActivity.ACTION_OPEN_IMPORTS -> navigator.openImports()
                else -> when {
                    action.startsWith(MainActivity.TORRENT_LINK_PREFIX) ->
                        navigator.addTorrent(TorrentSource.Link(action.removePrefix(MainActivity.TORRENT_LINK_PREFIX)))
                    action.startsWith(MainActivity.TORRENT_FILE_PREFIX) ->
                        navigator.addTorrent(TorrentSource.File(Uri.parse(action.removePrefix(MainActivity.TORRENT_FILE_PREFIX))))
                }
            }
        }
    }

    val top = backStack.lastOrNull()
    val playerShown by remember { derivedStateOf { player.fraction > 0f } }
    // Fully open, the screens below are hidden from accessibility services as they were when the
    // player was a screen of its own.
    val playerCovers by remember { derivedStateOf { player.fraction >= 1f } }
    val showMiniPlayer = nowPlaying != null && hasBook &&
        (top == LibraryKey || top is SeriesKey || top is BookKey || top == ImportsKey || top == SettingsKey)
    // Two panes side by side: the bar docks under the list pane instead of straddling both.
    val listPaneShown = top == LibraryKey || top is SeriesKey ||
        (top is BookKey && backStack.getOrNull(backStack.lastIndex - 1).let { it == LibraryKey || it is SeriesKey })
    val besideDetail = directive.maxHorizontalPartitions > 1 && listPaneShown
    // Screens add the navigation bar inset themselves; the docked bar sits right on top of it.
    val overlay: Dp = if (showMiniPlayer) MiniPlayerHeight else 0.dp

    Box(Modifier.fillMaxSize().onSizeChanged { player.travel = it.height.toFloat() }) {
        CompositionLocalProvider(LocalBottomOverlayPadding provides overlay) {
            NavDisplay(
                modifier = if (playerCovers) Modifier.clearAndSetSemantics { } else Modifier,
                backStack = backStack,
                onBack = { backStack.removeLastOrNull() },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                sceneStrategies = listOf(listDetail),
                entryProvider = entryProvider {
                    entry<LibraryKey>(metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { DetailPlaceholder() })) {
                        LibraryScreen(navigator)
                    }
                    entry<SeriesKey>(metadata = ListDetailSceneStrategy.listPane(detailPlaceholder = { DetailPlaceholder() })) { key ->
                        SeriesScreen(key.series, navigator)
                    }
                    entry<BookKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
                        BookDetailScreen(key.bookId, navigator)
                    }
                    entry<ImportEditorKey> { key -> ImportEditorScreen(key.sessionId, navigator) }
                    entry<CoverPickerKey> { key -> CoverPickerScreen(key.sessionId, navigator) }
                    entry<ImportsKey> { ImportsScreen(navigator) }
                    entry<SettingsKey> { SettingsScreen(navigator) }
                    entry<RemovedBooksKey> { RemovedBooksScreen(navigator) }
                },
            )
        }
        addTorrent?.let { request ->
            AddTorrentDialog(
                initial = request.initial,
                onDismiss = { addTorrent = null },
                onAdded = { id ->
                    addTorrent = null
                    navigator.openImportEditor(container.importSessions.torrent(id).id)
                    maybeAskBackgroundWork()
                },
                onAlreadyAdded = {
                    addTorrent = null
                    navigator.openImports()
                },
                onAddedSeveral = {
                    addTorrent = null
                    navigator.openImports()
                    maybeAskBackgroundWork()
                },
            )
        }
        if (askBackgroundWork) {
            fun answered() {
                askBackgroundWork = false
                scope.launch { container.settings.setBackgroundPromptShown() }
            }
            BackgroundWorkDialog(
                onAllow = {
                    answered()
                    requestBackgroundWork()
                },
                onLater = ::answered,
            )
        }
        if (playerShown) PlayerOverlay(player, navigator)
        AnimatedVisibility(
            visible = showMiniPlayer,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = (if (besideDetail) Modifier.align(Alignment.BottomStart).width(listPaneWidth) else Modifier.align(Alignment.BottomCenter))
                .then(if (playerShown) Modifier.clearAndSetSemantics { } else Modifier)
                // Rides on the rising player's top edge, fading out as it goes.
                .graphicsLayer {
                    translationY = -player.fraction * player.travel
                    alpha = (1f - player.fraction * 4f).coerceAtLeast(0f)
                },
        ) {
            nowPlaying?.let { book ->
                MiniPlayer(
                    book = book,
                    state = { playerState.value },
                    onOpen = navigator::openPlayer,
                    drag = player.draggable,
                    onDragStopped = player::settle,
                    onTogglePlay = container.player::togglePlayPause,
                    onSkipBack = container.player::skipBack,
                )
            }
        }
    }
}

/**
 * The player over the screens, pulled up [PlayerSheetState.fraction] of the way; the screens below
 * dim as it rises. Drags its content leaves alone (the chapter list scrolls, the seek bar seeks)
 * move it.
 */
@Composable
private fun PlayerOverlay(state: PlayerSheetState, navigator: AppNavigator) {
    PredictiveBackHandler(enabled = state.isOpen) { progress ->
        state.followBack(progress.map { it.progress })
    }
    // Its own view models, cleared when it closes as they were when the player was a screen.
    val owner = remember {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind { drawRect(Color.Black, alpha = 0.4f * state.fraction) },
    )
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = (1f - state.fraction) * state.travel
                // The mini player's corners, squaring off as it opens.
                val corner = 20.dp.toPx() * (1f - state.fraction)
                shape = RoundedCornerShape(topStart = corner, topEnd = corner)
                clip = true
            }
            .draggable(state.draggable, Orientation.Vertical, onDragStopped = { state.settle(it) })
            .background(MaterialTheme.colorScheme.background),
    ) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            PlayerScreen(navigator, onClose = state::collapse)
        }
    }
}
