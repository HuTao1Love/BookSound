package com.zyagodin.booksound.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.audio.VoicePreset
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.data.library.BookDetails
import com.zyagodin.booksound.playback.PlaybackProblem
import com.zyagodin.booksound.playback.PlayerUiState
import com.zyagodin.booksound.playback.SleepTimerState
import com.zyagodin.booksound.ui.AppNavigator
import com.zyagodin.booksound.ui.components.BookCover
import com.zyagodin.booksound.ui.components.CircleIconButton
import com.zyagodin.booksound.ui.components.CoverBackdrop
import com.zyagodin.booksound.ui.components.GradientCircleButton
import com.zyagodin.booksound.ui.components.LoadingDots
import com.zyagodin.booksound.ui.components.MessageState
import com.zyagodin.booksound.ui.components.PrimaryButton
import com.zyagodin.booksound.ui.components.QuietButton
import com.zyagodin.booksound.ui.components.WindowLayout
import com.zyagodin.booksound.ui.components.rememberWindowLayout
import com.zyagodin.booksound.ui.detail.ChapterRow
import com.zyagodin.booksound.ui.navigation.appViewModel
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing
import com.zyagodin.booksound.util.formatClock
import com.zyagodin.booksound.util.formatDuration
import com.zyagodin.booksound.util.formatSpeed

enum class PlayerSheet { SPEED, SLEEP, CHAPTERS, VOICE }

/** Derived, display-ready playback values. */
private class NowPlaying(val details: BookDetails, val state: PlayerUiState, previewMs: Long?, val voicePreset: VoicePreset) {
    val position: Long = previewMs ?: state.positionMs
    val duration: Long = details.item.entry.book.durationMs.takeIf { it > 0 } ?: state.durationMs
    val chapters: List<Chapter> = details.chapters
    val chapterIndex: Int = if (chapters.isEmpty()) -1 else chapters.indexOfLast { it.startMs <= position }.coerceAtLeast(0)
    val chapter: Chapter? = chapters.getOrNull(chapterIndex)
    private val useChapterScale = chapters.size > 1 && chapter != null
    val rangeStart: Long = if (useChapterScale) chapter!!.startMs else 0L
    val rangeEnd: Long = (if (useChapterScale) chapter!!.endMs else duration).coerceAtLeast(rangeStart + 1)
    val fraction: Float = ((position - rangeStart).toFloat() / (rangeEnd - rangeStart)).coerceIn(0f, 1f)
    val bookLeftMs: Long = ((duration - position) / state.speed.coerceAtLeast(0.1f)).toLong().coerceAtLeast(0)
    fun positionFor(fraction: Float): Long = rangeStart + ((rangeEnd - rangeStart) * fraction).toLong()
}

private class PlayerCallbacks(
    val onClose: () -> Unit,
    val onTogglePlay: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onPreview: (Long?) -> Unit,
    val onSkipBack: () -> Unit,
    val onSkipForward: () -> Unit,
    val onPrevChapter: () -> Unit,
    val onNextChapter: () -> Unit,
    val onSheet: (PlayerSheet) -> Unit,
    val onRetry: () -> Unit,
    val onRescan: () -> Unit,
    val onOpenBook: () -> Unit,
    val onEdit: () -> Unit,
    val onStop: () -> Unit,
)

