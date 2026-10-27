package com.zyagodin.booksound.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.library.ProgressFilter
import com.zyagodin.booksound.core.library.SortField
import com.zyagodin.booksound.data.library.BookLocation
import com.zyagodin.booksound.data.library.LibraryItem
import com.zyagodin.booksound.data.library.LocationFilter
import com.zyagodin.booksound.data.library.ScanResult
import com.zyagodin.booksound.importer.ImportSelection
import com.zyagodin.booksound.server.ServerInfo
import com.zyagodin.booksound.server.ServerTransfer
import com.zyagodin.booksound.torrent.TorrentPhase
import com.zyagodin.booksound.ui.AppNavigator
import com.zyagodin.booksound.ui.LocalBottomOverlayPadding
import com.zyagodin.booksound.ui.components.AppBottomSheet
import com.zyagodin.booksound.ui.components.BookCover
import com.zyagodin.booksound.ui.components.LoadingDots
import com.zyagodin.booksound.ui.components.MessageState
import com.zyagodin.booksound.ui.components.PrimaryButton
import com.zyagodin.booksound.ui.components.DeleteFromServerDialog
import com.zyagodin.booksound.ui.components.RemoveBookDialog
import com.zyagodin.booksound.ui.components.SheetAction
import com.zyagodin.booksound.ui.components.titleWithSeries
import com.zyagodin.booksound.ui.importer.ImportSourcePicker
import com.zyagodin.booksound.ui.components.rememberWindowLayout
import com.zyagodin.booksound.ui.navigation.appViewModel
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing
import com.zyagodin.booksound.util.formatSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(navigator: AppNavigator) {
    val vm = appViewModel { LibraryViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val lowStorage by vm.lowStorage.collectAsStateWithLifecycle()
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    val canDeleteOnServer by vm.canDeleteOnServer.collectAsStateWithLifecycle()
    val serverInfo by vm.serverInfo.collectAsStateWithLifecycle()
    val window = rememberWindowLayout()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showImportSheet by rememberSaveable { mutableStateOf(false) }
    var actionsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var removeFor by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteOnServerFor by rememberSaveable { mutableStateOf<String?>(null) }
    // Search hides behind a button in the top bar; it stays open while it holds text.
    var searching by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf(state.query) }
    val gridState = rememberLazyGridState()

    // A name tapped on another screen (an author on a book's page): show the books it finds,
    // whatever the filters.
    LaunchedEffect(navigator.pendingSearch) {
        val text = navigator.pendingSearch ?: return@LaunchedEffect
        searchText = text
        searching = true
        vm.setQuery(text)
        if (state.filter != ProgressFilter.ALL) vm.setFilter(ProgressFilter.ALL)
        if (state.location != LocationFilter.ALL) vm.setLocation(LocationFilter.ALL)
        gridState.scrollToItem(0)
        navigator.pendingSearch = null
    }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            val message = when (event) {
                is LibraryEvent.ScanFinished -> when (val r = event.result) {
                    is ScanResult.Done -> if (r.added + r.relinked + r.missing == 0) null
                    else context.getString(R.string.scan_result, r.added, r.missing)
                    ScanResult.PermissionLost, ScanResult.NoLibraryFolder -> context.getString(R.string.scan_permission_lost)
                }
                is LibraryEvent.Removed -> event.message(context)
            }
            if (message != null) snackbar.showSnackbar(message)
        }
    }

    val bottomOverlay = LocalBottomOverlayPadding.current
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = bottomOverlay)) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.scanning,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyVerticalGrid(
                // Series cards, two side by side on wide windows.
                columns = GridCells.Adaptive(520.dp),
                state = gridState,
                contentPadding = PaddingValues(
                    start = Spacing.lg, end = Spacing.lg,
                    bottom = Spacing.xxxl + bottomOverlay + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                    LibraryHeader(
                        state = state,
                        searching = searching,
                        onSearchingChange = { searching = it },
                        text = searchText,
                        onTextChange = { searchText = it },
                        onSearch = vm::setQuery,
                        onFilter = vm::setFilter,
                        onLocation = vm::setLocation,
                        onSort = vm::setSort,
                        onSettings = navigator::openSettings,
                        onDownloads = { navigator.openImports() },
                    )
                }
                lowStorage?.let { free ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "low-storage") { LowStorageNotice(free) }
                }
                serverInfo?.takeIf { it.incompatible }?.let { info ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "server-api") {
                        ServerApiNotice(info, onUpdateApp = vm::checkUpdates)
                    }
                }
                when {
                    state.loading -> item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                        Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) { LoadingDots() }
                    }
                    state.totalCount == 0 -> item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        MessageState(
                            icon = Icons.AutoMirrored.Rounded.LibraryBooks,
                            title = stringResource(R.string.library_empty_title),
                            message = stringResource(R.string.library_empty_message),
                            modifier = Modifier.heightIn(min = 420.dp),
                            action = {
                                PrimaryButton(stringResource(R.string.action_import_audiobook), onClick = { showImportSheet = true }, icon = Icons.Rounded.Add)
                            },
                        )
                    }
                    state.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "noresults") {
                        MessageState(
                            icon = Icons.Rounded.SearchOff,
                            title = stringResource(R.string.library_no_results_title),
                            message = stringResource(R.string.library_no_results_message),
                            modifier = Modifier.heightIn(min = 320.dp),
                        )
                    }
                    else -> items(state.groups, key = { it.key }) { section ->
                        SeriesSectionView(
                            section = section,
                            coverSize = if (window.isCompact) 128.dp else 152.dp,
                            transfers = transfers,
                            onOpen = { navigator.openBook(it.id) },
                            onLongClick = { actionsFor = it.id },
                            onOpenSeries = navigator::openSeries,
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    // Keeps the status bar readable while content scrolls underneath it.
    val bg = MaterialTheme.colorScheme.background
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .background(Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0.85f)))),
    )

    ImportSourcePicker(navigator, visible = showImportSheet, onDismiss = { showImportSheet = false })

    val actionItem = actionsFor?.let { id -> state.items.firstOrNull { it.id == id } }
    if (actionItem != null) {
        BookActionsSheet(
            item = actionItem,
            transfer = transfers[actionItem.id],
            onDismiss = { actionsFor = null },
            onDownload = {
                actionsFor = null
                vm.download(actionItem)
            },
            onUpload = {
                actionsFor = null
                vm.upload(actionItem)
            },
            onCancelTransfer = {
                actionsFor = null
                vm.cancelTransfer(it)
            },
            onDeleteFromServer = if (canDeleteOnServer) {
                {
                    actionsFor = null
                    deleteOnServerFor = actionItem.id
                }
            } else null,
            onPlay = {
                actionsFor = null
                vm.play(actionItem)
                navigator.openPlayer()
            },
            onDetails = {
                actionsFor = null
                navigator.openBook(actionItem.id)
            },
            onEdit = {
                actionsFor = null
                navigator.openImportEditor(vm.startImport(ImportSelection.ExistingBook(actionItem.id)))
            },
            onToggleFinished = {
                actionsFor = null
                vm.setFinished(actionItem, !actionItem.entry.finished)
            },
            onRemove = {
                actionsFor = null
                removeFor = actionItem.id
            },
        )
    }
    val removeItem = removeFor?.let { id -> state.items.firstOrNull { it.id == id } }
    if (removeItem != null) {
        RemoveBookDialog(
            title = removeItem.metadata.title,
            filePath = null,
            onConfirm = { deleteFile, deleteFromServer ->
                removeFor = null
                vm.remove(removeItem, deleteFile, deleteFromServer)
            },
            onDismiss = { removeFor = null },
            onServer = removeItem.location == BookLocation.SYNCED,
            canDeleteOnServer = canDeleteOnServer,
        )
    }
    val serverItem = deleteOnServerFor?.let { id -> state.items.firstOrNull { it.id == id } }
    if (serverItem != null) {
        DeleteFromServerDialog(
            title = serverItem.metadata.title,
            onConfirm = {
                deleteOnServerFor = null
                vm.deleteFromServer(serverItem)
            },
            onDismiss = { deleteOnServerFor = null },
        )
    }
}

