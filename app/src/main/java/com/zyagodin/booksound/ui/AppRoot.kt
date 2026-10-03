package com.zyagodin.booksound.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.navigation3.ListDetailSceneStrategy
import androidx.compose.material3.adaptive.navigation3.rememberListDetailSceneStrategy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.zyagodin.booksound.ui.navigation.BookKey
import com.zyagodin.booksound.ui.navigation.CoverPickerKey
import com.zyagodin.booksound.ui.navigation.ImportEditorKey
import com.zyagodin.booksound.ui.navigation.ImportsKey
import com.zyagodin.booksound.ui.navigation.LibraryKey
import com.zyagodin.booksound.ui.navigation.PlayerKey
import com.zyagodin.booksound.ui.navigation.RemovedBooksKey
import com.zyagodin.booksound.ui.navigation.SettingsKey
import com.zyagodin.booksound.ui.onboarding.OnboardingScreen
import com.zyagodin.booksound.ui.player.PlayerScreen
import com.zyagodin.booksound.ui.settings.RemovedBooksScreen
import com.zyagodin.booksound.ui.settings.SettingsScreen
import com.zyagodin.booksound.ui.theme.BookSoundTheme
import com.zyagodin.booksound.ui.torrent.AddTorrentDialog
import com.zyagodin.booksound.torrent.TorrentSource
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** The "Add torrent" dialog is open; [initial] is a link or file handed over by another app. */
private class AddTorrentRequest(val initial: TorrentSource?)

/** Extra bottom padding screens must leave for the floating mini player. */
val LocalBottomOverlayPadding = compositionLocalOf { 0.dp }

/** Navigation actions available to every screen. */
class AppNavigator(
    private val backStack: NavBackStack<NavKey>,
    private val showAddTorrent: (TorrentSource?) -> Unit = {},
) {
    fun openBook(bookId: String) {
        // In list-detail mode, selecting another book replaces the open detail instead of stacking.
        if (backStack.lastOrNull() is BookKey) backStack[backStack.lastIndex] = BookKey(bookId) else backStack.add(BookKey(bookId))
    }

    fun openPlayer() {
        if (backStack.lastOrNull() != PlayerKey) backStack.add(PlayerKey)
    }

    fun openImportEditor(sessionId: String) = backStack.add(ImportEditorKey(sessionId))
    fun openCoverPicker(sessionId: String) = backStack.add(CoverPickerKey(sessionId))

    fun openImports(replaceCurrent: Boolean = false) {
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
    val navigator = remember(backStack) { AppNavigator(backStack) { addTorrent = AddTorrentRequest(it) } }
    val listDetail = rememberListDetailSceneStrategy<NavKey>()
    val playerState by container.player.state.collectAsStateWithLifecycle()
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
    val showMiniPlayer = nowPlaying != null && playerState.hasBook &&
        (top == LibraryKey || top is BookKey || top == ImportsKey || top == SettingsKey)
    // Screens add the navigation bar inset themselves; the docked bar sits right on top of it.
    val overlay: Dp = if (showMiniPlayer) MiniPlayerHeight else 0.dp

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalBottomOverlayPadding provides overlay) {
            NavDisplay(
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
                    entry<BookKey>(metadata = ListDetailSceneStrategy.detailPane()) { key ->
                        BookDetailScreen(key.bookId, navigator)
                    }
                    entry<PlayerKey> { PlayerScreen(navigator) }
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
        AnimatedVisibility(
            visible = showMiniPlayer,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            nowPlaying?.let { book ->
                MiniPlayer(
                    book = book,
                    state = playerState,
                    onOpen = navigator::openPlayer,
                    onTogglePlay = container.player::togglePlayPause,
                    onSkipBack = container.player::skipBack,
                )
            }
        }
    }
}
