package com.zyagodin.booksound.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zyagodin.booksound.AppContainer
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.data.library.BookDetails
import com.zyagodin.booksound.importer.ImportSelection
import com.zyagodin.booksound.playback.PlayerUiState
import com.zyagodin.booksound.ui.AppNavigator
import com.zyagodin.booksound.ui.LocalBottomOverlayPadding
import com.zyagodin.booksound.ui.components.BookCover
import com.zyagodin.booksound.ui.components.BookProgressBar
import com.zyagodin.booksound.ui.components.CircleIconButton
import com.zyagodin.booksound.ui.components.CoverBackdrop
import com.zyagodin.booksound.ui.components.LoadingDots
import com.zyagodin.booksound.ui.components.MessageState
import com.zyagodin.booksound.ui.components.PrimaryButton
import com.zyagodin.booksound.ui.components.QuietButton
import com.zyagodin.booksound.ui.components.RemoveBookDialog
import com.zyagodin.booksound.ui.components.SectionHeader
import com.zyagodin.booksound.ui.components.Tag
import com.zyagodin.booksound.ui.components.seriesLabel
import com.zyagodin.booksound.ui.navigation.appViewModel
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing
import com.zyagodin.booksound.util.formatClock
import com.zyagodin.booksound.util.formatDuration
import com.zyagodin.booksound.util.formatSize
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface DetailState {
    data object Loading : DetailState
    data object Gone : DetailState
    data class Loaded(val details: BookDetails) : DetailState
}