@Composable
private fun LibraryHeader(
    state: LibraryUiState,
    searching: Boolean,
    onSearchingChange: (Boolean) -> Unit,
    text: String,
    onTextChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onFilter: (ProgressFilter) -> Unit,
    onLocation: (LocationFilter) -> Unit,
    onSort: (SortField) -> Unit,
    onSettings: () -> Unit,
    onDownloads: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val searchShown = searching || text.isNotEmpty()
    val focusRequester = remember { FocusRequester() }
    fun closeSearch() {
        onTextChange("")
        onSearch("")
        onSearchingChange(false)
        focus.clearFocus()
    }
    Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = Spacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = Spacing.xs)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.library_title), style = MaterialTheme.typography.headlineLarge)
                if (state.totalCount > 0) {
                    Text(
                        pluralStringResource(R.plurals.book_count, state.totalCount, state.totalCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.totalCount > 0) {
                IconButton(onClick = { if (searchShown) closeSearch() else onSearchingChange(true) }) {
                    Icon(
                        if (searchShown) Icons.Rounded.SearchOff else Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.library_search_hint),
                    )
                }
            }
            DownloadsButton(state, onDownloads)
            IconButton(onClick = onSettings) {
                Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.settings_title))
            }
        }
        if (state.totalCount > 0 && searchShown) {
            Spacer(Modifier.height(Spacing.lg))
            LaunchedEffect(Unit) { if (text.isEmpty()) runCatching { focusRequester.requestFocus() } }
            TextField(
                value = text,
                onValueChange = {
                    onTextChange(it)
                    onSearch(it)
                },
                placeholder = { Text(stringResource(R.string.library_search_hint), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (text.isNotEmpty()) {
                        IconButton(onClick = ::closeSearch) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear)) }
                    }
                },
                singleLine = true,
                shape = Radii.pill,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
        if (state.totalCount > 0) {
            Spacer(Modifier.height(Spacing.md))
            FilterRow(state, onFilter, onLocation, onSort)
            Spacer(Modifier.height(Spacing.sm))
        }
    }
}