@Composable
fun PlayerScreen(navigator: AppNavigator) {
    val vm = appViewModel { PlayerViewModel(it) }
    val player by vm.player.collectAsStateWithLifecycle()
    val book by vm.book.collectAsStateWithLifecycle()
    val sleep by vm.sleep.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val window = rememberWindowLayout()
    var sheet by rememberSaveable { mutableStateOf<PlayerSheet?>(null) }
    var previewMs by remember { mutableStateOf<Long?>(null) }

    val details = book
    if (details == null || !player.hasBook) {
        MessageState(
            icon = Icons.Rounded.Headphones,
            title = stringResource(R.string.player_nothing_title),
            message = stringResource(R.string.player_nothing_message),
            modifier = Modifier.safeDrawingPadding(),
            action = { PrimaryButton(stringResource(R.string.action_back_to_library), onClick = navigator::backToLibrary) },
        )
        return
    }

    val now = NowPlaying(details, player, previewMs, settings.voicePresetFor(details.item.id))
    val callbacks = PlayerCallbacks(
        onClose = navigator::back,
        onTogglePlay = vm::togglePlay,
        onSeek = vm::seekTo,
        onPreview = { previewMs = it },
        onSkipBack = vm::skipBack,
        onSkipForward = vm::skipForward,
        onPrevChapter = vm::previousChapter,
        onNextChapter = vm::nextChapter,
        onSheet = { sheet = it },
        onRetry = vm::retry,
        onRescan = vm::rescan,
        onOpenBook = {
            navigator.back()
            navigator.openBook(details.item.id)
        },
        onEdit = { navigator.openImportEditor(vm.startEdit(details.item.id)) },
        onStop = {
            vm.stop()
            navigator.back()
        },
    )
    val skip = settings.skipBackSeconds to settings.skipForwardSeconds

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CoverBackdrop(details.item.coverPath, details.item.metadata.title, Modifier.fillMaxSize(), intensity = 0.7f)
        val hinge = window.hingeBounds
        when {
            window.isTabletop && hinge != null -> TabletopPlayer(now, sleep, skip, callbacks, hinge.top, hinge.bottom)
            window.isWide || window.isShort -> TwoPanePlayer(now, sleep, skip, callbacks, window)
            else -> CompactPlayer(now, sleep, skip, callbacks)
        }
    }

    when (sheet) {
        PlayerSheet.SPEED -> SpeedSheet(
            speed = player.speed,
            defaultSpeed = settings.defaultSpeed,
            onSpeed = vm::setSpeed,
            onMakeDefault = vm::makeDefaultSpeed,
            onDismiss = { sheet = null },
        )
        PlayerSheet.SLEEP -> SleepSheet(
            state = sleep,
            shakeToReset = settings.shakeToReset,
            onStart = { minutes -> vm.startSleep(minutes); sheet = null },
            onEndOfChapter = { vm.sleepEndOfChapter(); sheet = null },
            onExtend = vm::extendSleep,
            onCancel = { vm.cancelSleep(); sheet = null },
            onDismiss = { sheet = null },
            hasChapters = details.chapters.size > 1,
        )
        PlayerSheet.VOICE -> VoiceSheet(
            preset = now.voicePreset,
            defaultPreset = settings.voicePreset,
            onSelect = { vm.setVoicePreset(details.item.id, it) },
            onMakeDefault = { vm.makeDefaultVoicePreset(details.item.id, it) },
            onDismiss = { sheet = null },
        )
        PlayerSheet.CHAPTERS -> ChaptersSheet(
            chapters = details.chapters,
            currentIndex = now.chapterIndex,
            isPlaying = player.isPlaying,
            position = player.positionMs,
            onSelect = { chapter -> vm.seekTo(chapter.startMs); sheet = null },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }
}

// ------------------------------------------------------------------ layouts

@Composable
private fun CompactPlayer(now: NowPlaying, sleep: SleepTimerState, skip: Pair<Int, Int>, cb: PlayerCallbacks) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PlayerTopBar(now, cb)
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = Spacing.lg), contentAlignment = Alignment.Center) {
            BookCover(
                now.details.item.coverPath, now.details.item.metadata.title, now.details.item.metadata.author,
                Modifier.widthIn(max = 420.dp),
                shape = Radii.coverLarge,
                elevation = 24.dp,
            )
        }
        TitleBlock(now, centered = true)
        Spacer(Modifier.height(Spacing.lg))
        ProblemBanner(now.state.problem, cb)
        SeekSection(now, cb)
        Spacer(Modifier.height(Spacing.sm))
        TransportControls(now, skip, cb, playSize = 80.dp)
        Spacer(Modifier.height(Spacing.xl))
        ActionChips(now, sleep, cb, showChapters = true)
        Spacer(Modifier.height(Spacing.lg))
    }
}

