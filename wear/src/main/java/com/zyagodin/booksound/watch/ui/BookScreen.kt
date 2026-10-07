package com.zyagodin.booksound.watch.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.zyagodin.booksound.watch.R
import com.zyagodin.booksound.watch.WatchContainer
import kotlinx.coroutines.launch

/** A book's actions, opened by a long press in the library. */
@Composable
fun BookScreen(container: WatchContainer, bookId: String, onListen: () -> Unit, onDeleted: () -> Unit) {
    val books by container.library.books.collectAsStateWithLifecycle()
    val book = books.firstOrNull { it.id == bookId } ?: return
    val scope = rememberCoroutineScope()
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(book.metadata.title, textAlign = TextAlign.Center) } }
            book.metadata.author?.let { author ->
                item {
                    Text(
                        author,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        container.player.play(book.id)
                        onListen()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(Icons.Rounded.PlayArrow, contentDescription = null) },
                    label = { Text(stringResource(R.string.listen)) },
                )
            }
            item {
                Button(
                    onClick = {
                        if (container.player.state.value.bookId == book.id) container.player.stop()
                        scope.launch {
                            container.library.remove(book.id)
                            onDeleted()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(contentColor = MaterialTheme.colorScheme.error, iconColor = MaterialTheme.colorScheme.error),
                    icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
                    label = { Text(stringResource(R.string.delete_from_watch)) },
                )
            }
        }
    }
}
