package com.zyagodin.booksound.ui.importer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.model.EmbeddedPicture
import com.zyagodin.booksound.core.torrent.TorrentContentProblem
import com.zyagodin.booksound.ui.torrent.problemMessage
import com.zyagodin.booksound.importer.AnalysisFailure
import com.zyagodin.booksound.importer.AnalysisState
import com.zyagodin.booksound.importer.EditorForm
import com.zyagodin.booksound.importer.ImportConflict
import com.zyagodin.booksound.importer.ImportDecision
import com.zyagodin.booksound.importer.SkippedFile
import com.zyagodin.booksound.ui.AppNavigator
import com.zyagodin.booksound.ui.components.BookProgressBar
import com.zyagodin.booksound.ui.components.Choice
import com.zyagodin.booksound.ui.components.ChoiceDialog
import com.zyagodin.booksound.ui.components.ChoiceStyle
import com.zyagodin.booksound.ui.components.ConfirmDialog
import com.zyagodin.booksound.ui.components.ErrorState
import com.zyagodin.booksound.ui.components.LoadingState
import com.zyagodin.booksound.ui.components.PrimaryButton
import com.zyagodin.booksound.ui.components.SectionHeader
import com.zyagodin.booksound.ui.components.TextInputDialog
import com.zyagodin.booksound.ui.components.reorderHandle
import com.zyagodin.booksound.ui.components.rememberReorderState
import com.zyagodin.booksound.ui.components.rememberWindowLayout
import com.zyagodin.booksound.ui.navigation.appViewModel
import com.zyagodin.booksound.ui.theme.Spacing
import com.zyagodin.booksound.util.formatClock
import com.zyagodin.booksound.util.formatDuration
import com.zyagodin.booksound.util.formatSize
import kotlinx.coroutines.launch

private const val PART_KEY = "part:"

