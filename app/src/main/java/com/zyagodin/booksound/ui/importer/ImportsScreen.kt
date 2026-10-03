package com.zyagodin.booksound.ui.importer

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyagodin.booksound.R
import com.zyagodin.booksound.importer.ImportFailure
import com.zyagodin.booksound.importer.ImportJob
import com.zyagodin.booksound.importer.ImportService
import com.zyagodin.booksound.importer.ImportStage
import com.zyagodin.booksound.ui.AppNavigator
import com.zyagodin.booksound.ui.LocalBottomOverlayPadding
import com.zyagodin.booksound.ui.components.BookCover
import com.zyagodin.booksound.ui.components.BookProgressBar
import com.zyagodin.booksound.ui.components.ConfirmDialog
import com.zyagodin.booksound.ui.components.MessageState
import com.zyagodin.booksound.ui.components.QuietButton
import com.zyagodin.booksound.ui.navigation.appContainer
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing
import com.zyagodin.booksound.util.formatSize
import kotlin.math.roundToInt

@Composable
fun ImportsScreen(navigator: AppNavigator) {
    val container = appContainer()
    val manager = container.importManager
    val jobs by manager.jobs.collectAsStateWithLifecycle()
    var cancelJob by remember { mutableStateOf<String?>(null) }
    val bottom = LocalBottomOverlayPadding.current + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
            Text(stringResource(R.string.imports_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).padding(start = Spacing.sm))
            if (jobs.any { !it.isActive }) QuietButton(stringResource(R.string.action_clear_finished), manager::dismissFinished)
        }
        if (jobs.isEmpty()) {
            MessageState(
                icon = Icons.Rounded.CloudDone,
                title = stringResource(R.string.imports_empty_title),
                message = stringResource(R.string.imports_empty_message),
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = bottom + Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(jobs.reversed(), key = { it.id }) { job ->
                    JobCard(
                        job = job,
                        onCancel = { cancelJob = job.id },
                        onRetry = { manager.retry(job.id) },
                        onDismiss = { manager.dismiss(job.id) },
                        onOpen = { job.resultBookId?.let { navigator.backToLibrary(); navigator.openBook(it) } },
                        onPlay = {
                            job.resultBookId?.let {
                                container.player.play(it)
                                navigator.openPlayer()
                            }
                        },
                        modifier = Modifier.widthIn(max = 720.dp).animateItem(),
                    )
                }
            }
        }
    }
    cancelJob?.let { id ->
        ConfirmDialog(
            title = stringResource(R.string.cancel_import_title),
            message = stringResource(R.string.cancel_import_message),
            confirmText = stringResource(R.string.action_cancel_import),
            onConfirm = {
                manager.cancel(id)
                cancelJob = null
            },
            onDismiss = { cancelJob = null },
            dismissText = stringResource(R.string.action_keep_going),
            destructive = true,
        )
    }
}

@Composable
private fun JobCard(
    job: ImportJob,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Surface(shape = Radii.card, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookCover(job.coverPath, job.title, job.request.metadata.author, Modifier.size(64.dp))
                Spacer(Modifier.width(Spacing.lg))
                Column(Modifier.weight(1f)) {
                    Text(job.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    job.request.metadata.author?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Spacer(Modifier.height(2.dp))
                    StageLine(job)
                }
                if (job.isActive) {
                    IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, stringResource(R.string.action_cancel_import)) }
                }
            }
            if (job.isActive) {
                Spacer(Modifier.height(Spacing.md))
                BookProgressBar(job.progress ?: 0f, height = 6.dp)
            }
            job.failure?.let { failure ->
                Spacer(Modifier.height(Spacing.md))
                Text(failureMessage(context, failure), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (!job.isActive) {
                Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.End) {
                    when (job.stage) {
                        ImportStage.DONE -> {
                            QuietButton(stringResource(R.string.action_details), onOpen)
                            QuietButton(stringResource(R.string.action_play), onPlay, icon = Icons.Rounded.PlayArrow)
                        }
                        else -> {
                            QuietButton(stringResource(R.string.action_dismiss), onDismiss, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            QuietButton(stringResource(R.string.action_retry), onRetry, icon = Icons.Rounded.Refresh)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StageLine(job: ImportJob) {
    val (icon, color) = when (job.stage) {
        ImportStage.DONE -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.secondary
        ImportStage.FAILED -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
        else -> null to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(
            stringResource(ImportService.stageLabel(job.stage)) + (job.progress?.takeIf { job.isActive }?.let { " · ${(it * 100).roundToInt()}%" } ?: ""),
            style = MaterialTheme.typography.labelMedium,
            color = color,
        )
    }
}

fun failureMessage(context: Context, failure: ImportFailure): String = when (failure) {
    is ImportFailure.InsufficientStorage -> context.getString(
        if (failure.location == ImportFailure.Location.TEMPORARY) R.string.failure_space_temp else R.string.failure_space_library,
        formatSize(context, failure.requiredBytes),
        failure.availableBytes?.let { formatSize(context, it) } ?: "—",
    )
    is ImportFailure.SourceUnavailable -> context.getString(R.string.failure_source, failure.fileName)
    is ImportFailure.UnsupportedFormat -> context.getString(R.string.failure_unsupported, failure.fileName ?: failure.detail.orEmpty())
    is ImportFailure.CorruptedInput -> context.getString(R.string.failure_corrupted)
    is ImportFailure.ConversionFailed -> context.getString(R.string.failure_conversion, failure.detail.orEmpty())
    is ImportFailure.LibraryUnavailable -> context.getString(R.string.failure_library)
    is ImportFailure.WriteFailed -> context.getString(R.string.failure_write, failure.detail.orEmpty())
    is ImportFailure.VerificationFailed -> context.getString(R.string.failure_verification)
    is ImportFailure.TimeLimit -> context.getString(R.string.failure_time_limit)
    is ImportFailure.Unexpected -> context.getString(R.string.failure_unexpected, failure.detail.orEmpty())
}
