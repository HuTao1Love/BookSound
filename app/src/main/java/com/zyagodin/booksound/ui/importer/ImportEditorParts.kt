package com.zyagodin.booksound.ui.importer

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.organize.ConversionStrategy
import com.zyagodin.booksound.core.organize.CoverOrigin
import com.zyagodin.booksound.core.torrent.AudiobookLayout
import com.zyagodin.booksound.torrent.TorrentPhase
import com.zyagodin.booksound.cover.OnlineCover
import com.zyagodin.booksound.importer.EditorForm
import com.zyagodin.booksound.importer.OnlineCoverState
import com.zyagodin.booksound.importer.SkipReason
import com.zyagodin.booksound.importer.SkippedFile
import com.zyagodin.booksound.ui.components.CoverImage
import com.zyagodin.booksound.ui.components.LoadingDots
import com.zyagodin.booksound.ui.components.QuietButton
import com.zyagodin.booksound.ui.components.SectionHeader
import com.zyagodin.booksound.ui.components.TonalButton
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing
import com.zyagodin.booksound.util.formatClock
import com.zyagodin.booksound.util.formatDuration
import com.zyagodin.booksound.util.formatSize

@Composable
internal fun CoverSection(ui: EditorUi, onPick: () -> Unit, onRemove: () -> Unit, onSuggestion: (OnlineCover) -> Unit) {
    val cover = ui.cover
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(136.dp)) {
                if (cover != null) {
                    CoverImage(cover.file, ui.form?.title.orEmpty(), Modifier.fillMaxSize(), shape = Radii.cover)
                } else {
                    Surface(
                        onClick = onPick,
                        shape = Radii.cover,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                        }
                    }
                }
                if (ui.downloadingCover) {
                    Surface(shape = Radii.cover, color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f), modifier = Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.Center) { LoadingDots(color = MaterialTheme.colorScheme.surface) }
                    }
                }
            }
            Spacer(Modifier.width(Spacing.lg))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.cover_title), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        when {
                            cover == null && ui.torrent != null -> R.string.torrent_cover_auto
                            cover == null -> R.string.cover_none
                            cover.source?.origin == CoverOrigin.EMBEDDED -> R.string.cover_from_file
                            cover.source?.origin == CoverOrigin.FOLDER -> R.string.cover_from_folder
                            else -> R.string.cover_chosen
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.sm))
                TonalButton(stringResource(if (cover == null) R.string.action_choose_cover else R.string.action_change_cover), onPick, icon = Icons.Rounded.ImageSearch)
                if (cover != null) QuietButton(stringResource(R.string.action_remove_cover), onRemove)
            }
        }
        if (cover == null) OnlineSuggestions(ui.online, onSuggestion, onPick)
    }
}

