package com.zyagodin.booksound.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zyagodin.booksound.R
import com.zyagodin.booksound.data.library.LibraryItem
import com.zyagodin.booksound.ui.theme.Spacing
import com.zyagodin.booksound.util.formatDuration

/** "Series · #3" or null. */
@Composable
fun seriesLabel(series: String?, index: String?): String? =
    series?.let { s -> index?.let { stringResource(R.string.series_with_index, s, it) } ?: s }

/** True when the title doesn't name its series itself, so lists put the series in front of it. */
fun showsSeriesInTitle(title: String, series: String?): Boolean =
    !series.isNullOrBlank() && !title.contains(series.trim(), ignoreCase = true)

/**
 * The title as lists show it: a volume of a series starts with the series and its number, in
 * the accent colour ("**Mushoku Tensei #3**. Childhood — Home Tutor"), since a volume title alone
 * often says little.
 */
@Composable
fun titleWithSeries(title: String, series: String?, index: String?): AnnotatedString {
    if (!showsSeriesInTitle(title, series)) return AnnotatedString(title)
    val name = series!!.trim()
    val prefix = index?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.series_in_title, name, it) } ?: name
    val accent = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        withStyle(SpanStyle(color = accent)) { append(prefix) }
        append(". ")
        append(title)
    }
}

/** The title inside its own series' list, where the series is known: "**#3**. Childhood — Home Tutor". */
@Composable
fun numberedTitle(title: String, index: String?): AnnotatedString {
    val number = index?.takeIf { it.isNotBlank() } ?: return AnnotatedString(title)
    val prefix = stringResource(R.string.series_number, number)
    val accent = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        withStyle(SpanStyle(color = accent)) { append(prefix) }
        append(". ")
        append(title)
    }
}

@Composable
fun remainingLabel(item: LibraryItem): String {
    val context = LocalContext.current
    val entry = item.entry
    return when {
        entry.finished -> stringResource(R.string.state_finished)
        entry.positionMs > 0 -> stringResource(R.string.time_left, formatDuration(context, entry.book.durationMs - entry.positionMs))
        else -> formatDuration(context, entry.book.durationMs)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookListRow(
    item: LibraryItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    /** In the list of one series: only the book's number in front of the title, no series name. */
    inSeries: Boolean = false,
) {
    val meta = item.metadata
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            BookCover(item.coverPath, meta.title, meta.author, Modifier.size(76.dp), elevation = 3.dp)
            StatusBadge(item, Modifier.align(Alignment.TopEnd).padding(4.dp), small = true)
        }
        Spacer(Modifier.width(Spacing.lg))
        Column(Modifier.weight(1f)) {
            Text(
                if (inSeries) numberedTitle(meta.title, meta.seriesIndex) else titleWithSeries(meta.title, meta.series, meta.seriesIndex),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            meta.author?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            seriesLabel(meta.series, meta.seriesIndex)?.takeUnless { inSeries || showsSeriesInTitle(meta.title, meta.series) }?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.entry.positionMs > 0 && !item.entry.finished) {
                    BookProgressBar(item.entry.progress, Modifier.weight(1f), height = 3.dp)
                    Spacer(Modifier.width(Spacing.sm))
                }
                Text(remainingLabel(item), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        CircleIconButton(
            icon = Icons.Rounded.PlayArrow,
            contentDescription = stringResource(R.string.action_play),
            onClick = onPlay,
            enabled = !item.isMissing,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun StatusBadge(item: LibraryItem, modifier: Modifier, small: Boolean = false) {
    val (icon, bg, fg) = when {
        item.isMissing -> Triple(Icons.Rounded.ErrorOutline, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        item.entry.finished -> Triple(Icons.Rounded.Check, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary)
        else -> return
    }
    Surface(shape = CircleShape, color = bg, contentColor = fg, modifier = modifier.size(if (small) 20.dp else 26.dp), shadowElevation = 2.dp) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = stringResource(if (item.isMissing) R.string.state_file_missing else R.string.state_finished),
                modifier = Modifier.size(if (small) 14.dp else 18.dp),
            )
        }
    }
}

/** Row of a bottom-sheet action menu. */
@Composable
fun SheetAction(icon: ImageVector, text: String, onClick: () -> Unit, color: Color = MaterialTheme.colorScheme.onSurface, enabled: Boolean = true) {
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            Icon(icon, contentDescription = null, tint = if (enabled) color else color.copy(alpha = 0.38f))
            Text(text, style = MaterialTheme.typography.bodyLarge, color = if (enabled) color else color.copy(alpha = 0.38f))
        }
    }
}

/** Confirmation for removing a book, with the option to delete its file too. */
@Composable
fun RemoveBookDialog(title: String, filePath: String?, onConfirm: (deleteFile: Boolean) -> Unit, onDismiss: () -> Unit) {
    var deleteFile by rememberSaveable { mutableStateOf(false) }
    ConfirmDialog(
        title = stringResource(R.string.remove_title),
        message = stringResource(R.string.remove_message, title),
        confirmText = stringResource(if (deleteFile) R.string.action_remove_and_delete else R.string.action_remove),
        onConfirm = { onConfirm(deleteFile) },
        onDismiss = onDismiss,
        dismissText = stringResource(R.string.action_cancel),
        destructive = true,
        extraContent = {
            Surface(
                onClick = { deleteFile = !deleteFile },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.padding(top = Spacing.lg).fillMaxWidth(),
            ) {
                Row(Modifier.padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteFile, onCheckedChange = { deleteFile = it })
                    Column(Modifier.padding(end = Spacing.sm)) {
                        Text(stringResource(R.string.remove_delete_file), style = MaterialTheme.typography.bodyMedium)
                        if (filePath != null) {
                            Text(filePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (!deleteFile) {
                Text(
                    stringResource(R.string.remove_keep_file_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
        },
    )
}
