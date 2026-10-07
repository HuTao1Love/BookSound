package com.zyagodin.booksound.watch.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.zyagodin.booksound.watch.R
import com.zyagodin.booksound.watch.WatchContainer

@Composable
fun ChaptersScreen(container: WatchContainer, onDone: () -> Unit) {
    val state by container.player.state.collectAsStateWithLifecycle()
    val books by container.library.books.collectAsStateWithLifecycle()
    val chapters = books.firstOrNull { it.id == state.bookId }?.chapters.orEmpty()
    val current = chapters.indexOfLast { it.startMs <= state.positionMs }
    val listState = rememberTransformingLazyColumnState()
    // Open at the chapter being listened to (+1 for the header).
    LaunchedEffect(Unit) { if (current > 0) listState.scrollToItem(current + 1) }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(stringResource(R.string.chapters)) } }
            itemsIndexed(chapters) { index, chapter ->
                Button(
                    onClick = {
                        container.player.seekTo(chapter.startMs)
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (index == current) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                    secondaryLabel = { Text(formatClock(chapter.startMs)) },
                    label = { Text(chapter.title.ifBlank { "${index + 1}" }, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                )
            }
        }
    }
}
