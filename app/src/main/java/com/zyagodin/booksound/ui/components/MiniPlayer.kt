package com.zyagodin.booksound.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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

val MiniPlayerHeight = 72.dp

/** Floating "now playing" bar shown above library screens. Tap it or swipe it up to open the player. */
@Composable
fun MiniPlayer(
    book: BookDetails,
    state: PlayerUiState,
    onOpen: () -> Unit,
    onTogglePlay: () -> Unit,
    onSkipBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val position = state.positionMs
    val chapter = book.chapters.lastOrNull { it.startMs <= position }
    val duration = book.item.entry.book.durationMs.takeIf { it > 0 } ?: state.durationMs

    // Swipe up: the bar follows the finger a little; far or fast enough opens the player.
    val density = LocalDensity.current
    val openDistance = with(density) { 40.dp.toPx() }
    val maxLift = with(density) { 96.dp.toPx() }
    val openVelocity = with(density) { 500.dp.toPx() }
    val scope = rememberCoroutineScope()
    var lift by remember { mutableFloatStateOf(0f) }
    val drag = rememberDraggableState { delta -> lift = (lift + delta).coerceIn(-maxLift, 0f) }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        shadowElevation = 16.dp,
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
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.action_open_player), onClick = onOpen)
                    .padding(start = Spacing.sm, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
            ) {
                BookCover(book.item.coverPath, book.item.metadata.title, null, Modifier.size(48.dp), shape = RoundedCornerShape(10.dp))
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        book.item.metadata.title,
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = MaterialTheme.typography.titleMedium.fontFamily),
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
                IconButton(onClick = onSkipBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Replay10, contentDescription = stringResource(R.string.action_rewind))
                }
                val playing = state.playWhenReady
                GradientCircleButton(onClick = onTogglePlay, size = 48.dp) {
                    Icon(
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (playing) R.string.action_pause else R.string.action_play),
                        modifier = Modifier.size(28.dp),
                    )
                }
                Spacer(Modifier.width(Spacing.xs))
            }
            Box(Modifier.padding(horizontal = Spacing.lg).padding(bottom = 6.dp)) {
                BookProgressBar(if (duration > 0) position.toFloat() / duration else 0f, height = 3.dp)
            }
        }
    }
}
