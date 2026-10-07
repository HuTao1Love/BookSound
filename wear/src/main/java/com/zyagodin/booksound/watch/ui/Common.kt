package com.zyagodin.booksound.watch.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The book's small cover, or a headphones glyph without one. */
@Composable
fun BookCover(file: File, revision: Int) {
    val bitmap by produceState<ImageBitmap?>(null, file, revision) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    }
    val image = bitmap
    if (image == null) {
        Icon(Icons.Rounded.Headphones, contentDescription = null)
    } else {
        Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
    }
}

/** 1:02:03 or 2:03. */
fun formatClock(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** 1, 1.25 or 1.5 for the speed label. */
fun formatSpeed(speed: Float): String = "%.2f".format(java.util.Locale.ROOT, speed).trimEnd('0').trimEnd('.')

val SPEEDS = listOf(0.8f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f)
