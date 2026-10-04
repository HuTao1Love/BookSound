package com.zyagodin.booksound.ui.library

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.library.ProgressFilter
import com.zyagodin.booksound.core.library.SortField
import com.zyagodin.booksound.data.library.LibraryItem
import com.zyagodin.booksound.data.library.ScanResult
import com.zyagodin.booksound.importer.ImportJob
import com.zyagodin.booksound.importer.ImportSelection
import com.zyagodin.booksound.importer.ImportService
import com.zyagodin.booksound.torrent.TorrentPhase
import com.zyagodin.booksound.ui.AppNavigator
import com.zyagodin.booksound.ui.LocalBottomOverlayPadding
import com.zyagodin.booksound.ui.components.AppBottomSheet
import com.zyagodin.booksound.ui.components.BookCover
import com.zyagodin.booksound.ui.components.BookProgressBar
import com.zyagodin.booksound.ui.components.GradientCircleButton
import com.zyagodin.booksound.ui.components.LoadingDots
import com.zyagodin.booksound.ui.components.MessageState
import com.zyagodin.booksound.ui.components.PrimaryButton
import com.zyagodin.booksound.ui.components.RemoveBookDialog
import com.zyagodin.booksound.ui.components.SheetAction
import com.zyagodin.booksound.ui.components.titleWithSeries
import com.zyagodin.booksound.ui.components.rememberWindowLayout
import com.zyagodin.booksound.ui.navigation.appViewModel
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(navigator: AppNavigator) {
    val vm = appViewModel { LibraryViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val window = rememberWindowLayout()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var showImportSheet by rememberSaveable { mutableStateOf(false) }
    var actionsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var removeFor by rememberSaveable { mutableStateOf<String?>(null) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun ensureNotificationPermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) navigator.openImportEditor(vm.startImport(ImportSelection.Documents(uris)))
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) navigator.openImportEditor(vm.startImport(ImportSelection.Folder(uri)))
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
        floatingActionButton = {
            if (state.totalCount > 0) {
                GradientCircleButton(
                    onClick = { showImportSheet = true },
                    size = 56.dp,
                    glow = true,
                    modifier = Modifier.padding(bottom = bottomOverlay).navigationBarsPadding(),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.action_import), modifier = Modifier.size(28.dp))
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.scanning,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val gridState = rememberLazyGridState()
            LazyVerticalGrid(
                // Series cards, two side by side on wide windows.
                columns = GridCells.Adaptive(520.dp),
                state = gridState,
                contentPadding = PaddingValues(
                    start = Spacing.lg, end = Spacing.lg,
                    bottom = Spacing.xxxl + 56.dp + bottomOverlay + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                    LibraryHeader(
                        state = state,
                        onSearch = vm::setQuery,
                        onFilter = vm::setFilter,
                        onSort = vm::setSort,
                        onSettings = navigator::openSettings,
                        onDownloads = { navigator.openImports() },
                    )
                }
                if (state.activeImports.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "imports") {
                        ImportBanner(state.activeImports, onClick = { navigator.openImports() })
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
                    else -> items(state.groups, key = { "series:" + (it.series ?: "") }) { section ->
                        SeriesSectionView(
                            section = section,
                            coverSize = if (window.isCompact) 128.dp else 152.dp,
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

    if (showImportSheet) {
        ImportSourceSheet(
            onDismiss = { showImportSheet = false },
            onPickFiles = {
                showImportSheet = false
                ensureNotificationPermission()
                pickFiles.launch(arrayOf("audio/*", "video/mp4", "application/octet-stream", "image/*"))
            },
            onPickFolder = {
                showImportSheet = false
                ensureNotificationPermission()
                pickFolder.launch(null)
            },
            onAddTorrent = {
                showImportSheet = false
                ensureNotificationPermission()
                navigator.addTorrent()
            },
        )
    }

    val actionItem = actionsFor?.let { id -> state.items.firstOrNull { it.id == id } }
    if (actionItem != null) {
        BookActionsSheet(
            item = actionItem,
            onDismiss = { actionsFor = null },
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
            onConfirm = { deleteFile ->
                removeFor = null
                vm.remove(removeItem, deleteFile)
            },
            onDismiss = { removeFor = null },
        )
    }
}

@Composable
private fun LibraryHeader(
    state: LibraryUiState,
    onSearch: (String) -> Unit,
    onFilter: (ProgressFilter) -> Unit,
    onSort: (SortField) -> Unit,
    onSettings: () -> Unit,
    onDownloads: () -> Unit,
) {
    val focus = LocalFocusManager.current
    // Search hides behind a button in the top bar; it stays open while it holds text.
    var searching by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf(state.query) }
    val searchShown = searching || text.isNotEmpty()
    val focusRequester = remember { FocusRequester() }
    fun closeSearch() {
        text = ""
        onSearch("")
        searching = false
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
                IconButton(onClick = { if (searchShown) closeSearch() else searching = true }) {
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
                    text = it
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
            FilterRow(state, onFilter, onSort)
            Spacer(Modifier.height(Spacing.sm))
        }
    }
}

/**
 * Opens the downloads. The badge counts torrents in progress (the books of one torrent count once)
 * and turns to the accent colour when one waits for its details to be reviewed.
 */
@Composable
private fun DownloadsButton(state: LibraryUiState, onClick: () -> Unit) {
    val torrents = state.activeTorrents
    val downloads = torrents.distinctBy { it.record.downloadKey }.size
    val needsReview = torrents.any {
        !it.record.reviewed && (it.record.phase == TorrentPhase.DOWNLOADING || it.record.phase == TorrentPhase.DOWNLOADED)
    }
    IconButton(onClick = onClick) {
        BadgedBox(
            badge = {
                if (downloads > 0) {
                    Badge(
                        containerColor = if (needsReview) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = if (needsReview) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                    ) { Text("$downloads") }
                }
            },
        ) {
            Icon(Icons.Rounded.Download, contentDescription = stringResource(R.string.torrents_section))
        }
    }
}

@Composable
private fun FilterRow(state: LibraryUiState, onFilter: (ProgressFilter) -> Unit, onSort: (SortField) -> Unit) {
    var sortMenu by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), modifier = Modifier.weight(1f)) {
            items(ProgressFilter.entries.size) { i ->
                val filter = ProgressFilter.entries[i]
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { onFilter(filter) },
                    label = { Text(stringResource(filter.label()), style = MaterialTheme.typography.labelMedium) },
                    shape = Radii.pill,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                        selectedLabelColor = MaterialTheme.colorScheme.surface,
                    ),
                    border = null,
                )
            }
        }
        Box {
            Surface(
                onClick = { sortMenu = true },
                shape = Radii.pill,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.padding(start = Spacing.sm).heightIn(min = 40.dp),
            ) {
                Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text(stringResource(state.sort.label()), style = MaterialTheme.typography.labelMedium)
                }
            }
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

@Composable
private fun ImportBanner(jobs: List<ImportJob>, onClick: () -> Unit) {
    val job = jobs.first()
    Surface(
        onClick = onClick,
        shape = Radii.card,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
    ) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (jobs.size > 1) pluralStringResource(R.plurals.importing_books, jobs.size, jobs.size)
                        else stringResource(R.string.importing_book, job.title),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stringResource(ImportService.stageLabel(job.stage)) + (job.progress?.let { " · ${(it * 100).roundToInt()}%" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
            }
            Spacer(Modifier.height(Spacing.sm))
            BookProgressBar(job.progress ?: 0f, height = 4.dp)
        }
    }
}

