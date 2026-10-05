package com.zyagodin.booksound.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.audio.VoicePreset
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.playback.SleepTimerState
import com.zyagodin.booksound.ui.components.AppBottomSheet
import com.zyagodin.booksound.ui.components.CircleIconButton
import com.zyagodin.booksound.ui.components.QuietButton
import com.zyagodin.booksound.ui.components.TonalButton
import com.zyagodin.booksound.ui.detail.ChapterRow
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing
import com.zyagodin.booksound.util.formatClock
import com.zyagodin.booksound.util.formatSpeed
import kotlin.math.roundToInt

private val SpeedPresets = listOf(0.8f, 1f, 1.2f, 1.5f, 1.75f, 2f, 2.5f, 3f)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedSheet(speed: Float, defaultSpeed: Float, onSpeed: (Float) -> Unit, onMakeDefault: (Float) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableFloatStateOf(speed) }
    fun set(v: Float) {
        value = ((v * 20).roundToInt() / 20f).coerceIn(0.5f, 3f)
        onSpeed(value)
    }
    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.speed_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Spacing.lg))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(Icons.Rounded.Remove, stringResource(R.string.speed_slower), { set(value - 0.05f) })
                Text(
                    formatSpeed(value),
                    style = MaterialTheme.typography.displayMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(180.dp),
                )
                CircleIconButton(Icons.Rounded.Add, stringResource(R.string.speed_faster), { set(value + 0.05f) })
            }
            Slider(
                value = value,
                onValueChange = { value = (it * 20).roundToInt() / 20f },
                onValueChangeFinished = { onSpeed(value) },
                valueRange = 0.5f..3f,
                steps = 49,
                colors = SliderDefaults.colors(activeTickColor = MaterialTheme.colorScheme.primary, inactiveTickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0f)),
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.md),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                SpeedPresets.forEach { preset ->
                    FilterChip(
                        selected = (value * 100).roundToInt() == (preset * 100).roundToInt(),
                        onClick = { set(preset) },
                        label = { Text(formatSpeed(preset), style = MaterialTheme.typography.labelLarge) },
                        shape = Radii.pill,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier.heightIn(min = 40.dp),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
            if ((value * 100).roundToInt() != (defaultSpeed * 100).roundToInt()) {
                QuietButton(stringResource(R.string.speed_make_default), { onMakeDefault(value) })
            } else {
                Text(stringResource(R.string.speed_is_default), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val SleepOptions = listOf(5, 10, 15, 30, 45, 60, 90)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepSheet(
    state: SleepTimerState,
    hasChapters: Boolean,
    shakeToReset: Boolean,
    onStart: (Int) -> Unit,
    onEndOfChapter: () -> Unit,
    onExtend: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.sleep_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Spacing.lg))
            when (state) {
                SleepTimerState.Off -> Unit
                is SleepTimerState.Countdown, is SleepTimerState.EndOfChapter -> {
                    Surface(shape = Radii.card, color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
                            val remaining = when (state) {
                                is SleepTimerState.Countdown -> state.remainingMs
                                is SleepTimerState.EndOfChapter -> state.remainingMs
                                else -> null
                            }
                            Text(
                                when {
                                    state !is SleepTimerState.EndOfChapter -> stringResource(R.string.sleep_pausing_in)
                                    hasChapters -> stringResource(R.string.sleep_end_of_chapter)
                                    else -> stringResource(R.string.sleep_end_of_book)
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Text(
                                remaining?.let { formatClock(it) } ?: "—",
                                style = MaterialTheme.typography.displaySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Text(stringResource(R.string.sleep_paused_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                            if (state is SleepTimerState.Countdown && state.auto) {
                                Text(
                                    stringResource(R.string.sleep_auto_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            if (shakeToReset) {
                                Text(
                                    stringResource(if (state is SleepTimerState.EndOfChapter) R.string.sleep_shake_hint_chapter else R.string.sleep_shake_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            Spacer(Modifier.height(Spacing.md))
                            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                if (state is SleepTimerState.Countdown) {
                                    TonalButton(stringResource(R.string.sleep_add_minutes, 5), { onExtend(5) })
                                }
                                TonalButton(stringResource(R.string.sleep_turn_off), onCancel)
                            }
                        }
                    }
                    Spacer(Modifier.height(Spacing.lg))
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SleepOptions.forEach { minutes ->
                    SleepOption(stringResource(R.string.minutes_short, minutes)) { onStart(minutes) }
                }
                // Without chapters the book's end is the natural stopping point.
                SleepOption(stringResource(if (hasChapters) R.string.sleep_end_of_chapter else R.string.sleep_end_of_book)) { onEndOfChapter() }
            }
        }
    }
}

@Composable
private fun SleepOption(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = Radii.pill, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.md))
    }
}

/** Equalizer presets for the narrator's voice; the choice is remembered for the book. */
@Composable
fun VoiceSheet(
    preset: VoicePreset,
    defaultPreset: VoicePreset,
    onSelect: (VoicePreset) -> Unit,
    onMakeDefault: (VoicePreset) -> Unit,
    onDismiss: () -> Unit,
) {
    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl)) {
            Text(stringResource(R.string.voice_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Spacing.sm))
            Text(
                stringResource(R.string.voice_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            )
            Spacer(Modifier.height(Spacing.sm))
            VoicePreset.entries.forEach { option ->
                val selected = option == preset
                Surface(
                    onClick = { onSelect(option) },
                    shape = Radii.card,
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.heightIn(min = 56.dp).padding(horizontal = Spacing.sm, vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = selected, onClick = { onSelect(option) })
                        Spacer(Modifier.width(Spacing.sm))
                        Column(Modifier.weight(1f)) {
                            Text(voicePresetName(option), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                voicePresetDescription(option),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(Spacing.md))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (preset != defaultPreset) {
                    QuietButton(stringResource(R.string.voice_make_default), { onMakeDefault(preset) })
                } else {
                    Text(stringResource(R.string.voice_is_default), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun voicePresetName(preset: VoicePreset): String = stringResource(
    when (preset) {
        VoicePreset.OFF -> R.string.voice_off
        VoicePreset.DEEP_MALE -> R.string.voice_deep_male
        VoicePreset.BRIGHT_FEMALE -> R.string.voice_bright_female
        VoicePreset.NO_BASS -> R.string.voice_no_bass
        VoicePreset.OLD_RECORDING -> R.string.voice_old_recording
        VoicePreset.CLARITY -> R.string.voice_clarity
    },
)

@Composable
private fun voicePresetDescription(preset: VoicePreset): String = stringResource(
    when (preset) {
        VoicePreset.OFF -> R.string.voice_off_hint
        VoicePreset.DEEP_MALE -> R.string.voice_deep_male_hint
        VoicePreset.BRIGHT_FEMALE -> R.string.voice_bright_female_hint
        VoicePreset.NO_BASS -> R.string.voice_no_bass_hint
        VoicePreset.OLD_RECORDING -> R.string.voice_old_recording_hint
        VoicePreset.CLARITY -> R.string.voice_clarity_hint
    },
)

@Composable
fun ChaptersSheet(
    chapters: List<Chapter>,
    currentIndex: Int,
    isPlaying: Boolean,
    position: Long,
    onSelect: (Chapter) -> Unit,
    onDismiss: () -> Unit,
) {
    AppBottomSheet(onDismiss = onDismiss, skipPartiallyExpanded = false) {
        Text(
            stringResource(R.string.chapters_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm),
        )
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = (currentIndex - 2).coerceAtLeast(0))
        LazyColumn(state = listState, contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm)) {
            itemsIndexed(chapters, key = { _, c -> c.index }) { i, chapter ->
                val progress = if (i == currentIndex && chapter.durationMs > 0) ((position - chapter.startMs).toFloat() / chapter.durationMs).coerceIn(0f, 1f) else null
                ChapterRow(
                    chapter = chapter,
                    number = i + 1,
                    isCurrent = i == currentIndex,
                    isPlaying = i == currentIndex && isPlaying,
                    onClick = { onSelect(chapter) },
                    progress = progress,
                )
            }
        }
    }
}