class BookDetailViewModel(private val container: AppContainer, private val bookId: String) : ViewModel() {
    val state: StateFlow<DetailState> = container.library.observeDetails(bookId)
        .map { d -> if (d == null || d.item.entry.book.sync.deleted) DetailState.Gone else DetailState.Loaded(d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState.Loading)

    val player: StateFlow<PlayerUiState> = container.player.state

    fun play() = container.player.play(bookId)
    fun pause() = container.player.pause()
    fun playChapter(chapter: Chapter) = container.player.play(bookId, chapter.startMs)
    fun setFinished(finished: Boolean) = viewModelScope.launch { container.library.setFinished(bookId, finished) }
    fun rescan() = viewModelScope.launch { container.scanner.scan() }

    fun remove(deleteFile: Boolean) = viewModelScope.launch {
        if (container.player.state.value.bookId == bookId) container.player.stop()
        container.library.remove(bookId, deleteFile)
    }

    fun startEdit(): String {
        val session = container.importSessions.create(ImportSelection.ExistingBook(bookId))
        session.analysisJob = container.appScope.launch { container.importAnalyzer.analyze(session) }
        return session.id
    }
}

@Composable
fun DetailPlaceholder() {
    MessageState(
        icon = Icons.Rounded.Headphones,
        title = stringResource(R.string.detail_placeholder_title),
        message = stringResource(R.string.detail_placeholder_message),
        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        iconBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
}

@Composable
fun BookDetailScreen(bookId: String, navigator: AppNavigator) {
    val vm = appViewModel(key = "detail-$bookId") { BookDetailViewModel(it, bookId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val playerState = vm.player.collectAsStateWithLifecycle()
    val live = remember(bookId, playerState) { LivePlayback(bookId, playerState) }
    var showRemove by rememberSaveable { mutableStateOf(false) }

    when (val s = state) {
        DetailState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingDots() }
        DetailState.Gone -> {
            LaunchedEffect(Unit) { navigator.back() }
            DetailPlaceholder()
        }
        is DetailState.Loaded -> {
            val details = s.details
            val finished = details.item.entry.finished
            val actions = remember(vm, navigator, finished) {
                DetailActions(
                    onBack = navigator::back,
                    onPlay = {
                        vm.play()
                        navigator.openPlayer()
                    },
                    onPause = vm::pause,
                    onChapter = { chapter ->
                        vm.playChapter(chapter)
                        navigator.openPlayer()
                    },
                    onEdit = { navigator.openImportEditor(vm.startEdit()) },
                    onToggleFinished = { vm.setFinished(!finished) },
                    onRemove = { showRemove = true },
                    onRescan = vm::rescan,
                )
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                CoverBackdrop(
                    details.item.coverPath, details.item.metadata.title,
                    Modifier.fillMaxWidth().height(if (maxWidth >= 680.dp) maxHeight else 620.dp),
                )
                if (maxWidth >= 680.dp) {
                    TwoColumnDetail(details, live, actions)
                } else {
                    SingleColumnDetail(details, live, actions)
                }
            }
            if (showRemove) {
                RemoveBookDialog(
                    title = details.item.metadata.title,
                    filePath = details.relativePath,
                    onConfirm = { deleteFile ->
                        showRemove = false
                        vm.remove(deleteFile)
                    },
                    onDismiss = { showRemove = false },
                )
            }
        }
    }
}

/**
 * The player as this screen sees it. The position changes four times a second while playing, so it
 * is read only where it is shown; the rest follows derived values that change far less often.
 */
@Stable
private class LivePlayback(private val bookId: String, private val state: State<PlayerUiState>) {
    val isCurrent by derivedStateOf { state.value.bookId == bookId }
    val playWhenReady by derivedStateOf { isCurrent && state.value.playWhenReady }
    val isPlaying by derivedStateOf { isCurrent && state.value.isPlaying }

    /** The listening position: live while this book is in the player, else the saved one. */
    fun position(details: BookDetails): Long = state.value.takeIf { it.bookId == bookId }?.positionMs ?: details.item.entry.positionMs
}

/** Index of the chapter at the listening position (-1 before the book was started); changes only between chapters. */
@Composable
private fun rememberCurrentChapter(details: BookDetails, live: LivePlayback): Int {
    val index by remember(details, live) {
        derivedStateOf {
            val position = live.position(details)
            details.chapters.indexOfLast { it.startMs <= position }.takeIf { position > 0 || live.isCurrent } ?: -1
        }
    }
    return index
}

private class DetailActions(
    val onBack: () -> Unit,
    val onPlay: () -> Unit,
    val onPause: () -> Unit,
    val onChapter: (Chapter) -> Unit,
    val onEdit: () -> Unit,
    val onToggleFinished: () -> Unit,
    val onRemove: () -> Unit,
    val onRescan: () -> Unit,
)

@Composable
private fun SingleColumnDetail(details: BookDetails, live: LivePlayback, actions: DetailActions) {
    val currentChapter = rememberCurrentChapter(details, live)
    val bottom = LocalBottomOverlayPadding.current + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    LazyColumn(
        contentPadding = PaddingValues(bottom = bottom + Spacing.xl),
        modifier = Modifier.fillMaxSize(),
    ) {
        item { DetailTopBar(details, actions) }
        item {
            Column(Modifier.padding(horizontal = Spacing.screen), horizontalAlignment = Alignment.CenterHorizontally) {
                BookCover(
                    details.item.coverPath, details.item.metadata.title, details.item.metadata.author,
                    Modifier.widthIn(max = 260.dp).fillMaxWidth(0.68f),
                    shape = Radii.coverLarge,
                    elevation = 14.dp,
                )
                Spacer(Modifier.height(Spacing.xl))
                TitleBlock(details, centered = true)
                Spacer(Modifier.height(Spacing.xl))
                PlayBlock(details, live, actions)
                Spacer(Modifier.height(Spacing.lg))
                StatsRow(details)
            }
        }
        infoItems(details, actions)
        chapterItems(details, currentChapter, live, actions)
    }
}

@Composable
private fun TwoColumnDetail(details: BookDetails, live: LivePlayback, actions: DetailActions) {
    val currentChapter = rememberCurrentChapter(details, live)
    val bottom = LocalBottomOverlayPadding.current + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(Modifier.fillMaxSize()) {
        DetailTopBar(details, actions)
        Row(Modifier.fillMaxSize().padding(horizontal = Spacing.xl), horizontalArrangement = Arrangement.spacedBy(Spacing.xxl)) {
            Column(Modifier.weight(0.42f).padding(bottom = bottom), horizontalAlignment = Alignment.CenterHorizontally) {
                BookCover(
                    details.item.coverPath, details.item.metadata.title, details.item.metadata.author,
                    Modifier.widthIn(max = 340.dp).fillMaxWidth(),
                    shape = Radii.coverLarge,
                    elevation = 16.dp,
                )
                Spacer(Modifier.height(Spacing.xl))
                PlayBlock(details, live, actions)
                Spacer(Modifier.height(Spacing.lg))
                StatsRow(details)
            }
            LazyColumn(Modifier.weight(0.58f), contentPadding = PaddingValues(bottom = bottom + Spacing.xl)) {
                item { TitleBlock(details, centered = false) }
                infoItems(details, actions, horizontalPadding = 0.dp)
                chapterItems(details, currentChapter, live, actions, horizontalPadding = 0.dp)
            }
        }
    }
}

@Composable
private fun DetailTopBar(details: BookDetails, actions: DetailActions) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), actions.onBack, containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        Spacer(Modifier.weight(1f))
        Box {
            CircleIconButton(Icons.Rounded.MoreVert, stringResource(R.string.action_more), { menu = true }, containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.large) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_edit_details)) },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    enabled = !details.item.isMissing,
                    onClick = { menu = false; actions.onEdit() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(if (details.item.entry.finished) R.string.action_mark_unfinished else R.string.action_mark_finished)) },
                    leadingIcon = { Icon(if (details.item.entry.finished) Icons.Rounded.RadioButtonUnchecked else Icons.Rounded.CheckCircle, null) },
                    onClick = { menu = false; actions.onToggleFinished() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_remove_from_library), color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; actions.onRemove() },
                )
            }
        }
    }
}

