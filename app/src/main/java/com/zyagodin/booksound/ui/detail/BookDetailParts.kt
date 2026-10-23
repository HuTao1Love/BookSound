package com.zyagodin.booksound.ui.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.data.library.BookDetails
import com.zyagodin.booksound.server.ServerTransfer
import com.zyagodin.booksound.ui.components.BookProgressBar
import com.zyagodin.booksound.ui.components.CircleIconButton
import com.zyagodin.booksound.ui.components.QuietButton
import com.zyagodin.booksound.ui.components.SectionHeader
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing
import com.zyagodin.booksound.util.formatClock
import com.zyagodin.booksound.util.formatDuration
import com.zyagodin.booksound.util.formatSize

/** A download from or an upload to the server: what it is doing, its progress and a cancel button. */
@Composable
internal fun TransferProgress(transfer: ServerTransfer, actions: DetailActions) {
    val percent = ((transfer.progress ?: 0f) * 100).toInt()
    val download = transfer.direction == ServerTransfer.Direction.DOWNLOAD
    Surface(shape = Radii.card, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = Spacing.lg, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (transfer.stage) {
                        ServerTransfer.Stage.QUEUED -> stringResource(R.string.server_stage_queued)
                        ServerTransfer.Stage.FINISHING -> stringResource(R.string.server_stage_finishing)
                        else -> stringResource(if (download) R.string.downloading_percent else R.string.uploading_percent, percent)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                CircleIconButton(
                    Icons.Rounded.Close,
                    stringResource(if (download) R.string.action_cancel_download else R.string.action_cancel_upload),
                    { actions.onCancelTransfer(transfer) },
                    size = 40.dp,
                    iconSize = 20.dp,
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                )
            }
            val progress = transfer.progress
            if (progress != null && transfer.stage == ServerTransfer.Stage.RUNNING) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(end = Spacing.md))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(end = Spacing.md))
            }
        }
    }
}

@Composable
internal fun StatsRow(details: BookDetails) {
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

internal fun LazyListScope.infoItems(details: BookDetails, actions: DetailActions, horizontalPadding: androidx.compose.ui.unit.Dp = Spacing.screen) {
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
    if (details.relativePath.isNotEmpty()) {
        item {
            Column(Modifier.padding(horizontal = horizontalPadding).padding(top = Spacing.lg)) {
                SectionHeader(stringResource(R.string.section_file))
                Text(details.relativePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal fun LazyListScope.chapterItems(
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
            enabled = !details.item.isMissing && !details.item.onServerOnly,
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