@Composable
fun ImportEditorScreen(sessionId: String, navigator: AppNavigator) {
    val appContext = LocalContext.current.applicationContext
    val vm = appViewModel(key = "editor-$sessionId") { ImportEditorViewModel(it, appContext, sessionId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var conflict by remember { mutableStateOf<ImportConflict?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmLeaveTorrent by remember { mutableStateOf(false) }
    var cropPicture by remember { mutableStateOf<EmbeddedPicture?>(null) }
    var renamePart by remember { mutableStateOf<Int?>(null) }
    var renameChapter by remember { mutableStateOf<Int?>(null) }

    fun close() {
        vm.cancel()
        navigator.back()
    }
    val ready = ui.analysis is AnalysisState.Ready && ui.form != null
    val torrent = ui.torrent
    // A torrent keeps downloading when the review is left; ask whether to keep or remove it.
    val isTorrent = torrent != null || ui.analysis is AnalysisState.AwaitingTorrent
    fun onCloseRequest() {
        when {
            isTorrent -> confirmLeaveTorrent = true
            ready -> confirmDiscard = true
            else -> close()
        }
    }
    BackHandler { onCloseRequest() }

    fun handle(outcome: ConfirmOutcome) {
        when (outcome) {
            is ConfirmOutcome.Started -> navigator.openImports(replaceCurrent = true)
            is ConfirmOutcome.NeedsDecision -> conflict = outcome.conflict
            ConfirmOutcome.LibraryUnavailable -> scope.launch { snackbar.showSnackbar(context.getString(R.string.error_library_unavailable)) }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { onCloseRequest() }) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel))
                }
                Text(
                    stringResource(
                        when {
                            isTorrent -> R.string.editor_title_torrent
                            ui.isEdit -> R.string.editor_title_edit
                            else -> R.string.editor_title_import
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = Spacing.sm),
                )
            }
        },
        bottomBar = {
            if (ready) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shadowElevation = 8.dp) {
                    Row(
                        Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            if (torrent != null) {
                                Text(torrentDownloadLine(torrent), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(R.string.torrent_converted_after_download),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(formatDuration(context, ui.totalDurationMs), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "≈ " + formatSize(context, ui.estimatedBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        PrimaryButton(
                            text = stringResource(if (ui.isEdit || torrent?.reviewed == true) R.string.action_save_changes else R.string.action_import),
                            onClick = { vm.confirm(null, ::handle) },
                            enabled = !ui.busy && !ui.seriesIndexInvalid && !ui.downloadingCover,
                            modifier = Modifier.widthIn(min = 160.dp),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (ui.expired) {
                ErrorState(
                    icon = Icons.Rounded.ErrorOutline,
                    title = stringResource(R.string.editor_expired_title),
                    message = stringResource(R.string.editor_expired_message),
                    action = { PrimaryButton(stringResource(R.string.action_back), onClick = navigator::back, icon = Icons.AutoMirrored.Rounded.ArrowBack) },
                )
            } else when (val analysis = ui.analysis) {
                null, is AnalysisState.Analyzing -> {
                    val a = analysis as? AnalysisState.Analyzing
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        LoadingState(
                            title = stringResource(R.string.editor_reading_files),
                            message = a?.takeIf { it.total > 0 }?.let { stringResource(R.string.editor_reading_progress, it.done, it.total, it.currentName.orEmpty()) },
                            modifier = Modifier.height(240.dp),
                        )
                        if (a != null && a.total > 0) {
                            BookProgressBar(a.done.toFloat() / a.total, Modifier.widthIn(max = 280.dp).padding(horizontal = Spacing.xl))
                        }
                    }
                }
                is AnalysisState.AwaitingTorrent -> Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LoadingState(
                        title = stringResource(if (analysis.online) R.string.editor_fetching_torrent else R.string.torrent_waiting_network),
                        message = stringResource(R.string.editor_fetching_torrent_message, analysis.name.orEmpty()),
                        modifier = Modifier.height(240.dp),
                    )
                }
                is AnalysisState.Failed -> AnalysisFailed(analysis, onBack = ::close)
                is AnalysisState.Ready -> ui.form?.let { form ->
                    EditorContent(
                        ui = ui,
                        form = form,
                        skipped = analysis.skipped,
                        vm = vm,
                        onPickCover = { navigator.openCoverPicker(sessionId) },
                        onRenamePart = { renamePart = it },
                        onRenameChapter = { renameChapter = it },
                        onCoverFailed = { scope.launch { snackbar.showSnackbar(context.getString(R.string.cover_download_failed)) } },
                        onOnlineCover = { picture -> if (needsCrop(picture)) cropPicture = picture else vm.useCover(picture) },
                    )
                }
            }
        }
    }

    conflict?.let { c ->
        when (c) {
            is ImportConflict.PathTaken -> ChoiceDialog(
                title = stringResource(R.string.conflict_title),
                message = if (c.existingTitle != null) stringResource(R.string.conflict_book_message, c.path, c.existingTitle)
                else stringResource(R.string.conflict_file_message, c.path),
                choices = listOf(
                    Choice(stringResource(R.string.conflict_keep_both)) { conflict = null; vm.confirm(ImportDecision.KEEP_BOTH, ::handle) },
                    Choice(stringResource(R.string.conflict_replace), ChoiceStyle.DESTRUCTIVE) { conflict = null; vm.confirm(ImportDecision.REPLACE, ::handle) },
                    Choice(stringResource(R.string.action_cancel), ChoiceStyle.NEUTRAL) { conflict = null },
                ),
                onDismiss = { conflict = null },
            )
            is ImportConflict.AlreadyInLibrary -> ChoiceDialog(
                title = stringResource(R.string.duplicate_title),
                message = stringResource(R.string.duplicate_message, c.existingTitle),
                choices = listOf(
                    Choice(stringResource(R.string.duplicate_replace), ChoiceStyle.DESTRUCTIVE) { conflict = null; vm.confirm(ImportDecision.REPLACE, ::handle) },
                    Choice(stringResource(R.string.duplicate_keep_both)) { conflict = null; vm.confirm(ImportDecision.KEEP_BOTH, ::handle) },
                    Choice(stringResource(R.string.action_cancel), ChoiceStyle.NEUTRAL) { conflict = null },
                ),
                onDismiss = { conflict = null },
            )
        }
    }
    cropPicture?.let { picture ->
        SquareCropDialog(
            picture = picture,
            onCropped = {
                vm.useCover(it)
                cropPicture = null
            },
            onDismiss = { cropPicture = null },
        )
    }
    if (confirmLeaveTorrent) {
        ChoiceDialog(
            title = stringResource(R.string.torrent_leave_title),
            message = stringResource(R.string.torrent_leave_message),
            choices = listOf(
                Choice(stringResource(R.string.action_keep_downloading)) { confirmLeaveTorrent = false; close() },
                Choice(stringResource(R.string.action_remove_torrent), ChoiceStyle.DESTRUCTIVE) {
                    confirmLeaveTorrent = false
                    vm.removeTorrent()
                    navigator.back()
                },
                Choice(stringResource(R.string.action_keep_editing), ChoiceStyle.NEUTRAL) { confirmLeaveTorrent = false },
            ),
            onDismiss = { confirmLeaveTorrent = false },
        )
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = stringResource(if (ui.isEdit) R.string.discard_edit_title else R.string.discard_import_title),
            message = stringResource(R.string.discard_message),
            confirmText = stringResource(R.string.action_discard),
            onConfirm = {
                confirmDiscard = false
                close()
            },
            onDismiss = { confirmDiscard = false },
            dismissText = stringResource(R.string.action_keep_editing),
            destructive = true,
        )
    }
    val form = ui.form
    renamePart?.let { index ->
        val part = form?.parts?.getOrNull(index)
        if (part != null) {
            TextInputDialog(
                title = stringResource(R.string.rename_part_title),
                initial = part.title,
                confirmText = stringResource(R.string.action_save),
                dismissText = stringResource(R.string.action_cancel),
                onConfirm = { vm.renamePart(index, it); renamePart = null },
                onDismiss = { renamePart = null },
            )
        }
    }
    renameChapter?.let { index ->
        val chapter = form?.parts?.firstOrNull()?.chapters?.getOrNull(index)
        if (chapter != null) {
            TextInputDialog(
                title = stringResource(R.string.rename_chapter_title),
                initial = chapter.title.orEmpty(),
                confirmText = stringResource(R.string.action_save),
                dismissText = stringResource(R.string.action_cancel),
                onConfirm = { vm.renameChapter(0, index, it); renameChapter = null },
                onDismiss = { renameChapter = null },
            )
        }
    }
}

@Composable
private fun AnalysisFailed(state: AnalysisState.Failed, onBack: () -> Unit) {
    val (title, message) = when (state.reason) {
        AnalysisFailure.NO_AUDIO_FILES -> R.string.analysis_no_audio_title to R.string.analysis_no_audio_message
        AnalysisFailure.ALL_FILES_FAILED -> R.string.analysis_all_failed_title to R.string.analysis_all_failed_message
        AnalysisFailure.SOURCE_UNAVAILABLE -> R.string.analysis_unavailable_title to R.string.analysis_unavailable_message
        AnalysisFailure.BOOK_NOT_FOUND -> R.string.analysis_book_missing_title to R.string.analysis_book_missing_message
        AnalysisFailure.TORRENT_INVALID -> R.string.analysis_torrent_invalid_title to R.string.torrent_requirements
        AnalysisFailure.TORRENT_UNAVAILABLE -> R.string.analysis_torrent_unavailable_title to R.string.analysis_torrent_unavailable_message
        AnalysisFailure.TORRENT_BUSY -> R.string.analysis_torrent_busy_title to R.string.analysis_torrent_busy_message
    }
    val context = LocalContext.current
    val problem = state.detail?.let { name -> TorrentContentProblem.entries.firstOrNull { it.name == name } }
    val text = problem?.let { problemMessage(context, it, state.skipped.map { f -> f.name }) } ?: stringResource(message)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            ErrorState(
                icon = Icons.Rounded.ErrorOutline,
                title = stringResource(title),
                message = text,
                modifier = Modifier.height(380.dp),
                action = { PrimaryButton(stringResource(R.string.action_back), onClick = onBack) },
            )
        }
        // For torrents the offending files are part of the message.
        if (state.skipped.isNotEmpty() && state.reason != AnalysisFailure.TORRENT_INVALID) {
            item { SkippedCard(state.skipped, Modifier.widthIn(max = 560.dp)) }
        }
    }
}

