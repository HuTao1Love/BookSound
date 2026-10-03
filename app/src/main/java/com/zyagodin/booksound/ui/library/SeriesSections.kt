package com.zyagodin.booksound.ui.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zyagodin.booksound.R
import com.zyagodin.booksound.data.library.LibraryItem
import com.zyagodin.booksound.ui.components.BookCover
import com.zyagodin.booksound.ui.components.BookProgressBar
import com.zyagodin.booksound.ui.components.CoverBackdrop
import com.zyagodin.booksound.ui.components.remainingLabel
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing
import com.zyagodin.booksound.util.formatDuration

/**
 * One row of the series view. Books without a series are shown as an open shelf; a series is a
 * card with its name, authors and overall progress above a carousel ordered by book number.
 */
@Composable
fun SeriesSectionView(
    section: SeriesSection,
    coverSize: Dp,
    onOpen: (LibraryItem) -> Unit,
    onLongClick: (LibraryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (section.series == null) {
        Column(modifier.fillMaxWidth().padding(vertical = Spacing.sm)) {
            SectionTitle(
                title = stringResource(R.string.library_no_series),
                subtitle = pluralStringResource(R.plurals.book_count, section.items.size, section.items.size),
            )
            Spacer(Modifier.height(Spacing.md))
            Carousel(section.items, numbered = false, coverSize, onOpen, onLongClick, PaddingValues(horizontal = 0.dp))
        }
    } else {
        SeriesCard(section, coverSize, onOpen, onLongClick, modifier)
    }
}

@Composable
private fun SeriesCard(
    section: SeriesSection,
    coverSize: Dp,
    onOpen: (LibraryItem) -> Unit,
    onLongClick: (LibraryItem) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val group = section.group
    val first = section.items.firstOrNull()
    Surface(
        shape = Radii.card,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.sm),
    ) {
        Box {
            CoverBackdrop(first?.coverPath, section.series.orEmpty(), Modifier.matchParentSize(), intensity = 0.45f)
            Column(Modifier.padding(vertical = Spacing.lg)) {
                Row(Modifier.padding(horizontal = Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                    StackedCovers(section.items.take(3))
                    Spacer(Modifier.width(Spacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(section.series.orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(
                                group.authors.take(2).joinToString(", ").takeIf { it.isNotEmpty() },
                                pluralStringResource(R.plurals.book_count, section.items.size, section.items.size),
                                formatDuration(context, group.totalDurationMs),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (group.finishedCount > 0) {
                        Spacer(Modifier.width(Spacing.sm))
                        Surface(shape = Radii.pill, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Text(
                                stringResource(R.string.series_finished_of, group.finishedCount, section.items.size),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                if (group.progress > 0f) {
                    BookProgressBar(group.progress, Modifier.padding(horizontal = Spacing.lg).padding(top = Spacing.md), height = 3.dp)
                }
                Spacer(Modifier.height(Spacing.lg))
                Carousel(section.items, numbered = true, coverSize, onOpen, onLongClick, PaddingValues(horizontal = Spacing.lg))
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.width(Spacing.sm))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
    }
}

/** Up to three covers fanned out, as a small series emblem. */
@Composable
private fun StackedCovers(items: List<LibraryItem>) {
    val size = 52.dp
    Box(Modifier.width(size + 12.dp * (items.size - 1).coerceAtLeast(0)).height(size)) {
        items.reversed().forEachIndexed { reverseIndex, item ->
            val i = items.size - 1 - reverseIndex
            BookCover(
                item.coverPath, item.metadata.title, null,
                Modifier.size(size).offset(x = 12.dp * i),
                shape = RoundedCornerShape(12.dp),
                elevation = 4.dp,
            )
        }
    }
}

@Composable
private fun Carousel(
    items: List<LibraryItem>,
    numbered: Boolean,
    coverSize: Dp,
    onOpen: (LibraryItem) -> Unit,
    onLongClick: (LibraryItem) -> Unit,
    padding: PaddingValues,
) {
    LazyRow(contentPadding = padding, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        items(items, key = { it.id }) { item ->
            CarouselCard(item, numbered, coverSize, { onOpen(item) }, { onLongClick(item) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CarouselCard(item: LibraryItem, numbered: Boolean, coverSize: Dp, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier
            .width(coverSize)
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .padding(bottom = Spacing.sm),
    ) {
        Box {
            BookCover(item.coverPath, item.metadata.title, item.metadata.author, Modifier.size(coverSize), elevation = 6.dp)
            val number = item.metadata.seriesIndex
            if (numbered && number != null) {
                Surface(
                    shape = Radii.pill,
                    color = Color.Black.copy(alpha = 0.55f),
                    contentColor = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                ) {
                    Text("#$number", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
            if (item.entry.positionMs > 0 && !item.entry.finished) {
                BookProgressBar(
                    item.entry.progress,
                    Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 10.dp),
                    height = 4.dp,
                    trackColor = Color.White.copy(alpha = 0.3f),
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(item.metadata.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!numbered) {
            item.metadata.author?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(remainingLabel(item), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f), maxLines = 1)
    }
}