@Composable
private fun TitleBlock(details: BookDetails, centered: Boolean) {
    val meta = details.item.metadata
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        seriesLabel(meta.series, meta.seriesIndex)?.let {
            Tag(it)
            Spacer(Modifier.height(Spacing.sm))
        }
        Text(meta.title, style = MaterialTheme.typography.headlineMedium, textAlign = align)
        meta.author?.let {
            Spacer(Modifier.height(Spacing.xs))
            Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, textAlign = align)
        }
        meta.narrator?.let {
            Spacer(Modifier.height(2.dp))
            Text(stringResource(R.string.narrated_by, it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align)
        }
    }
}

@Composable
private fun PlayBlock(details: BookDetails, live: LivePlayback, actions: DetailActions) {
    val context = LocalContext.current
    val entry = details.item.entry
    val position = live.position(details)
    val duration = entry.book.durationMs
    val playingNow = live.playWhenReady
    val label = when {
        playingNow -> stringResource(R.string.action_pause)
        entry.finished -> stringResource(R.string.action_listen_again)
        position > 0 -> stringResource(R.string.action_resume_with_left, formatDuration(context, duration - position))
        else -> stringResource(R.string.action_play)
    }
    Column(Modifier.fillMaxWidth().widthIn(max = 480.dp)) {
        PrimaryButton(
            text = label,
            onClick = if (playingNow) actions.onPause else actions.onPlay,
            icon = when {
                playingNow -> Icons.Rounded.Pause
                entry.finished -> Icons.Rounded.Replay
                else -> Icons.Rounded.PlayArrow
            },
            enabled = !details.item.isMissing,
            modifier = Modifier.fillMaxWidth(),
        )
        if (position > 0 && !entry.finished && duration > 0) {
            Spacer(Modifier.height(Spacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookProgressBar(position.toFloat() / duration, Modifier.weight(1f))
                Spacer(Modifier.width(Spacing.md))
                Text("${(position * 100 / duration)}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun StatsRow(details: BookDetails) {
    val context = LocalContext.current
    Surface(shape = Radii.card, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), modifier = Modifier.fillMaxWidth().widthIn(max = 480.dp)) {
        Row(Modifier.padding(vertical = Spacing.md), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat(stringResource(R.string.stat_length), formatDuration(context, details.item.entry.book.durationMs))
            Stat(stringResource(R.string.stat_chapters), details.chapters.size.toString())
            Stat(stringResource(R.string.stat_size), formatSize(context, details.fileSize))
            details.item.metadata.year?.let { Stat(stringResource(R.string.stat_year), it) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun LazyListScope.infoItems(details: BookDetails, actions: DetailActions, horizontalPadding: androidx.compose.ui.unit.Dp = Spacing.screen) {
    if (details.item.isMissing) {
        item {
            Surface(
                shape = Radii.card,
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding).padding(top = Spacing.xl),
            ) {
                Column(Modifier.padding(Spacing.lg)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(Spacing.sm))
                        Text(stringResource(R.string.file_missing_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    Spacer(Modifier.height(Spacing.xs))
                    Text(stringResource(R.string.file_missing_message, details.relativePath), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                    Row {
                        QuietButton(stringResource(R.string.action_rescan), actions.onRescan, icon = Icons.Rounded.Refresh, color = MaterialTheme.colorScheme.onErrorContainer)
                        QuietButton(stringResource(R.string.action_remove), actions.onRemove, icon = Icons.Rounded.DeleteOutline, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
    details.item.metadata.description?.let { description ->
        item {
            var expanded by rememberSaveable { mutableStateOf(false) }
            Column(Modifier.padding(horizontal = horizontalPadding).padding(top = Spacing.xl)) {
                SectionHeader(stringResource(R.string.section_about))
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 5,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.animateContentSize().clickable { expanded = !expanded },
                )
                if (!expanded && description.length > 280) {
                    Text(
                        stringResource(R.string.action_more),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = Spacing.xs).clickable { expanded = true },
                    )
                }
            }
        }
    }
    item {
        Column(Modifier.padding(horizontal = horizontalPadding).padding(top = Spacing.lg)) {
            SectionHeader(stringResource(R.string.section_file))
            Text(details.relativePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun LazyListScope.chapterItems(
    details: BookDetails,
    currentIndex: Int,
    live: LivePlayback,
    actions: DetailActions,
    horizontalPadding: androidx.compose.ui.unit.Dp = Spacing.screen,
) {
    if (details.chapters.isEmpty()) return
    item {
        SectionHeader(
            stringResource(R.string.section_chapters_count, details.chapters.size),
            Modifier.padding(horizontal = horizontalPadding).padding(top = Spacing.xl),
        )
    }
    itemsIndexed(details.chapters, key = { _, c -> "ch-${c.index}" }) { i, chapter ->
        ChapterRow(
            chapter = chapter,
            number = i + 1,
            isCurrent = i == currentIndex,
            isPlaying = i == currentIndex && live.isPlaying,
            onClick = { actions.onChapter(chapter) },
            enabled = !details.item.isMissing,
            modifier = Modifier.padding(horizontal = horizontalPadding - Spacing.sm),
        )
    }
}

@Composable
fun ChapterRow(
    chapter: Chapter,
    number: Int,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    progress: Float? = null,
) {
    val color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else androidx.compose.ui.graphics.Color.Transparent,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(36.dp)) {
                    if (isPlaying) {
                        Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                    } else {
                        Text(number.toString(), style = MaterialTheme.typography.labelMedium, color = if (isCurrent) color else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    chapter.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    color = color,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Spacing.md))
                Text(formatClock(chapter.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (progress != null && isCurrent) {
                Spacer(Modifier.height(Spacing.sm))
                BookProgressBar(progress, Modifier.padding(start = 36.dp), height = 3.dp)
            }
        }
    }
}