@Composable
private fun EditorContent(
    ui: EditorUi,
    form: EditorForm,
    skipped: List<SkippedFile>,
    vm: ImportEditorViewModel,
    onPickCover: () -> Unit,
    onRenamePart: (Int) -> Unit,
    onRenameChapter: (Int) -> Unit,
    onCoverFailed: () -> Unit,
    onOnlineCover: (EmbeddedPicture) -> Unit,
) {
    val window = rememberWindowLayout()
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(
        listState,
        canSwap = { (it as? String)?.startsWith(PART_KEY) == true },
        onMove = { from, to ->
            val fromIndex = form.parts.indexOfFirst { PART_KEY + it.sourceId == from }
            val toIndex = form.parts.indexOfFirst { PART_KEY + it.sourceId == to }
            vm.movePart(fromIndex, toIndex)
        },
    )
    val details: LazyListScope.() -> Unit = {
        item(key = "cover") {
            CoverSection(ui, onPickCover, vm::removeCover, onSuggestion = { cover -> vm.downloadOnline(cover) { picture -> if (picture == null) onCoverFailed() else onOnlineCover(picture) } })
        }
        if (ui.sourceName.isNotBlank()) {
            item(key = "templates") { NameTemplateSection(ui, vm::applyTemplate, vm::addTemplate, vm::deleteTemplate) }
        }
        item(key = "fields") { MetadataFields(form, ui.seriesIndexInvalid, vm::update) }
        item(key = "destination") { DestinationCard(ui) }
        if (skipped.isNotEmpty()) item(key = "skipped") { SkippedCard(skipped, Modifier.padding(top = Spacing.lg)) }
    }
    val structure: LazyListScope.() -> Unit = {
        val parts = form.parts
        if (parts.size > 1) {
            item(key = "parts-header") {
                SectionHeader(
                    pluralStringResource(R.plurals.parts_count, parts.size, parts.size),
                    Modifier.padding(top = Spacing.lg),
                    trailing = { Text(stringResource(R.string.parts_reorder_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
            itemsIndexed(parts, key = { _, p -> PART_KEY + p.sourceId }) { index, part ->
                val context = LocalContext.current
                val key = PART_KEY + part.sourceId
                val dragging = reorder.draggingKey == key
                PartRow(
                    index = index,
                    count = parts.size,
                    title = part.title,
                    fileName = part.displayName,
                    detail = ui.torrent?.partSizes?.get(part.sourceId)?.let { formatSize(context, it) } ?: formatClock(part.durationMs),
                    dragging = dragging,
                    handle = Modifier.reorderHandle(reorder, key),
                    onRename = { onRenamePart(index) },
                    onRemove = { vm.removePart(index) },
                    onMove = { to -> vm.movePart(index, to) },
                    modifier = if (dragging) {
                        Modifier.zIndex(1f).graphicsLayer { translationY = reorder.offsetOf(key) }
                    } else {
                        Modifier.animateItem()
                    },
                )
            }
        } else {
            val chapters = parts.firstOrNull()?.chapters.orEmpty()
            if (chapters.size > 1) {
                item(key = "chapters-header") {
                    SectionHeader(
                        pluralStringResource(R.plurals.chapters_count, chapters.size, chapters.size),
                        Modifier.padding(top = Spacing.lg),
                        trailing = { Text(stringResource(R.string.chapters_rename_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                }
                itemsIndexed(chapters, key = { i, _ -> "chapter:$i" }) { i, mark ->
                    ChapterEditRow(i + 1, mark.title ?: stringResource(R.string.chapter_number, i + 1), mark.startMs) { onRenameChapter(i) }
                }
            }
        }
    }
    if (window.isWide) {
        Row(Modifier.fillMaxSize().padding(horizontal = Spacing.lg), horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = Spacing.lg), content = details)
            LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(vertical = Spacing.lg), content = structure)
        }
    } else {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.lg),
            modifier = Modifier.fillMaxSize(),
        ) {
            details()
            structure()
        }
    }
}
