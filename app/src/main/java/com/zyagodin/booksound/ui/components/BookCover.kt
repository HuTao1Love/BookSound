package com.zyagodin.booksound.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.zyagodin.booksound.ui.theme.LocalDarkTheme
import com.zyagodin.booksound.ui.theme.Manrope
import com.zyagodin.booksound.ui.theme.Radii
import java.io.File
import kotlin.math.abs

/**
 * Square book cover with a thin light edge (so dark covers don't melt into the dark UI).
 * Without an image, a generated placeholder is shown: a vivid duotone gradient with the title.
 */
@Composable
fun BookCover(
    coverPath: String?,
    title: String,
    author: String?,
    modifier: Modifier = Modifier,
    shape: Shape = Radii.cover,
    elevation: Dp = 0.dp,
) {
    val edge = if (LocalDarkTheme.current) Color.White.copy(alpha = 0.08f) else Color.Black.copy(alpha = 0.06f)
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .then(
                if (elevation > 0.dp) Modifier.shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.6f))
                else Modifier,
            )
            .clip(shape)
            .border(1.dp, edge, shape),
    ) {
        // The generated placeholder only stands in for a missing cover: shown while the image
        // loads, it flashed a bright title card every time a screen with the cover opened.
        var failed by remember(coverPath) { mutableStateOf(false) }
        if (coverPath == null || failed) {
            CoverPlaceholder(title, author, Modifier.fillMaxSize())
        } else {
            val context = LocalContext.current
            val request = remember(coverPath) {
                ImageRequest.Builder(context).data(File(coverPath)).crossfade(true).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }
    }
}

/** Cover image from any Coil model (URL, file) with the same placeholder. */
@Composable
fun CoverImage(
    model: Any?,
    title: String,
    modifier: Modifier = Modifier,
    shape: Shape = Radii.cover,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(modifier.aspectRatio(1f).clip(shape)) {
        CoverPlaceholder(title, null, Modifier.fillMaxSize())
        if (model != null) {
            AsyncImage(model = model, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * Full-bleed, heavily blurred version of the cover fading into the background — the backdrop of
 * the player and book details. Falls back to a soft accent glow without a cover.
 */
@Composable
fun CoverBackdrop(coverPath: String?, title: String, modifier: Modifier = Modifier, intensity: Float = 0.55f) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier) {
        if (coverPath != null) {
            val context = LocalContext.current
            val request = remember(coverPath) { ImageRequest.Builder(context).data(File(coverPath)).size(256).build() }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = intensity,
                modifier = Modifier.fillMaxSize().blur(72.dp),
            )
        } else {
            val (a, b) = paletteFor(title)
            Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(a.copy(alpha = 0.35f), Color.Transparent))))
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(b.copy(alpha = 0.15f), Color.Transparent))))
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to background.copy(alpha = 0.15f), 0.55f to background.copy(alpha = 0.7f), 1f to background),
            ),
        )
    }
}

private val PlaceholderPalettes = listOf(
    Color(0xFFFFB27D) to Color(0xFFE0607E),
    Color(0xFF8ED8BF) to Color(0xFF3F7FA8),
    Color(0xFFBBB0FF) to Color(0xFF6A55D8),
    Color(0xFFFFD48A) to Color(0xFFE5835A),
    Color(0xFF8CC8F0) to Color(0xFF4A5BC4),
    Color(0xFFF4A3C4) to Color(0xFF8E5BD0),
)

private fun paletteFor(title: String) = PlaceholderPalettes[abs(title.hashCode()) % PlaceholderPalettes.size]

@Composable
private fun CoverPlaceholder(title: String, author: String?, modifier: Modifier) {
    val (light, dark) = paletteFor(title)
    BoxWithConstraints(
        modifier.background(Brush.linearGradient(listOf(light, dark))),
        contentAlignment = Alignment.BottomStart,
    ) {
        val maxWidth = this.maxWidth
        val small = maxWidth < 72.dp
        if (small) {
            Text(
                text = title.trim().take(1).uppercase(),
                fontFamily = Manrope,
                fontWeight = FontWeight.ExtraBold,
                fontSize = (maxWidth.value * 0.44f).sp,
                color = Color.White.copy(alpha = 0.95f),
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Column(Modifier.padding(maxWidth * 0.1f)) {
                Text(
                    text = title,
                    fontFamily = Manrope,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = (maxWidth.value * 0.1f).coerceIn(11f, 30f).sp,
                    lineHeight = (maxWidth.value * 0.115f).coerceIn(13f, 34f).sp,
                    letterSpacing = (-0.03).em,
                    color = Color.White,
                    textAlign = TextAlign.Start,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!author.isNullOrBlank()) {
                    Text(
                        text = author,
                        fontFamily = Manrope,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = (maxWidth.value * 0.055f).coerceIn(8f, 14f).sp,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = maxWidth * 0.03f),
                    )
                }
            }
        }
    }
}
