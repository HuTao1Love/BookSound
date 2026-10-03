package com.zyagodin.booksound.ui.torrent

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.torrent.TorrentContentProblem
import com.zyagodin.booksound.importer.ImportService
import com.zyagodin.booksound.torrent.TorrentFailure
import com.zyagodin.booksound.torrent.TorrentFailureCode
import com.zyagodin.booksound.torrent.TorrentItem
import com.zyagodin.booksound.torrent.TorrentPhase
import com.zyagodin.booksound.ui.components.BookCover
import com.zyagodin.booksound.ui.components.BookProgressBar
import com.zyagodin.booksound.ui.components.QuietButton
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing
import com.zyagodin.booksound.util.formatSize
import kotlin.math.roundToInt

/** Actions available on a torrent card. */
class TorrentActions(
    val review: (TorrentItem) -> Unit,
    val pause: (TorrentItem) -> Unit,
    val resume: (TorrentItem) -> Unit,
    val retry: (TorrentItem) -> Unit,
    val remove: (TorrentItem) -> Unit,
    val openBook: (bookId: String) -> Unit,
    val play: (bookId: String) -> Unit,
)

@Composable
fun TorrentCard(item: TorrentItem, actions: TorrentActions, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val r = item.record
    val needsReview = !r.reviewed && (r.phase == TorrentPhase.DOWNLOADING || r.phase == TorrentPhase.DOWNLOADED)
    Surface(shape = Radii.card, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookCover(item.coverPath, r.title, r.author, Modifier.size(64.dp))
                Spacer(Modifier.width(Spacing.lg))
                Column(Modifier.weight(1f)) {
                    Text(r.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    r.author?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    // A book of a torrent with several books: name the torrent it comes from.
                    if (r.group != null) {
                        Text(
                            stringResource(R.string.torrent_book_of, r.position + 1, r.name),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    StatusLine(item)
                }
                if (r.phase != TorrentPhase.COMPLETED) {
                    IconButton(onClick = { actions.remove(item) }) { Icon(Icons.Rounded.Close, stringResource(R.string.action_remove_torrent)) }
                }
            }
            progressOf(item)?.let { progress ->
                Spacer(Modifier.height(Spacing.md))
                BookProgressBar(progress, height = 6.dp)
            }
            // No connections: show why the tracker refused us, if it did.
            item.live?.takeIf { it.peers == 0 && r.phase == TorrentPhase.DOWNLOADING }?.trackerError?.let { error ->
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    stringResource(R.string.torrent_tracker_error, error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (needsReview) {
                Spacer(Modifier.height(Spacing.md))
                Text(
                    stringResource(if (r.phase == TorrentPhase.DOWNLOADED) R.string.torrent_waiting_review else R.string.torrent_review_needed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            r.failure?.let { failure ->
                Spacer(Modifier.height(Spacing.md))
                Text(failureMessage(context, failure), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.End) {
                when (r.phase) {
                    TorrentPhase.FETCHING_METADATA, TorrentPhase.DOWNLOADING, TorrentPhase.DOWNLOADED -> {
                        if (r.phase != TorrentPhase.FETCHING_METADATA) {
                            QuietButton(
                                stringResource(if (needsReview) R.string.action_review else R.string.action_edit_details),
                                { actions.review(item) },
                                icon = Icons.Rounded.Edit,
                            )
                        }
                        if (r.phase != TorrentPhase.DOWNLOADED) {
                            if (r.paused) QuietButton(stringResource(R.string.action_resume), { actions.resume(item) }, icon = Icons.Rounded.PlayArrow)
                            else QuietButton(stringResource(R.string.action_pause), { actions.pause(item) }, icon = Icons.Rounded.Pause)
                        }
                    }
                    TorrentPhase.COMPLETED -> r.resultBookId?.let { bookId ->
                        QuietButton(stringResource(R.string.action_details), { actions.openBook(bookId) })
                        QuietButton(stringResource(R.string.action_play), { actions.play(bookId) }, icon = Icons.Rounded.PlayArrow)
                    }
                    TorrentPhase.FAILED -> {
                        if (r.failure?.code != TorrentFailureCode.CONTENT_INVALID && r.suggested != null) {
                            QuietButton(stringResource(R.string.action_edit_details), { actions.review(item) }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (r.failure?.retryable == true) QuietButton(stringResource(R.string.action_retry), { actions.retry(item) }, icon = Icons.Rounded.Refresh)
                    }
                    TorrentPhase.VERIFYING, TorrentPhase.CONVERTING -> Unit
                }
            }
        }
    }
}

private fun progressOf(item: TorrentItem): Float? {
    val r = item.record
    return when (r.phase) {
        TorrentPhase.DOWNLOADING -> item.live?.progress ?: r.progress
        TorrentPhase.CONVERTING -> item.job?.progress ?: 0f
        else -> null
    }
}

@Composable
private fun StatusLine(item: TorrentItem) {
    val r = item.record
    val (icon, color) = when {
        r.phase == TorrentPhase.COMPLETED -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.secondary
        r.phase == TorrentPhase.FAILED -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
        !item.online && (r.phase == TorrentPhase.DOWNLOADING || r.phase == TorrentPhase.FETCHING_METADATA) && !r.paused ->
            Icons.Rounded.WifiOff to MaterialTheme.colorScheme.onSurfaceVariant
        else -> null to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(statusText(item), style = MaterialTheme.typography.labelMedium, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun statusText(item: TorrentItem): String {
    val context = LocalContext.current
    val r = item.record
    val live = item.live
    return when (r.phase) {
        TorrentPhase.FETCHING_METADATA -> when {
            r.paused -> stringResource(R.string.torrent_paused)
            !item.online -> stringResource(R.string.torrent_waiting_network)
            else -> stringResource(R.string.torrent_fetching_metadata)
        }
        TorrentPhase.DOWNLOADING -> {
            val percent = ((live?.progress ?: r.progress) * 100).roundToInt()
            val size = formatSize(context, r.wantedBytes)
            when {
                r.paused -> stringResource(R.string.torrent_paused) + " · $percent% · $size"
                !item.online -> stringResource(R.string.torrent_waiting_network) + " · $percent%"
                live?.checking == true -> stringResource(R.string.torrent_checking) + " · $percent%"
                live == null -> stringResource(R.string.torrent_downloading) + " · $percent% · $size"
                else -> listOfNotNull(
                    "$percent%",
                    live.downloadRate.takeIf { it > 0 }?.let { stringResource(R.string.torrent_speed, formatSize(context, it.toLong())) },
                    when {
                        live.peers == 0 && live.swarm == 0 -> stringResource(R.string.torrent_looking_for_peers)
                        live.swarm > live.peers -> stringResource(R.string.torrent_peers_of, live.peers, live.swarm)
                        else -> pluralStringResource(R.plurals.torrent_peers, live.peers, live.peers)
                    },
                ).joinToString(" · ")
            }
        }
        TorrentPhase.DOWNLOADED -> stringResource(R.string.torrent_downloaded)
        TorrentPhase.VERIFYING -> stringResource(if (item.editing) R.string.torrent_editing else R.string.torrent_verifying)
        TorrentPhase.CONVERTING -> {
            val job = item.job
            if (job == null) stringResource(R.string.torrent_verifying)
            else stringResource(ImportService.stageLabel(job.stage)) + (job.progress?.let { " · ${(it * 100).roundToInt()}%" } ?: "")
        }
        TorrentPhase.COMPLETED -> stringResource(R.string.import_stage_done)
        TorrentPhase.FAILED -> stringResource(R.string.import_stage_failed)
    }
}

fun failureMessage(context: Context, failure: TorrentFailure): String {
    val files = failure.files.take(3).joinToString("\n") { it.substringAfterLast('/') } + if (failure.files.size > 3) "\n…" else ""
    fun withFiles(text: String) = if (files.isBlank()) text else "$text\n$files"
    return when (failure.code) {
        TorrentFailureCode.METADATA_NOT_FOUND -> context.getString(R.string.torrent_failure_metadata)
        TorrentFailureCode.CONTENT_INVALID -> problemMessage(
            context,
            TorrentContentProblem.entries.firstOrNull { it.name == failure.problem } ?: TorrentContentProblem.NO_AUDIO,
            failure.files,
        )
        TorrentFailureCode.NOT_ENOUGH_SPACE -> context.getString(R.string.torrent_failure_space, failure.detail.orEmpty())
        TorrentFailureCode.DOWNLOAD_ERROR -> context.getString(R.string.torrent_failure_download, failure.detail.orEmpty())
        TorrentFailureCode.FILES_MISSING -> withFiles(context.getString(R.string.torrent_failure_files_missing))
        TorrentFailureCode.AUDIOBOOK_INVALID -> withFiles(context.getString(R.string.torrent_failure_invalid_audiobook))
        TorrentFailureCode.CONVERSION_FAILED -> failure.detail ?: context.getString(R.string.failure_unexpected, "")
        TorrentFailureCode.CONVERSION_CANCELLED -> context.getString(R.string.torrent_failure_cancelled)
    }
}

/** Library banner for torrents in progress; tapping it opens the downloads. */
@Composable
fun TorrentBanner(items: List<TorrentItem>, onClick: () -> Unit) {
    val review = items.firstOrNull { !it.record.reviewed && (it.record.phase == TorrentPhase.DOWNLOADING || it.record.phase == TorrentPhase.DOWNLOADED) }
    val first = review ?: items.first()
    Surface(
        onClick = onClick,
        shape = Radii.card,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
    ) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (items.size > 1) pluralStringResource(R.plurals.torrent_banner_many, items.size, items.size)
                        else first.record.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (review != null) stringResource(R.string.torrent_review_needed_short, review.record.title) else statusText(first),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (review != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
            }
            progressOf(first)?.let {
                Spacer(Modifier.height(Spacing.sm))
                BookProgressBar(it, height = 4.dp)
            }
        }
    }
}