@Composable
private fun ImportSourceSheet(onDismiss: () -> Unit, onPickFiles: () -> Unit, onPickFolder: () -> Unit, onAddTorrent: () -> Unit) {
    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl)) {
            Text(stringResource(R.string.import_sheet_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.import_sheet_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xl))
            ImportOption(Icons.Rounded.AudioFile, stringResource(R.string.import_option_files), stringResource(R.string.import_option_files_hint), onPickFiles)
            Spacer(Modifier.height(Spacing.md))
            ImportOption(Icons.Rounded.FolderOpen, stringResource(R.string.import_option_folder), stringResource(R.string.import_option_folder_hint), onPickFolder)
            Spacer(Modifier.height(Spacing.md))
            ImportOption(Icons.Rounded.Download, stringResource(R.string.import_option_torrent), stringResource(R.string.import_option_torrent_hint), onAddTorrent)
        }
    }
}

@Composable
private fun ImportOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, hint: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = Radii.card, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(52.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.width(Spacing.lg))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Long-press menu of a book in the library and in a series. */
@Composable
internal fun BookActionsSheet(
    item: LibraryItem,
    onDismiss: () -> Unit,
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
        SheetAction(Icons.Rounded.PlayArrow, stringResource(if (item.entry.positionMs > 0) R.string.action_resume else R.string.action_play), onPlay, enabled = !item.isMissing)
        SheetAction(Icons.Rounded.Info, stringResource(R.string.action_details), onDetails)
        SheetAction(Icons.Rounded.Edit, stringResource(R.string.action_edit_details), onEdit, enabled = !item.isMissing)
        SheetAction(
            if (item.entry.finished) Icons.Rounded.RadioButtonUnchecked else Icons.Rounded.CheckCircle,
            stringResource(if (item.entry.finished) R.string.action_mark_unfinished else R.string.action_mark_finished),
            onToggleFinished,
        )
        SheetAction(Icons.Rounded.DeleteOutline, stringResource(R.string.action_remove_from_library), onRemove, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(Spacing.lg))
    }
}
