package com.zyagodin.booksound.ui.importer

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.model.EmbeddedPicture
import com.zyagodin.booksound.cover.CoverImages
import com.zyagodin.booksound.cover.SquareCrop
import com.zyagodin.booksound.ui.components.PrimaryButton
import com.zyagodin.booksound.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Lets the user cut a square cover out of a non-square picture: the picture fills a square frame
 * and can be dragged and pinch-zoomed. [onCropped] receives the square JPEG.
 */
@Composable
fun SquareCropDialog(picture: EmbeddedPicture, onCropped: (EmbeddedPicture) -> Unit, onDismiss: () -> Unit) {
    var decoded by remember(picture) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(picture) {
        decoded = withContext(Dispatchers.Default) { CoverImages.decodeForCrop(picture.bytes) }
        if (decoded == null) onDismiss()
    }
    val bitmap = decoded ?: return
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var viewport by remember { mutableFloatStateOf(0f) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize().safeDrawingPadding().padding(Spacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(stringResource(R.string.crop_title), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(Spacing.lg))
                Box(
                    Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clipToBounds()
                        .background(Color.Black)
                        .onSizeChanged { viewport = it.width.toFloat() }
                        .pointerInput(bitmap) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                if (viewport <= 0f) return@detectTransformGestures
                                zoom = (zoom * gestureZoom).coerceIn(1f, SquareCrop.MAX_ZOOM)
                                val (x, y) = SquareCrop.clampOffset(bitmap.width, bitmap.height, viewport, zoom, offset.x + pan.x, offset.y + pan.y)
                                offset = Offset(x, y)
                            }
                        },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val side = size.width
                        val s = SquareCrop.scale(bitmap.width, bitmap.height, side, zoom)
                        val (x, y) = SquareCrop.clampOffset(bitmap.width, bitmap.height, side, zoom, offset.x, offset.y)
                        val w = bitmap.width * s
                        val h = bitmap.height * s
                        drawImage(
                            image = image,
                            dstOffset = IntOffset(((side - w) / 2 + x).roundToInt(), ((side - h) / 2 + y).roundToInt()),
                            dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                        )
                        // Rule of thirds, to help centring the title or a face.
                        val line = Color.White.copy(alpha = 0.35f)
                        for (i in 1..2) {
                            val p = side * i / 3
                            drawLine(line, Offset(p, 0f), Offset(p, side), strokeWidth = 1.dp.toPx())
                            drawLine(line, Offset(0f, p), Offset(side, p), strokeWidth = 1.dp.toPx())
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.md))
                Text(
                    stringResource(R.string.crop_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.xl))
                Row(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    Spacer(Modifier.weight(1f))
                    PrimaryButton(
                        stringResource(R.string.action_done),
                        onClick = {
                            if (busy || viewport <= 0f) return@PrimaryButton
                            busy = true
                            val region = SquareCrop.region(bitmap.width, bitmap.height, viewport, zoom, offset.x, offset.y)
                            scope.launch {
                                val cropped = withContext(Dispatchers.Default) { CoverImages.crop(bitmap, region) }
                                onCropped(cropped)
                            }
                        },
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

/** True when [picture] is not square and should go through [SquareCropDialog] first. */
fun needsCrop(picture: EmbeddedPicture): Boolean =
    CoverImages.dimensions(picture.bytes)?.let { (w, h) -> !SquareCrop.isSquare(w, h) } ?: false
