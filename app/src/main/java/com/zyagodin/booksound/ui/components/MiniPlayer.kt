package com.zyagodin.booksound.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zyagodin.booksound.R
import com.zyagodin.booksound.data.library.BookDetails
import com.zyagodin.booksound.playback.PlayerUiState
import com.zyagodin.booksound.ui.theme.Spacing
import kotlinx.coroutines.launch

/** Height of the bar above the navigation bar inset (progress line + content row). */
val MiniPlayerHeight = 62.dp

/**
 * "Now playing" bar docked to the bottom edge of library screens; its background runs under the
 * navigation bar. Tap it or swipe it up to open the player.
 */
@Composable
fun MiniPlayer(
    book: BookDetails,
    state: () -> PlayerUiState,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    onSkipBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The position changes four times a second while playing: only the progress line reads it,
    // the rest of the bar follows the chapter and play/pause.
    val chapter by remember(book.chapters, state) {
        derivedStateOf { book.chapters.lastOrNull { it.startMs <= state().positionMs } }
    }
    val playing by remember(state) { derivedStateOf { state().playWhenReady } }
    val bookDuration = book.item.entry.book.durationMs

    // Swipe up: the bar follows the finger a little; far or fast enough opens the player.
    val density = LocalDensity.current
    val openDistance = with(density) { 40.dp.toPx() }
    val maxLift = with(density) { 96.dp.toPx() }
    val openVelocity = with(density) { 500.dp.toPx() }
    val scope = rememberCoroutineScope()
    var lift by remember { mutableFloatStateOf(0f) }
    val drag = rememberDraggableState { delta -> lift = (lift + delta).coerceIn(-maxLift, 0f) }

    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 12.dp,
        modifier = modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .graphicsLayer { translationY = lift }
            .draggable(
                state = drag,
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    if (lift < -openDistance || velocity < -openVelocity) onOpen()
                    scope.launch { animate(lift, 0f) { value, _ -> lift = value } }
                },
            ),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            PositionLine {
                val s = state()
                val duration = bookDuration.takeIf { it > 0 } ?: s.durationMs
                if (duration > 0) s.positionMs.toFloat() / duration else 0f
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(MiniPlayerHeight - 2.dp)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_open_player), onClick = onOpen)
                    .padding(start = Spacing.md, end = Spacing.sm),
            ) {
                BookCover(book.item.coverPath, book.item.metadata.title, null, Modifier.size(42.dp), shape = RoundedCornerShape(10.dp))
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        book.item.metadata.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        chapter?.title ?: book.item.metadata.author.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onSkipBack, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Rounded.Replay10, contentDescription = stringResource(R.string.action_rewind), modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(Spacing.xs))
                GradientCircleButton(onClick = onTogglePlay, size = 40.dp) {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (playing) R.string.action_pause else R.string.action_play),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

/** The book's progress line; [progress] is read here so the position redraws only this line. */
@Composable
private fun PositionLine(progress: () -> Float) {
    BookProgressBar(
        progress(),
        Modifier.padding(horizontal = 20.dp),
        height = 2.dp,
        trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
    )
}
