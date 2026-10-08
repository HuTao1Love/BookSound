package com.zyagodin.booksound.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zyagodin.booksound.AppContainer
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.library.LibrarySearch
import com.zyagodin.booksound.data.library.LibraryItem
import com.zyagodin.booksound.importer.ImportSelection
import com.zyagodin.booksound.ui.AppNavigator
import com.zyagodin.booksound.ui.LocalBottomOverlayPadding
import com.zyagodin.booksound.ui.components.BookListRow
import com.zyagodin.booksound.ui.components.NameLinksText
import com.zyagodin.booksound.ui.components.BookProgressBar
import com.zyagodin.booksound.ui.components.CircleIconButton
import com.zyagodin.booksound.ui.components.CoverBackdrop
import com.zyagodin.booksound.ui.components.LoadingDots
import com.zyagodin.booksound.ui.components.MessageState
import com.zyagodin.booksound.ui.components.PrimaryButton
import com.zyagodin.booksound.ui.components.RemoveBookDialog
import com.zyagodin.booksound.ui.components.numberedTitle
import com.zyagodin.booksound.ui.navigation.appViewModel
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing
import com.zyagodin.booksound.util.formatDuration
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SeriesState {
    data object Loading : SeriesState
    /** No book in the library belongs to the series any more (removed or renamed). */
    data object Empty : SeriesState
    data class Loaded(val section: SeriesSection) : SeriesState
}

class SeriesViewModel(private val container: AppContainer, series: String) : ViewModel() {
    private val _removed = MutableSharedFlow<LibraryEvent.Removed>(extraBufferCapacity = 4)
    val removed: SharedFlow<LibraryEvent.Removed> = _removed

    /** Every book of the series, ignoring the library's search and filter, ordered by number. */
    val state: StateFlow<SeriesState> = container.library.library
        .map { items ->
            val byId = items.associateBy { it.id }
            val group = LibrarySearch.seriesGroup(items.map { it.entry }, series)
            if (group == null) SeriesState.Empty
            else SeriesState.Loaded(SeriesSection(group.series, group.entries.mapNotNull { byId[it.book.id.value] }, group))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SeriesState.Loading)

    fun play(item: LibraryItem) = container.player.play(item.id)

    fun setFinished(item: LibraryItem, finished: Boolean) = viewModelScope.launch {
        container.library.setFinished(item.id, finished)
    }

    fun remove(item: LibraryItem, deleteFile: Boolean) = viewModelScope.launch {
        if (container.player.state.value.bookId == item.id) container.player.stop()
        val ok = container.library.remove(item.id, deleteFile)
        _removed.emit(LibraryEvent.Removed(item.metadata.title, deleteFile && ok, deleteFile && !ok))
    }

    fun startEdit(item: LibraryItem): String {
        val session = container.importSessions.create(ImportSelection.ExistingBook(item.id))
        session.analysisJob = container.appScope.launch { container.importAnalyzer.analyze(session) }
        return session.id
    }
}

/** The book to continue the series with: the one listened to last, else the first unfinished one. */
private fun upNext(items: List<LibraryItem>): LibraryItem? {
    val available = items.filter { !it.isMissing && !it.entry.finished }
    return available.filter { it.entry.positionMs > 0 }.maxByOrNull { it.entry.lastPlayedAt ?: 0L } ?: available.firstOrNull()
}

/** All books of one series, opened from its card in the library. */
@Composable
fun SeriesScreen(series: String, navigator: AppNavigator) {
    val vm = appViewModel(key = "series-$series") { SeriesViewModel(it, series) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var actionsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var removeFor by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(vm) {
        vm.removed.collect { snackbar.showSnackbar(it.message(context)) }
    }

    val items = (state as? SeriesState.Loaded)?.section?.items.orEmpty()
    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            SeriesState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingDots() }
            SeriesState.Empty -> Column(Modifier.fillMaxSize()) {
                SeriesTopBar(navigator::back)
                MessageState(
                    icon = Icons.AutoMirrored.Rounded.LibraryBooks,
                    title = stringResource(R.string.series_empty_title),
                    message = stringResource(R.string.series_empty_message),
                )
            }
            is SeriesState.Loaded -> {
                val section = s.section
                val bottom = LocalBottomOverlayPadding.current + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                CoverBackdrop(section.items.firstOrNull()?.coverPath, section.series.orEmpty(), Modifier.fillMaxWidth().height(420.dp))
                LazyColumn(
                    contentPadding = PaddingValues(bottom = bottom + Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "top") { SeriesTopBar(navigator::back) }
                    item(key = "header") {
                        SeriesHeader(
                            section = section,
                            upNext = upNext(section.items),
                            onPlay = { item ->
                                vm.play(item)
                                navigator.openPlayer()
                            },
                            onSearch = navigator::searchLibrary,
                            modifier = Modifier.padding(horizontal = Spacing.lg).widthIn(max = 720.dp),
                        )
                    }
                    items(section.items, key = { it.id }) { item ->
                        BookListRow(
                            item = item,
                            onClick = { navigator.openBook(item.id) },
                            onLongClick = { actionsFor = item.id },
                            onPlay = {
                                vm.play(item)
                                navigator.openPlayer()
                            },
                            modifier = Modifier.animateItem().padding(horizontal = Spacing.lg).widthIn(max = 720.dp).fillMaxWidth(),
                            inSeries = true,
                        )
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomOverlayPadding.current).navigationBarsPadding())
    }

    val actionItem = actionsFor?.let { id -> items.firstOrNull { it.id == id } }
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
                navigator.openImportEditor(vm.startEdit(actionItem))
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
    val removeItem = removeFor?.let { id -> items.firstOrNull { it.id == id } }
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
private fun SeriesTopBar(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), onBack, containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
    }
}

/** Emblem, name, authors, totals and overall progress, with a button that continues the series. */
@Composable
private fun SeriesHeader(
    section: SeriesSection,
    upNext: LibraryItem?,
    onPlay: (LibraryItem) -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val group = section.group
    Column(modifier.fillMaxWidth().padding(bottom = Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
        StackedCovers(section.items.take(3), size = 120.dp)
        Spacer(Modifier.height(Spacing.xl))
        Text(section.series.orEmpty(), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.xs))
        // The authors search the library, e.g. for their books outside this series.
        val authors = group.authors.take(2).joinToString(", ")
        NameLinksText(
            listOfNotNull(
                authors.takeIf { it.isNotEmpty() },
                pluralStringResource(R.plurals.book_count, section.items.size, section.items.size),
                formatDuration(context, group.totalDurationMs),
            ).joinToString(" · "),
            onSearch,
            names = authors,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            linkColor = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (group.finishedCount > 0) {
            Spacer(Modifier.height(Spacing.md))
            Surface(shape = Radii.pill, color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    stringResource(R.string.series_finished_of, group.finishedCount, section.items.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
        if (group.progress > 0f) {
            Spacer(Modifier.height(Spacing.lg))
            BookProgressBar(group.progress, Modifier.widthIn(max = 420.dp).fillMaxWidth(), height = 4.dp)
        }
        if (upNext != null) {
            Spacer(Modifier.height(Spacing.xl))
            PrimaryButton(
                stringResource(if (upNext.entry.positionMs > 0) R.string.action_resume else R.string.action_play),
                onClick = { onPlay(upNext) },
                icon = Icons.Rounded.PlayArrow,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.series_up_next, numberedTitle(upNext.metadata.title, upNext.metadata.seriesIndex).text),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
