package com.zyagodin.booksound.watch.ui

import android.media.AudioManager
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TextButton
import com.zyagodin.booksound.watch.R
import com.zyagodin.booksound.watch.WatchContainer
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun PlayerScreen(container: WatchContainer, onOpenChapters: () -> Unit) {
    val state by container.player.state.collectAsStateWithLifecycle()
    val books by container.library.books.collectAsStateWithLifecycle()
    val book = books.firstOrNull { it.id == state.bookId }
    LaunchedEffect(state.isPlaying) {
        while (state.isPlaying) {
            container.player.refreshPosition()
            delay(500)
        }
    }
    val position = state.positionMs
    val duration = state.durationMs.takeIf { it > 0 } ?: book?.durationMs ?: 0L
    val chapter = book?.chapters?.lastOrNull { it.startMs <= position }
    val chapterEnd = chapter?.endMs?.takeIf { it > chapter.startMs } ?: duration
    val chapterStart = chapter?.startMs ?: 0L
    // The indicator follows progress with snapshotFlow: the lambda must read state, not a captured value.
    val progress = rememberUpdatedState(
        if (chapterEnd > chapterStart) ((position - chapterStart).toFloat() / (chapterEnd - chapterStart)).coerceIn(0f, 1f) else 0f,
    )

    val context = LocalContext.current
    val audio = remember { context.getSystemService(AudioManager::class.java) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    ScreenScaffold { _ ->
        Box(
            Modifier
                .fillMaxSize()
                // The crown or the bezel sets the volume, as in the system media controls.
                .onRotaryScrollEvent { event ->
                    if (abs(event.verticalScrollPixels) >= ROTARY_STEP_PX) {
                        val direction = if (event.verticalScrollPixels > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
                    }
                    true
                }
                .focusRequester(focus)
                .focusable(),
        ) {
            CircularProgressIndicator(progress = { progress.value }, modifier = Modifier.fillMaxSize().padding(2.dp), strokeWidth = 4.dp)
            Column(
                Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    chapter?.title?.takeIf { it.isNotBlank() } ?: book?.metadata?.author.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Text(
                    book?.metadata?.title.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = container.player::skipBack) {
                        Icon(Icons.Rounded.FastRewind, stringResource(R.string.skip_back))
                    }
                    FilledIconButton(
                        onClick = container.player::togglePlayPause,
                        modifier = Modifier.size(IconButtonDefaults.LargeButtonSize),
                        enabled = book != null,
                    ) {
                        val playing = state.playWhenReady
                        Icon(
                            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            stringResource(if (playing) R.string.pause else R.string.play),
                            modifier = Modifier.size(IconButtonDefaults.LargeIconSize),
                        )
                    }
                    IconButton(onClick = container.player::skipForward) {
                        Icon(Icons.Rounded.FastForward, stringResource(R.string.skip_forward))
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    when {
                        state.hasError -> stringResource(R.string.playback_error)
                        state.waitingForHeadphones -> stringResource(R.string.waiting_headphones)
                        else -> "${formatClock(position)} / ${formatClock(duration)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.hasError || state.waitingForHeadphones) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val next = SPEEDS.firstOrNull { it > state.speed + 0.01f } ?: SPEEDS.first()
                        container.player.setSpeed(next)
                    }) {
                        Text(stringResource(R.string.speed, formatSpeed(state.speed)), style = MaterialTheme.typography.labelMedium)
                    }
                    IconButton(onClick = onOpenChapters, enabled = !book?.chapters.isNullOrEmpty()) {
                        Icon(Icons.AutoMirrored.Rounded.List, stringResource(R.string.chapters))
                    }
                }
            }
        }
    }
}

private const val ROTARY_STEP_PX = 1f