/**
 * Opens the downloads. Like an app icon's notification count, the badge counts what is in progress:
 * torrents (the books of one torrent count once), imports, and books moving to or from the server or to
 * the watch. It turns to the accent colour when a
 * torrent waits for its details to be reviewed.
 */
@Composable
private fun DownloadsButton(state: LibraryUiState, onClick: () -> Unit) {
    val torrents = state.activeTorrents
    val downloads = torrents.distinctBy { it.record.downloadKey }.size + state.activeImports.size + state.activeTransfers
    val needsReview = torrents.any {
        !it.record.reviewed && (it.record.phase == TorrentPhase.DOWNLOADING || it.record.phase == TorrentPhase.DOWNLOADED)
    }
    IconButton(onClick = onClick) {
        // Not BadgedBox: it reads the badge's baseline, which crashes the lookahead pass
        // that follows a rotation.
        Box {
            Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.torrents_section))
            if (downloads > 0) {
                Badge(
                    containerColor = if (needsReview) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (needsReview) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp),
                ) { Text("$downloads") }
            }
        }
    }
}

@Composable
private fun FilterRow(state: LibraryUiState, onFilter: (ProgressFilter) -> Unit, onLocation: (LocationFilter) -> Unit, onSort: (SortField) -> Unit) {
    var filterMenu by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            MenuPill(Icons.Rounded.FilterList, stringResource(state.filter.label()), onClick = { filterMenu = true })
            DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }, shape = MaterialTheme.shapes.large) {
                ProgressFilter.entries.forEach { filter ->
                    DropdownMenuItem(
                        text = { Text(stringResource(filter.label()), style = MaterialTheme.typography.bodyLarge) },
                        trailingIcon = {
                            if (state.filter == filter) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        onClick = {
                            filterMenu = false
                            onFilter(filter)
                        },
                    )
                }
            }
        }
        // Shown while it filters too, so it can be turned off after the server's books are gone.
        if (state.hasServerBooks || state.location != LocationFilter.ALL) {
            LocationButton(state.location, onClick = { onLocation(state.location.next()) })
        }
        Spacer(Modifier.weight(1f))
        Box {
            MenuPill(Icons.Rounded.SwapVert, stringResource(state.sort.label()), onClick = { sortMenu = true }, modifier = Modifier.padding(start = Spacing.sm))
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }, shape = MaterialTheme.shapes.large) {
                SortField.entries.forEach { field ->
                    DropdownMenuItem(
                        text = { Text(stringResource(field.label()), style = MaterialTheme.typography.bodyLarge) },
                        trailingIcon = {
                            if (state.sort == field) {
                                Icon(
                                    if (state.descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                                    contentDescription = stringResource(if (state.descending) R.string.sort_descending else R.string.sort_ascending),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        onClick = {
                            sortMenu = false
                            onSort(field)
                        },
                    )
                }
            }
        }
    }
}

/** Where the shown books are: a grey cloud for all of them, a crossed-out one for the phone's, a bright one for the server's. */
@Composable
private fun LocationButton(location: LocationFilter, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.padding(start = Spacing.xs)) {
        Icon(
            if (location == LocationFilter.PHONE) Icons.Rounded.CloudOff else Icons.Rounded.Cloud,
            contentDescription = stringResource(
                when (location) {
                    LocationFilter.ALL -> R.string.location_filter_all
                    LocationFilter.PHONE -> R.string.location_filter_phone
                    LocationFilter.SERVER -> R.string.location_filter_server
                },
            ),
            tint = if (location == LocationFilter.ALL) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun MenuPill(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = Radii.pill,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.xs))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun ProgressFilter.label(): Int = when (this) {
    ProgressFilter.ALL -> R.string.filter_all
    ProgressFilter.IN_PROGRESS -> R.string.filter_in_progress
    ProgressFilter.NOT_STARTED -> R.string.filter_not_started
    ProgressFilter.FINISHED -> R.string.filter_finished
}

private fun SortField.label(): Int = when (this) {
    SortField.RECENT -> R.string.sort_recent
    SortField.ADDED -> R.string.sort_added
    SortField.TITLE -> R.string.sort_title
    SortField.AUTHOR -> R.string.sort_author
    SortField.SERIES -> R.string.sort_series
    SortField.DURATION -> R.string.sort_duration
    SortField.PROGRESS -> R.string.sort_progress
}

/**
 * Long-press menu of a book in the library and in a series. A book only on the server can be downloaded and
 * looked at; one the server lacks can be sent there.
 */
@Composable
internal fun BookActionsSheet(
    item: LibraryItem,
    transfer: ServerTransfer?,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onUpload: () -> Unit,
    onCancelTransfer: (ServerTransfer) -> Unit,
    /** Null unless the user may delete books from the server. */
    onDeleteFromServer: (() -> Unit)?,
    onPlay: () -> Unit,
    onDetails: () -> Unit,
    onEdit: () -> Unit,
    onToggleFinished: () -> Unit,
    onRemove: () -> Unit,
) {
    AppBottomSheet(onDismiss = onDismiss) {
        Row(Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            BookCover(item.coverPath, item.metadata.title, item.metadata.author, Modifier.size(56.dp))
            Spacer(Modifier.width(Spacing.lg))
            Column {
                Text(titleWithSeries(item.metadata.title, item.metadata.series, item.metadata.seriesIndex), style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                item.metadata.author?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (transfer != null) {
            SheetAction(
                Icons.Rounded.Close,
                stringResource(if (transfer.direction == ServerTransfer.Direction.DOWNLOAD) R.string.action_cancel_download else R.string.action_cancel_upload),
                { onCancelTransfer(transfer) },
            )
        }
        if (item.onServerOnly) {
            if (transfer == null) {
                SheetAction(Icons.Rounded.CloudDownload, stringResource(R.string.action_download_to_phone_size, formatSize(LocalContext.current, item.entry.book.file.sizeBytes)), onDownload)
            }
            SheetAction(Icons.Rounded.Info, stringResource(R.string.action_details), onDetails)
            onDeleteFromServer?.let {
                SheetAction(Icons.Rounded.DeleteOutline, stringResource(R.string.action_delete_from_server), it, color = MaterialTheme.colorScheme.error)
            }
        } else {
            SheetAction(Icons.Rounded.PlayArrow, stringResource(if (item.entry.positionMs > 0) R.string.action_resume else R.string.action_play), onPlay, enabled = !item.isMissing)
            SheetAction(Icons.Rounded.Info, stringResource(R.string.action_details), onDetails)
            if (item.location == BookLocation.PHONE_ONLY && transfer == null) {
                SheetAction(Icons.Rounded.CloudUpload, stringResource(R.string.action_upload_to_server), onUpload, enabled = !item.isMissing)
            }
            SheetAction(Icons.Rounded.Edit, stringResource(R.string.action_edit_details), onEdit, enabled = !item.isMissing)
            SheetAction(
                if (item.entry.finished) Icons.Rounded.RadioButtonUnchecked else Icons.Rounded.CheckCircle,
                stringResource(if (item.entry.finished) R.string.action_mark_unfinished else R.string.action_mark_finished),
                onToggleFinished,
            )
            SheetAction(Icons.Rounded.DeleteOutline, stringResource(R.string.action_remove_from_library), onRemove, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(Spacing.lg))
    }
}

/**
 * The server speaks another API than this app, so nothing syncs: shown above the books until the older side
 * is updated. Newer releases of the same API work together, so a plain version difference shows nothing.
 */
@Composable
private fun ServerApiNotice(info: ServerInfo, onUpdateApp: () -> Unit) {
    Surface(
        onClick = onUpdateApp,
        // The app updates from here; the server only from its web app.
        enabled = !info.serverOutdated,
        shape = Radii.card,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
    ) {
        Row(Modifier.padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (info.serverOutdated) R.string.server_api_outdated else R.string.server_api_app_outdated),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!info.serverOutdated) {
                    Text(stringResource(R.string.update_check), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Spacing.xs))
                }
            }
        }
    }
}

/** The phone is almost full: shown above the books until space is freed. */
@Composable
private fun LowStorageNotice(freeBytes: Long) {
    val context = LocalContext.current
    Surface(
        shape = Radii.card,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.sm),
    ) {
        Row(Modifier.padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Warning, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Spacing.md))
            Text(stringResource(R.string.low_storage_warning, formatSize(context, freeBytes.coerceAtLeast(0))), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
