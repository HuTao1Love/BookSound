package com.zyagodin.booksound.ui.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp

/**
 * Seek bar with a large touch area. While dragging, [onPreview] reports the target so the time
 * labels can follow the finger; the seek happens once, on release.
 */
@Composable
fun SeekBar(
    fraction: Float,
    onSeek: (Float) -> Unit,
    onPreview: (Float?) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val preview by rememberUpdatedState(onPreview)
    val shown = (drag ?: fraction).coerceIn(0f, 1f)
    val thumb by animateDpAsState(if (drag != null) 22.dp else 14.dp, label = "thumb")
    val active = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)
    val gradient = com.zyagodin.booksound.ui.theme.LocalGradients.current.accentColors
    val thumbColor = if (enabled) MaterialTheme.colorScheme.onSurface else active
    Canvas(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .semantics {
                contentDescription = description
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                setProgress { target ->
                    seek(target.coerceIn(0f, 1f))
                    true
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset -> seek((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        drag = (offset.x / size.width).coerceIn(0f, 1f)
                        preview(drag)
                    },
                    onDragEnd = {
                        drag?.let(seek)
                        drag = null
                        preview(null)
                    },
                    onDragCancel = {
                        drag = null
                        preview(null)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        drag = (change.position.x / size.width).coerceIn(0f, 1f)
                        preview(drag)
                    },
                )
            },
    ) {
        val barHeight = 6.dp.toPx()
        val y = size.height / 2
        val radius = CornerRadius(barHeight / 2, barHeight / 2)
        drawRoundRect(track, topLeft = Offset(0f, y - barHeight / 2), size = Size(size.width, barHeight), cornerRadius = radius)
        val activeWidth = size.width * shown
        if (enabled) {
            drawRoundRect(Brush.horizontalGradient(gradient, 0f, activeWidth.coerceAtLeast(1f)), topLeft = Offset(0f, y - barHeight / 2), size = Size(activeWidth, barHeight), cornerRadius = radius)
        } else {
            drawRoundRect(active, topLeft = Offset(0f, y - barHeight / 2), size = Size(activeWidth, barHeight), cornerRadius = radius)
        }
        drawCircle(thumbColor, radius = thumb.toPx() / 2, center = Offset(activeWidth, y))
    }
}