@Composable
private fun OnlineSuggestions(state: OnlineCoverState, onSuggestion: (OnlineCover) -> Unit, onMore: () -> Unit) {
    Surface(shape = Radii.card, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(top = Spacing.lg)) {
        Column(Modifier.padding(Spacing.lg)) {
            when (state) {
                OnlineCoverState.Idle -> SuggestionMessage(Icons.Rounded.ImageSearch, stringResource(R.string.cover_not_found_hint)) {
                    QuietButton(stringResource(R.string.action_search_online), onMore)
                }
                OnlineCoverState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    LoadingDots()
                    Spacer(Modifier.width(Spacing.md))
                    Text(stringResource(R.string.cover_searching), style = MaterialTheme.typography.bodyMedium)
                }
                OnlineCoverState.Offline -> SuggestionMessage(Icons.Rounded.WifiOff, stringResource(R.string.cover_offline)) {
                    QuietButton(stringResource(R.string.action_choose_cover), onMore)
                }
                OnlineCoverState.Failed -> SuggestionMessage(Icons.Rounded.ErrorOutline, stringResource(R.string.cover_search_failed)) {
                    QuietButton(stringResource(R.string.action_choose_cover), onMore)
                }
                is OnlineCoverState.Results -> if (state.covers.isEmpty()) {
                    SuggestionMessage(Icons.Rounded.ImageSearch, stringResource(R.string.cover_no_online_results)) {
                        QuietButton(stringResource(R.string.action_search_manually), onMore)
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text(stringResource(R.string.cover_found_online), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        QuietButton(stringResource(R.string.action_more), onMore)
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        items(state.covers.take(12), key = { it.fullUrl }) { cover ->
                            Surface(onClick = { onSuggestion(cover) }, shape = Radii.cover, modifier = Modifier.size(92.dp)) {
                                CoverImage(cover.thumbnailUrl, cover.title.orEmpty(), Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionMessage(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, action: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Spacing.md))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { action() }
}

@Composable
internal fun MetadataFields(form: EditorForm, seriesIndexInvalid: Boolean, update: ((EditorForm) -> EditorForm) -> Unit) {
    Column(Modifier.padding(top = Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        SectionHeader(stringResource(R.string.section_details))
        EditorField(stringResource(R.string.field_title), form.title, { v -> update { it.copy(title = v) } })
        EditorField(stringResource(R.string.field_author), form.author, { v -> update { it.copy(author = v) } })
        EditorField(stringResource(R.string.field_narrator), form.narrator, { v -> update { it.copy(narrator = v) } })
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            EditorField(stringResource(R.string.field_series), form.series, { v -> update { it.copy(series = v) } }, Modifier.weight(1f))
            EditorField(
                stringResource(R.string.field_series_number),
                form.seriesIndex,
                { v -> update { it.copy(seriesIndex = v.take(8)) } },
                Modifier.width(112.dp),
                keyboardType = KeyboardType.Decimal,
                isError = seriesIndexInvalid,
                enabled = form.series.isNotBlank(),
            )
        }
        if (seriesIndexInvalid) {
            Text(stringResource(R.string.field_series_number_error), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        EditorField(stringResource(R.string.field_year), form.year, { v -> update { it.copy(year = v.filter(Char::isDigit).take(4)) } }, Modifier.width(140.dp), keyboardType = KeyboardType.Number)
        EditorField(stringResource(R.string.field_description), form.description, { v -> update { it.copy(description = v) } }, singleLine = false)
    }
}

@Composable
private fun EditorField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        maxLines = if (singleLine) 1 else 8,
        isError = isError,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardOptions = KeyboardOptions(
            capitalization = if (keyboardType == KeyboardType.Text) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
            keyboardType = keyboardType,
        ),
        colors = OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
internal fun DestinationCard(ui: EditorUi) {
    val context = LocalContext.current
    Surface(shape = Radii.card, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(top = Spacing.xl)) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            InfoLine(Icons.Rounded.Folder, stringResource(R.string.destination_label), ui.destination)
            InfoLine(
                Icons.Rounded.GraphicEq,
                stringResource(R.string.conversion_label),
                when {
                    ui.torrent?.layout == AudiobookLayout.SINGLE_M4B -> stringResource(R.string.torrent_conversion_m4b)
                    ui.torrent != null -> stringResource(R.string.conversion_transcode, ui.bitrateKbps)
                    ui.strategy == ConversionStrategy.REMUX_SINGLE -> stringResource(R.string.conversion_copy)
                    ui.strategy == ConversionStrategy.CONCAT_COPY -> stringResource(R.string.conversion_join)
                    else -> stringResource(R.string.conversion_transcode, ui.bitrateKbps)
                },
            )
            val torrent = ui.torrent
            if (torrent != null) {
                InfoLine(
                    Icons.Rounded.Download,
                    stringResource(R.string.torrent_download_label),
                    listOf(
                        torrentDownloadLine(torrent),
                        pluralStringResource(R.plurals.parts_count, ui.form?.parts?.size ?: 0, ui.form?.parts?.size ?: 0),
                    ).joinToString(" · "),
                )
            } else InfoLine(
                Icons.Rounded.Schedule,
                stringResource(R.string.summary_label),
                listOf(
                    formatDuration(context, ui.totalDurationMs),
                    pluralStringResource(R.plurals.chapters_count, ui.chapterCount, ui.chapterCount),
                    "≈ " + formatSize(context, ui.estimatedBytes),
                ).joinToString(" · "),
            )
        }
    }
}

@Composable
private fun InfoLine(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String) {
    Row {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp).padding(top = 2.dp))
        Spacer(Modifier.width(Spacing.md))
        Column {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun SkippedCard(skipped: List<SkippedFile>, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Surface(shape = Radii.card, color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f), onClick = { expanded = !expanded }, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.lg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(Spacing.md))
                Text(
                    pluralStringResource(R.plurals.skipped_files, skipped.size, skipped.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
            }
            (if (expanded) skipped else skipped.take(3)).forEach { file ->
                Text(
                    "${file.name} — " + stringResource(
                        when (file.reason) {
                            SkipReason.UNSUPPORTED -> R.string.skip_unsupported
                            SkipReason.CORRUPTED -> R.string.skip_corrupted
                            SkipReason.UNREADABLE -> R.string.skip_unreadable
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.xs, start = 36.dp),
                )
            }
            if (!expanded && skipped.size > 3) {
                Text(
                    stringResource(R.string.action_show_all),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = Spacing.sm, start = 36.dp),
                )
            }
        }
    }
}

@Composable
internal fun PartRow(
    index: Int,
    count: Int,
    title: String,
    fileName: String,
    detail: String,
    dragging: Boolean,
    handle: Modifier,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
    modifier: Modifier,
) {
    val moveUp = stringResource(R.string.action_move_up)
    val moveDown = stringResource(R.string.action_move_down)
    Surface(
        onClick = onRename,
        shape = MaterialTheme.shapes.medium,
        color = if (dragging) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
        shadowElevation = if (dragging) 8.dp else 0.dp,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .semantics {
                customActions = buildList {
                    if (index > 0) add(CustomAccessibilityAction(moveUp) { onMove(index - 1); true })
                    if (index < count - 1) add(CustomAccessibilityAction(moveDown) { onMove(index + 1); true })
                }
            },
    ) {
        Row(Modifier.padding(end = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            Box(handle.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.DragIndicator, contentDescription = stringResource(R.string.action_drag_to_reorder), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                (index + 1).toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp),
            )
            Column(Modifier.weight(1f).padding(vertical = Spacing.sm)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "$fileName · $detail",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRemove, enabled = count > 1, modifier = Modifier.alpha(if (count > 1) 1f else 0f)) {
                Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = stringResource(R.string.action_remove_part), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ChapterEditRow(number: Int, title: String, startMs: Long, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Text(number.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(36.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(formatClock(startMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun torrentDownloadLine(torrent: TorrentEditorInfo): String {
    val context = LocalContext.current
    val size = formatSize(context, torrent.downloadBytes)
    return when {
        torrent.phase == TorrentPhase.DOWNLOADED || torrent.progress >= 1f -> stringResource(R.string.torrent_download_done, size)
        !torrent.online -> stringResource(R.string.torrent_download_waiting, size)
        else -> stringResource(R.string.torrent_download_progress, size, (torrent.progress * 100).toInt())
    }
}
