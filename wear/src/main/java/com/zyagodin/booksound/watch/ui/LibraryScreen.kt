package com.zyagodin.booksound.watch.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.zyagodin.booksound.watch.R
import com.zyagodin.booksound.watch.WatchContainer
import com.zyagodin.booksound.watch.sync.IncomingBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun LibraryScreen(container: WatchContainer, onOpenPlayer: () -> Unit, onOpenBook: (String) -> Unit) {
    val books by container.library.books.collectAsStateWithLifecycle()
    val player by container.player.state.collectAsStateWithLifecycle()
    val speaker by container.settings.speaker.collectAsStateWithLifecycle()
    val pendingUpdate by container.updates.pending.collectAsStateWithLifecycle()
    // Play services writes incoming files without telling the app: look at them while visible.
    var incoming by remember { mutableStateOf(emptyList<IncomingBook>()) }
    LaunchedEffect(Unit) {
        while (true) {
            incoming = withContext(Dispatchers.IO) { container.receiver.incoming() }
            delay(1_000)
        }
    }
    val context = LocalContext.current
    val free = remember(books) { Formatter.formatShortFileSize(context, container.library.freeBytes()) }
    val nowPlaying = books.firstOrNull { it.id == player.bookId }
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(stringResource(R.string.app_name)) } }
            pendingUpdate?.let { version ->
                item {
                    Button(
                        onClick = container.updates::install,
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(Icons.Rounded.SystemUpdate, contentDescription = null) },
                        secondaryLabel = { Text(version) },
                        label = { Text(stringResource(R.string.update_install)) },
                    )
                }
            }
            if (nowPlaying != null) {
                item {
                    Button(
                        onClick = onOpenPlayer,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        icon = { Icon(Icons.Rounded.GraphicEq, contentDescription = null) },
                        secondaryLabel = { Text(nowPlaying.metadata.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        label = { Text(stringResource(R.string.now_playing)) },
                    )
                }
            }
            items(incoming, key = { "in-" + it.id }) { book ->
                Button(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                    icon = { Icon(Icons.Rounded.Downloading, contentDescription = null) },
                    secondaryLabel = { Text(stringResource(R.string.receiving, (book.progress * 100).roundToInt())) },
                    label = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                )
            }
            if (books.isEmpty() && incoming.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.library_empty),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(books, key = { it.id }) { book ->
                val status = if (book.finished) stringResource(R.string.book_finished)
                else stringResource(R.string.book_progress, (book.progress * 100).roundToInt(), formatClock(book.durationMs - book.positionMs))
                Button(
                    onClick = {
                        container.player.play(book.id)
                        onOpenPlayer()
                    },
                    onLongClick = { onOpenBook(book.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { BookCover(container.library.coverFile(book.id), book.fileRevision) },
                    secondaryLabel = { Text(status, maxLines = 1) },
                    label = { Text(book.metadata.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                )
            }
            item {
                SwitchButton(
                    checked = speaker,
                    onCheckedChange = {
                        container.settings.setSpeaker(it)
                        container.player.restartIfIdle()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    secondaryLabel = { Text(stringResource(R.string.speaker_hint)) },
                    label = { Text(stringResource(R.string.speaker)) },
                )
            }
            item {
                Text(
                    stringResource(R.string.free_space, free) + " · " + container.updates.installedVersion,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
