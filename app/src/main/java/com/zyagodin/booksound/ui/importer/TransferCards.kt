package com.zyagodin.booksound.ui.importer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zyagodin.booksound.R
import com.zyagodin.booksound.server.ServerTransfer
import com.zyagodin.booksound.ui.components.BookProgressBar
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing
import com.zyagodin.booksound.wear.WatchTransfer

/** A book moving between devices, as the downloads screen shows it: to or from the server, or to the watch. */
data class TransferRow(
    val key: String,
    val title: String,
    val direction: Direction,
    val progress: Float?,
    val active: Boolean,
    val failed: Boolean,
    val status: @Composable () -> String,
    val cancel: () -> Unit,
) {
    enum class Direction { TO_SERVER, FROM_SERVER, TO_WATCH }
}

fun ServerTransfer.toRow(onCancel: () -> Unit) = TransferRow(
    key = "server:${direction}:$bookId",
    title = title,
    direction = if (direction == ServerTransfer.Direction.UPLOAD) TransferRow.Direction.TO_SERVER else TransferRow.Direction.FROM_SERVER,
    progress = progress,
    active = isActive,
    failed = stage == ServerTransfer.Stage.FAILED,
    status = {
        when (stage) {
            ServerTransfer.Stage.QUEUED -> stringResource(R.string.server_stage_queued)
            ServerTransfer.Stage.RUNNING -> progress?.let { "${(it * 100).toInt()}%" } ?: "…"
            ServerTransfer.Stage.DONE -> stringResource(R.string.server_stage_done)
            ServerTransfer.Stage.FAILED -> stringResource(R.string.server_stage_failed, error.orEmpty())
            ServerTransfer.Stage.CANCELLED -> stringResource(R.string.server_stage_cancelled)
        }
    },
    cancel = onCancel,
)

fun WatchTransfer.toRow(onCancel: () -> Unit) = TransferRow(
    key = "watch:$bookId",
    title = title,
    direction = TransferRow.Direction.TO_WATCH,
    progress = progress,
    active = isActive,
    failed = stage == WatchTransfer.Stage.FAILED || stage == WatchTransfer.Stage.NO_SPACE || stage == WatchTransfer.Stage.NO_WATCH,
    status = {
        when (stage) {
            WatchTransfer.Stage.QUEUED -> stringResource(R.string.watch_stage_connecting)
            WatchTransfer.Stage.SENDING -> progress?.let { "${(it * 100).toInt()}%" } ?: "…"
            WatchTransfer.Stage.SAVING -> stringResource(R.string.watch_stage_saving)
            WatchTransfer.Stage.DONE -> stringResource(R.string.server_stage_done)
            WatchTransfer.Stage.NO_WATCH -> stringResource(R.string.transfer_no_watch)
            WatchTransfer.Stage.NO_SPACE -> stringResource(R.string.transfer_no_space)
            WatchTransfer.Stage.FAILED -> stringResource(R.string.watch_failed_title)
            WatchTransfer.Stage.CANCELLED -> stringResource(R.string.server_stage_cancelled)
        }
    },
    cancel = onCancel,
)

@Composable
fun TransferCard(row: TransferRow, modifier: Modifier = Modifier) {
    Surface(shape = Radii.card, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (row.direction) {
                        TransferRow.Direction.TO_SERVER -> Icons.Rounded.CloudUpload
                        TransferRow.Direction.FROM_SERVER -> Icons.Rounded.CloudDownload
                        TransferRow.Direction.TO_WATCH -> Icons.Rounded.Watch
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(Spacing.lg))
                Column(Modifier.weight(1f)) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val where = stringResource(
                        when (row.direction) {
                            TransferRow.Direction.TO_SERVER -> R.string.transfer_to_server
                            TransferRow.Direction.FROM_SERVER -> R.string.transfer_from_server
                            TransferRow.Direction.TO_WATCH -> R.string.transfer_to_watch
                        },
                    )
                    Text(
                        "$where · ${row.status()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (row.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (row.active) {
                    IconButton(onClick = row.cancel) { Icon(Icons.Rounded.Close, stringResource(R.string.action_cancel)) }
                }
            }
            if (row.active) {
                Spacer(Modifier.height(Spacing.md))
                BookProgressBar(row.progress ?: 0f, height = 6.dp)
            }
        }
    }
}