@Composable
private fun TwoPanePlayer(now: NowPlaying, sleep: SleepTimerState, skip: Pair<Int, Int>, cb: PlayerCallbacks, window: WindowLayout) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = Spacing.xl)) {
        PlayerTopBar(now, cb)
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xxl)) {
            Box(Modifier.weight(1f).fillMaxSize().padding(vertical = Spacing.lg), contentAlignment = Alignment.Center) {
                BookCover(
                    now.details.item.coverPath, now.details.item.metadata.title, now.details.item.metadata.author,
                    Modifier.widthIn(max = 520.dp),
                    shape = Radii.coverLarge,
                    elevation = 24.dp,
                )
            }
            Column(Modifier.weight(1f).fillMaxSize()) {
                val showList = !window.isShort && now.chapters.size > 1
                if (!showList) Spacer(Modifier.weight(1f))
                TitleBlock(now, centered = false)
                Spacer(Modifier.height(Spacing.lg))
                ProblemBanner(now.state.problem, cb)
                SeekSection(now, cb)
                TransportControls(now, skip, cb, playSize = if (window.isShort) 64.dp else 88.dp)
                Spacer(Modifier.height(Spacing.lg))
                ActionChips(now, sleep, cb, showChapters = !showList)
                if (showList) {
                    Spacer(Modifier.height(Spacing.lg))
                    InlineChapters(now, cb, Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Half-folded Fold on a table: cover above the hinge, controls below it. */
@Composable
private fun TabletopPlayer(now: NowPlaying, sleep: SleepTimerState, skip: Pair<Int, Int>, cb: PlayerCallbacks, hingeTopPx: Float, hingeBottomPx: Float) {
    val density = LocalDensity.current
    val topHeight: Dp = with(density) { hingeTopPx.toDp() }
    val hingeHeight: Dp = with(density) { (hingeBottomPx - hingeTopPx).toDp() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.height(topHeight).fillMaxWidth().safeDrawingPadding().padding(horizontal = Spacing.xl, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(
                now.details.item.coverPath, now.details.item.metadata.title, now.details.item.metadata.author,
                Modifier.fillMaxHeight(),
                shape = Radii.coverLarge,
                elevation = 20.dp,
            )
            Spacer(Modifier.width(Spacing.xl))
            Column(Modifier.weight(1f)) {
                TitleBlock(now, centered = false)
            }
            VoiceButton(now, cb)
            IconButton(onClick = cb.onClose) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.action_close_player))
            }
        }
        Spacer(Modifier.height(hingeHeight))
        Column(
            Modifier.weight(1f).fillMaxWidth().safeDrawingPadding().padding(horizontal = Spacing.xl),
            verticalArrangement = Arrangement.Center,
        ) {
            ProblemBanner(now.state.problem, cb)
            SeekSection(now, cb)
            TransportControls(now, skip, cb, playSize = 80.dp)
            Spacer(Modifier.height(Spacing.md))
            ActionChips(now, sleep, cb, showChapters = true)
        }
    }
}

// ------------------------------------------------------------------ pieces

@Composable
private fun PlayerTopBar(now: NowPlaying, cb: PlayerCallbacks) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        CircleIconButton(
            Icons.Rounded.KeyboardArrowDown, stringResource(R.string.action_close_player), cb.onClose,
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), iconSize = 28.dp,
        )
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.player_now_playing).uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (now.chapters.size > 1) {
                Text(
                    stringResource(R.string.chapter_of, now.chapterIndex + 1, now.chapters.size),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        VoiceButton(now, cb)
        Spacer(Modifier.width(Spacing.sm))
        Box {
            CircleIconButton(Icons.Rounded.MoreVert, stringResource(R.string.action_more), { menu = true }, containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = MaterialTheme.shapes.large) {
                DropdownMenuItem(text = { Text(stringResource(R.string.action_go_to_book)) }, onClick = { menu = false; cb.onOpenBook() })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_edit_details)) }, onClick = { menu = false; cb.onEdit() })
                DropdownMenuItem(text = { Text(stringResource(R.string.action_stop_playback)) }, onClick = { menu = false; cb.onStop() })
            }
        }
    }
}

/** Voice equalizer; highlighted while a preset is on for this book. */
@Composable
private fun VoiceButton(now: NowPlaying, cb: PlayerCallbacks) {
    val on = now.voicePreset != VoicePreset.OFF
    CircleIconButton(
        Icons.Rounded.GraphicEq,
        stringResource(R.string.voice_title) + if (on) ": " + voicePresetName(now.voicePreset) else "",
        { cb.onSheet(PlayerSheet.VOICE) },
        containerColor = if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        contentColor = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun TitleBlock(now: NowPlaying, centered: Boolean) {
    val meta = now.details.item.metadata
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
        now.chapter?.takeIf { now.chapters.size > 1 }?.let {
            Text(
                it.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = align,
            )
            Spacer(Modifier.height(Spacing.xs))
        }
        Text(meta.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = align)
        meta.author?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = align)
        }
    }
}

@Composable
private fun SeekSection(now: NowPlaying, cb: PlayerCallbacks) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth()) {
        SeekBar(
            fraction = now.fraction,
            onSeek = { f -> cb.onSeek(now.positionFor(f)) },
            onPreview = { f -> cb.onPreview(f?.let { now.positionFor(it) }) },
            description = stringResource(R.string.seek_description),
            enabled = now.state.problem == null,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatClock(now.position - now.rangeStart), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.time_left_in_book, formatDuration(context, now.bookLeftMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text("-" + formatClock(now.rangeEnd - now.position), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TransportControls(now: NowPlaying, skip: Pair<Int, Int>, cb: PlayerCallbacks, playSize: Dp) {
    val hasChapters = now.chapters.size > 1
    Row(
        Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = cb.onPrevChapter, enabled = hasChapters, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.action_previous_chapter), Modifier.size(30.dp))
        }
        IconButton(onClick = cb.onSkipBack, modifier = Modifier.size(60.dp)) {
            SkipIcon(skip.first, forward = false, contentDescription = stringResource(R.string.action_skip_back, skip.first))
        }
        PlayButton(now.state, cb.onTogglePlay, playSize)
        IconButton(onClick = cb.onSkipForward, modifier = Modifier.size(60.dp)) {
            SkipIcon(skip.second, forward = true, contentDescription = stringResource(R.string.action_skip_forward, skip.second))
        }
        IconButton(onClick = cb.onNextChapter, enabled = hasChapters && now.chapterIndex < now.chapters.lastIndex, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Rounded.SkipNext, stringResource(R.string.action_next_chapter), Modifier.size(30.dp))
        }
    }
}

@Composable
private fun PlayButton(state: PlayerUiState, onClick: () -> Unit, size: Dp) {
    val playing = state.playWhenReady
    GradientCircleButton(onClick = onClick, size = size, glow = true) {
        if (state.isBuffering && playing) {
            LoadingDots(color = androidx.compose.material3.LocalContentColor.current)
        } else {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(if (playing) R.string.action_pause else R.string.action_play),
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/** Circular arrow with the number of seconds inside, mirrored for "forward". */
@Composable
private fun SkipIcon(seconds: Int, forward: Boolean, contentDescription: String) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            Icons.Rounded.Replay,
            contentDescription = contentDescription,
            modifier = Modifier.size(38.dp).graphicsLayer { if (forward) scaleX = -1f },
        )
        Text(
            seconds.toString(),
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

@Composable
private fun ActionChips(now: NowPlaying, sleep: SleepTimerState, cb: PlayerCallbacks, showChapters: Boolean) {
    val sleepActive = sleep != SleepTimerState.Off
    val sleepLabel = when (sleep) {
        SleepTimerState.Off -> stringResource(R.string.sleep_off)
        is SleepTimerState.Countdown -> formatClock(sleep.remainingMs)
        is SleepTimerState.EndOfChapter -> stringResource(if (now.chapters.size > 1) R.string.sleep_end_of_chapter_short else R.string.sleep_end_of_book_short)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally)) {
        PlayerChip(Icons.Rounded.Speed, formatSpeed(now.state.speed), stringResource(R.string.speed_title)) { cb.onSheet(PlayerSheet.SPEED) }
        PlayerChip(
            Icons.Rounded.Bedtime, sleepLabel, stringResource(R.string.sleep_title),
            active = sleepActive,
        ) { cb.onSheet(PlayerSheet.SLEEP) }
        if (showChapters && now.chapters.size > 1) {
            PlayerChip(Icons.AutoMirrored.Rounded.FormatListBulleted, stringResource(R.string.chapters_title), stringResource(R.string.chapters_title)) {
                cb.onSheet(PlayerSheet.CHAPTERS)
            }
        }
    }
}

@Composable
private fun PlayerChip(icon: ImageVector, label: String, description: String, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = Radii.pill,
        color = if (active) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
        contentColor = if (active) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.heightIn(min = 44.dp),
    ) {
        Row(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = description, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
private fun InlineChapters(now: NowPlaying, cb: PlayerCallbacks, modifier: Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(now.chapterIndex) {
        if (now.chapterIndex >= 0) listState.animateScrollToItem((now.chapterIndex - 1).coerceAtLeast(0))
    }
    Surface(shape = Radii.card, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), modifier = modifier.fillMaxWidth()) {
        LazyColumn(state = listState, contentPadding = PaddingValues(Spacing.sm)) {
            itemsIndexed(now.chapters, key = { _, c -> c.index }) { i, chapter ->
                ChapterRow(
                    chapter = chapter,
                    number = i + 1,
                    isCurrent = i == now.chapterIndex,
                    isPlaying = i == now.chapterIndex && now.state.isPlaying,
                    onClick = { cb.onSeek(chapter.startMs) },
                    progress = if (i == now.chapterIndex) now.fraction else null,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.ProblemBanner(problem: PlaybackProblem?, cb: PlayerCallbacks) {
    AnimatedVisibility(problem != null) {
        val message = when (problem) {
            PlaybackProblem.FILE_UNAVAILABLE -> R.string.playback_error_unavailable
            PlaybackProblem.FILE_DAMAGED -> R.string.playback_error_damaged
            PlaybackProblem.UNSUPPORTED -> R.string.playback_error_unsupported
            else -> R.string.playback_error_other
        }
        Surface(shape = Radii.card, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.md)) {
            Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                }
                Row {
                    QuietButton(stringResource(R.string.action_retry), cb.onRetry, color = MaterialTheme.colorScheme.onErrorContainer)
                    if (problem == PlaybackProblem.FILE_UNAVAILABLE) {
                        QuietButton(stringResource(R.string.action_rescan), cb.onRescan, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}
