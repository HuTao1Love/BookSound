package com.zyagodin.booksound.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zyagodin.booksound.AppContainer
import com.zyagodin.booksound.BuildConfigInfo
import com.zyagodin.booksound.update.UpdateState
import com.zyagodin.booksound.R
import com.zyagodin.booksound.importer.ImportPipeline
import com.zyagodin.booksound.core.audio.VoicePreset
import com.zyagodin.booksound.data.library.ScanResult
import com.zyagodin.booksound.data.settings.AppSettings
import com.zyagodin.booksound.data.settings.ThemeMode
import com.zyagodin.booksound.ui.AppNavigator
import com.zyagodin.booksound.ui.LocalBottomOverlayPadding
import com.zyagodin.booksound.ui.components.AppBottomSheet
import com.zyagodin.booksound.ui.components.BackgroundWork
import com.zyagodin.booksound.ui.components.rememberBackgroundWorkRequest
import com.zyagodin.booksound.ui.components.SectionHeader
import com.zyagodin.booksound.ui.navigation.appViewModel
import com.zyagodin.booksound.ui.player.voicePresetName
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing
import com.zyagodin.booksound.util.DebugLog
import com.zyagodin.booksound.util.formatSpeed
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settings.state
    val removedCount: StateFlow<Int> = container.library.removed.map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val scanning: StateFlow<Boolean> = container.scanner.scanning
    private val s get() = container.settings

    fun folderLabel(context: android.content.Context): String =
        settings.value.libraryTreeUri?.let {
            container.documents.describeTree(Uri.parse(it), context.getString(R.string.storage_internal), context.getString(R.string.storage_sd_card))
        } ?: ""

    fun changeFolder(uri: Uri, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = runCatching { container.documents.takePersistablePermission(uri, write = true) }.isSuccess
        if (ok) {
            val old = settings.value.libraryTreeUri
            s.setLibraryTree(uri.toString())
            if (old != null && old != uri.toString()) container.documents.releasePersistablePermission(Uri.parse(old))
            container.scanner.scan()
        }
        onDone(ok)
    }

    fun rescan(onDone: (ScanResult) -> Unit) = viewModelScope.launch { onDone(container.scanner.scan()) }
    fun setTheme(mode: ThemeMode) = viewModelScope.launch { s.setTheme(mode) }
    fun setAmoled(v: Boolean) = viewModelScope.launch { s.setAmoledBlack(v) }
    fun setSkipBack(v: Int) = viewModelScope.launch { s.setSkipBack(v) }
    fun setSkipForward(v: Int) = viewModelScope.launch { s.setSkipForward(v) }
    fun setDefaultSpeed(v: Float) = viewModelScope.launch { s.setDefaultSpeed(v) }
    fun setSmartRewind(v: Boolean) = viewModelScope.launch { s.setSmartRewind(v) }
    fun setSmartRewindSeconds(v: Int) = viewModelScope.launch { s.setSmartRewindSeconds(v) }
    fun setSmartRewindAfter(v: Int) = viewModelScope.launch { s.setSmartRewindAfter(v) }
    fun setVoicePreset(v: VoicePreset) = viewModelScope.launch { s.setVoicePreset(v) }
    fun setSleepMinutes(v: Int) = viewModelScope.launch { s.setSleepTimerMinutes(v) }
    fun setSleepFade(v: Boolean) = viewModelScope.launch { s.setSleepFadeOut(v) }
    fun setShake(v: Boolean) = viewModelScope.launch { s.setShakeToReset(v) }
    fun setSleepRepeat(v: Boolean) = viewModelScope.launch { s.setSleepRepeat(v) }
    fun setSleepAutoNight(v: Boolean) = viewModelScope.launch { s.setSleepAutoNight(v) }
    fun setSleepNightStart(v: Int) = viewModelScope.launch { s.setSleepNightStart(v) }
    fun setSleepNightEnd(v: Int) = viewModelScope.launch { s.setSleepNightEnd(v) }
    fun setBitrate(v: Int) = viewModelScope.launch { s.setEncoderBitrate(v) }
    fun setMono(v: Boolean) = viewModelScope.launch { s.setDownmixToMono(v) }
    fun setAutoCover(v: Boolean) = viewModelScope.launch { s.setAutoCoverSearch(v) }
    fun setParallelImports(v: Int) = viewModelScope.launch { s.setParallelImports(v) }
    fun setParallelCodecs(v: Int) = viewModelScope.launch { s.setParallelCodecs(v) }

    val update: StateFlow<UpdateState> = container.updates.state
    fun checkUpdates() = container.updates.check()
    fun installUpdate(checked: UpdateState.Checked) = container.updates.update(checked)
    fun dismissUpdate() = container.updates.dismiss()
}

private enum class ChoiceKind { SKIP_BACK, SKIP_FORWARD, SPEED, SLEEP, BITRATE, REWIND_AMOUNT, REWIND_AFTER, VOICE, PARALLEL_BOOKS, PARALLEL_CODECS, NIGHT_START, NIGHT_END }

@Composable
fun SettingsScreen(navigator: AppNavigator) {
    val vm = appViewModel { SettingsViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val removed by vm.removedCount.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var choice by remember { mutableStateOf<ChoiceKind?>(null) }
    var unrestricted by remember { mutableStateOf(BackgroundWork.isUnrestricted(context)) }
    val requestBackgroundWork = rememberBackgroundWorkRequest { unrestricted = it }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.changeFolder(uri) { ok ->
            scope.launch { snackbar.showSnackbar(context.getString(if (ok) R.string.settings_folder_changed else R.string.settings_folder_failed)) }
        }
    }
    val bottom = LocalBottomOverlayPadding.current + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
                Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = Spacing.sm))
            }
            LazyColumn(
                contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = bottom + Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Column(Modifier.widthIn(max = 720.dp)) {
                        Group(stringResource(R.string.settings_group_library)) {
                            Item(Icons.Rounded.Folder, stringResource(R.string.settings_library_folder), vm.folderLabel(context), onClick = { pickFolder.launch(null) })
                            Item(
                                Icons.Rounded.Refresh,
                                stringResource(R.string.settings_rescan),
                                stringResource(if (scanning) R.string.settings_rescan_running else R.string.settings_rescan_hint),
                                onClick = {
                                    vm.rescan { r ->
                                        val msg = when (r) {
                                            is ScanResult.Done -> context.getString(R.string.scan_result, r.added, r.missing)
                                            else -> context.getString(R.string.scan_permission_lost)
                                        }
                                        scope.launch { snackbar.showSnackbar(msg) }
                                    }
                                },
                            )
                            Item(Icons.Rounded.DeleteSweep, stringResource(R.string.settings_removed_books), stringResource(R.string.settings_removed_books_hint, removed), onClick = navigator::openRemovedBooks)
                        }
                        Group(stringResource(R.string.settings_group_playback)) {
                            Item(Icons.Rounded.FastRewind, stringResource(R.string.settings_skip_back), stringResource(R.string.seconds_value, settings.skipBackSeconds), onClick = { choice = ChoiceKind.SKIP_BACK })
                            Item(Icons.Rounded.FastForward, stringResource(R.string.settings_skip_forward), stringResource(R.string.seconds_value, settings.skipForwardSeconds), onClick = { choice = ChoiceKind.SKIP_FORWARD })
                            Item(Icons.Rounded.Speed, stringResource(R.string.settings_default_speed), formatSpeed(settings.defaultSpeed), onClick = { choice = ChoiceKind.SPEED })
                            Toggle(Icons.Rounded.Replay, stringResource(R.string.settings_smart_rewind), stringResource(R.string.settings_smart_rewind_hint), settings.smartRewind, vm::setSmartRewind)
                            if (settings.smartRewind) {
                                Item(Icons.Rounded.History, stringResource(R.string.settings_smart_rewind_amount), stringResource(R.string.seconds_value, settings.smartRewindSeconds), onClick = { choice = ChoiceKind.REWIND_AMOUNT })
                                Item(Icons.Rounded.Timer, stringResource(R.string.settings_smart_rewind_after), pauseLabel(settings.smartRewindAfterSeconds), onClick = { choice = ChoiceKind.REWIND_AFTER })
                            }
                            Item(Icons.Rounded.Equalizer, stringResource(R.string.settings_voice_default), voicePresetName(settings.voicePreset), onClick = { choice = ChoiceKind.VOICE })
                        }
                        Group(stringResource(R.string.settings_group_sleep)) {
                            Item(Icons.Rounded.Bedtime, stringResource(R.string.settings_sleep_default), stringResource(R.string.minutes_short, settings.sleepTimerMinutes), onClick = { choice = ChoiceKind.SLEEP })
                            Toggle(Icons.AutoMirrored.Rounded.VolumeDown, stringResource(R.string.settings_sleep_fade), stringResource(R.string.settings_sleep_fade_hint), settings.sleepFadeOut, vm::setSleepFade)
                            Toggle(Icons.Rounded.Vibration, stringResource(R.string.settings_shake), stringResource(R.string.settings_shake_hint), settings.shakeToReset, vm::setShake)
                            Toggle(Icons.Rounded.Repeat, stringResource(R.string.settings_sleep_repeat), stringResource(R.string.settings_sleep_repeat_hint), settings.sleepRepeat, vm::setSleepRepeat)
                            Toggle(Icons.Rounded.NightsStay, stringResource(R.string.settings_sleep_auto_night), stringResource(R.string.settings_sleep_auto_night_hint), settings.sleepAutoNight, vm::setSleepAutoNight)
                            if (settings.sleepAutoNight) {
                                Item(Icons.Rounded.Schedule, stringResource(R.string.settings_sleep_night_start), timeLabel(settings.sleepNightStartMinute), onClick = { choice = ChoiceKind.NIGHT_START })
                                Item(Icons.Rounded.WbSunny, stringResource(R.string.settings_sleep_night_end), timeLabel(settings.sleepNightEndMinute), onClick = { choice = ChoiceKind.NIGHT_END })
                            }
                        }
                        Group(stringResource(R.string.settings_group_import)) {
                            Item(Icons.Rounded.GraphicEq, stringResource(R.string.settings_quality), bitrateLabel(settings.encoderBitrateKbps), onClick = { choice = ChoiceKind.BITRATE })
                            Toggle(Icons.Rounded.Speaker, stringResource(R.string.settings_mono), stringResource(R.string.settings_mono_hint), settings.downmixToMono, vm::setMono)
                            Toggle(Icons.Rounded.ImageSearch, stringResource(R.string.settings_auto_cover), stringResource(R.string.settings_auto_cover_hint), settings.autoCoverSearch, vm::setAutoCover)
                            Item(Icons.Rounded.Layers, stringResource(R.string.settings_parallel_books), parallelBooksLabel(settings.parallelImports), onClick = { choice = ChoiceKind.PARALLEL_BOOKS })
                            Item(Icons.Rounded.Memory, stringResource(R.string.settings_parallel_codecs), parallelCodecsLabel(settings.parallelCodecs), onClick = { choice = ChoiceKind.PARALLEL_CODECS })
                            Item(
                                Icons.Rounded.BatteryFull,
                                stringResource(R.string.settings_background),
                                stringResource(if (unrestricted) R.string.settings_background_on else R.string.settings_background_off),
                                onClick = requestBackgroundWork,
                            )
                        }
                        Group(stringResource(R.string.settings_group_appearance)) {
                            Row(Modifier.padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Palette, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(Spacing.lg))
                                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                                    ThemeMode.entries.forEachIndexed { i, mode ->
                                        SegmentedButton(
                                            selected = settings.themeMode == mode,
                                            onClick = { vm.setTheme(mode) },
                                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                                            label = {
                                                Text(
                                                    stringResource(
                                                        when (mode) {
                                                            ThemeMode.SYSTEM -> R.string.theme_system
                                                            ThemeMode.LIGHT -> R.string.theme_light
                                                            ThemeMode.DARK -> R.string.theme_dark
                                                        },
                                                    ),
                                                    style = MaterialTheme.typography.labelMedium,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                            Toggle(Icons.Rounded.DarkMode, stringResource(R.string.settings_amoled), stringResource(R.string.settings_amoled_hint), settings.amoledBlack, vm::setAmoled)
                        }
                        Group(stringResource(R.string.settings_group_about)) {
                            Item(Icons.Rounded.CloudSync, stringResource(R.string.settings_sync), stringResource(R.string.settings_sync_hint), onClick = null)
                            Item(Icons.Rounded.Info, stringResource(R.string.app_name), stringResource(R.string.settings_version, BuildConfigInfo.versionName(context)), onClick = null)
                            Item(
                                Icons.Rounded.SystemUpdate,
                                stringResource(R.string.update_check),
                                stringResource(if (update == UpdateState.Checking) R.string.update_checking else R.string.update_check_hint),
                                onClick = vm::checkUpdates,
                            )
                            Item(
                                Icons.Rounded.BugReport,
                                stringResource(R.string.settings_debug_log),
                                stringResource(R.string.settings_debug_log_hint),
                                onClick = {
                                    scope.launch {
                                        runCatching { DebugLog.export(context) }
                                            .onSuccess { context.startActivity(DebugLog.shareIntent(it)) }
                                            .onFailure { snackbar.showSnackbar(context.getString(R.string.debug_log_failed)) }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottom))
    }

    if (update != UpdateState.Idle && update != UpdateState.Checking) {
        UpdateDialog(update, onUpdate = vm::installUpdate, onDismiss = vm::dismissUpdate)
    }

    when (choice) {
        ChoiceKind.SKIP_BACK -> OptionsSheet(stringResource(R.string.settings_skip_back), listOf(5, 10, 15, 30, 60), settings.skipBackSeconds, { stringResource(R.string.seconds_value, it) }, { vm.setSkipBack(it); choice = null }) { choice = null }
        ChoiceKind.SKIP_FORWARD -> OptionsSheet(stringResource(R.string.settings_skip_forward), listOf(10, 15, 30, 45, 60), settings.skipForwardSeconds, { stringResource(R.string.seconds_value, it) }, { vm.setSkipForward(it); choice = null }) { choice = null }
        ChoiceKind.SPEED -> OptionsSheet(stringResource(R.string.settings_default_speed), listOf(0.8f, 0.9f, 1f, 1.1f, 1.2f, 1.25f, 1.3f, 1.5f, 1.75f, 2f), settings.defaultSpeed, { formatSpeed(it) }, { vm.setDefaultSpeed(it); choice = null }) { choice = null }
        ChoiceKind.SLEEP -> OptionsSheet(stringResource(R.string.settings_sleep_default), listOf(5, 10, 15, 20, 30, 45, 60, 90), settings.sleepTimerMinutes, { stringResource(R.string.minutes_short, it) }, { vm.setSleepMinutes(it); choice = null }) { choice = null }
        ChoiceKind.REWIND_AMOUNT -> OptionsSheet(stringResource(R.string.settings_smart_rewind_amount), listOf(5, 10, 15, 20, 30), settings.smartRewindSeconds, { stringResource(R.string.seconds_value, it) }, { vm.setSmartRewindSeconds(it); choice = null }) { choice = null }
        ChoiceKind.REWIND_AFTER -> OptionsSheet(stringResource(R.string.settings_smart_rewind_after), listOf(10, 30, 60, 300, 900, 1800), settings.smartRewindAfterSeconds, { pauseLabel(it) }, { vm.setSmartRewindAfter(it); choice = null }) { choice = null }
        ChoiceKind.VOICE -> OptionsSheet(stringResource(R.string.settings_voice_default), VoicePreset.entries, settings.voicePreset, { voicePresetName(it) }, { vm.setVoicePreset(it); choice = null }) { choice = null }
        ChoiceKind.BITRATE -> OptionsSheet(stringResource(R.string.settings_quality), listOf(48, 64, 96, 128), settings.encoderBitrateKbps, { bitrateLabel(it) }, { vm.setBitrate(it); choice = null }) { choice = null }
        ChoiceKind.PARALLEL_BOOKS -> OptionsSheet(stringResource(R.string.settings_parallel_books), listOf(1, 2, 3), settings.parallelImports, { parallelBooksLabel(it) }, { vm.setParallelImports(it); choice = null }) { choice = null }
        ChoiceKind.PARALLEL_CODECS -> OptionsSheet(stringResource(R.string.settings_parallel_codecs), listOf(0, 1, 2, 3, 4), settings.parallelCodecs, { parallelCodecsLabel(it) }, { vm.setParallelCodecs(it); choice = null }) { choice = null }
        ChoiceKind.NIGHT_START -> TimeSheet(stringResource(R.string.settings_sleep_night_start), settings.sleepNightStartMinute, { vm.setSleepNightStart(it); choice = null }) { choice = null }
        ChoiceKind.NIGHT_END -> TimeSheet(stringResource(R.string.settings_sleep_night_end), settings.sleepNightEndMinute, { vm.setSleepNightEnd(it); choice = null }) { choice = null }
        null -> Unit
    }
}

/** [minuteOfDay] as a clock time, in the 12/24-hour format the phone uses. */
@Composable
private fun timeLabel(minuteOfDay: Int): String {
    val pattern = if (android.text.format.DateFormat.is24HourFormat(LocalContext.current)) "H:mm" else "h:mm a"
    return LocalTime.of(minuteOfDay / 60, minuteOfDay % 60).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

@Composable
private fun pauseLabel(seconds: Int): String =
    if (seconds < 60) stringResource(R.string.seconds_value, seconds) else stringResource(R.string.minutes_short, seconds / 60)

@Composable
private fun parallelBooksLabel(books: Int): String = pluralStringResource(R.plurals.parallel_books, books, books)

/** 0 is automatic: half the CPU cores, 2 to 4. */
@Composable
private fun parallelCodecsLabel(codecs: Int): String =
    if (codecs <= 0) stringResource(R.string.parallel_codecs_auto, ImportPipeline.AUTO_CODECS)
    else pluralStringResource(R.plurals.parallel_codecs, codecs, codecs)

@Composable
private fun bitrateLabel(kbps: Int): String = stringResource(
    when (kbps) {
        48 -> R.string.quality_48
        64 -> R.string.quality_64
        96 -> R.string.quality_96
        else -> R.string.quality_128
    },
)

@Composable
private fun UpdateDialog(state: UpdateState, onUpdate: (UpdateState.Checked) -> Unit, onDismiss: () -> Unit) {
    val offersUpdate = state is UpdateState.Checked && !state.upToDate
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.SystemUpdate, null) },
        title = { Text(stringResource(R.string.update_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                when (state) {
                    is UpdateState.Checked -> {
                        val latest = state.release.version.toString()
                        if (state.upToDate) Text(stringResource(R.string.update_latest, latest))
                        Text(
                            if (state.phoneUpdate) stringResource(R.string.update_phone_line, state.phoneVersion, latest)
                            else stringResource(R.string.update_phone_current, state.phoneVersion),
                        )
                        Text(
                            when {
                                state.watchVersion == null -> stringResource(R.string.update_no_watch)
                                state.watchUpdate -> stringResource(R.string.update_watch_line, state.watchVersion, latest)
                                else -> stringResource(R.string.update_watch_current, state.watchVersion)
                            },
                        )
                    }
                    is UpdateState.Working -> {
                        Text(
                            stringResource(
                                when (state.step) {
                                    UpdateState.Step.DOWNLOADING_WATCH -> R.string.update_step_downloading_watch
                                    UpdateState.Step.SENDING_WATCH -> R.string.update_step_sending_watch
                                    UpdateState.Step.DOWNLOADING_PHONE -> R.string.update_step_downloading_phone
                                },
                            ),
                        )
                        val progress = state.progress
                        if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is UpdateState.Done -> {
                        if (state.watchSent) Text(stringResource(R.string.update_done_watch))
                        if (state.phoneInstalling) Text(stringResource(R.string.update_done_phone))
                    }
                    is UpdateState.Failed -> Text(
                        stringResource(
                            when (state.reason) {
                                UpdateState.Reason.NETWORK -> R.string.update_failed_network
                                UpdateState.Reason.NO_RELEASE -> R.string.update_failed_no_release
                                UpdateState.Reason.WATCH_UNREACHABLE -> R.string.update_failed_watch_unreachable
                                UpdateState.Reason.WATCH_NO_SPACE -> R.string.update_failed_watch_space
                                UpdateState.Reason.WATCH_FAILED -> R.string.update_failed_watch
                                UpdateState.Reason.INSTALL -> R.string.update_failed_install
                            },
                        ),
                    )
                    UpdateState.Idle, UpdateState.Checking -> Unit
                }
            }
        },
        confirmButton = {
            when {
                offersUpdate -> TextButton(onClick = { onUpdate(state as UpdateState.Checked) }) { Text(stringResource(R.string.update_install)) }
                // While downloading the dialog can be hidden; the work goes on.
                else -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_close)) }
            }
        },
        dismissButton = {
            if (offersUpdate) TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) }
        },
    )
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionHeader(title, Modifier.padding(top = Spacing.xl, start = Spacing.xs))
    Surface(shape = Radii.card, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

@Composable
private fun Item(icon: ImageVector, title: String, subtitle: String?, onClick: (() -> Unit)?) {
    val content: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Spacing.lg))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (!subtitle.isNullOrEmpty()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onClick != null) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (onClick != null) Surface(onClick = onClick, color = androidx.compose.ui.graphics.Color.Transparent) { content() } else content()
}

@Composable
private fun Toggle(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(onClick = { onChange(!checked) }, color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Spacing.lg))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(Spacing.md))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeSheet(title: String, minuteOfDay: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(
        initialHour = minuteOfDay / 60,
        initialMinute = minuteOfDay % 60,
        is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current),
    )
    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm))
            Spacer(Modifier.size(Spacing.md))
            TimePicker(state)
            FilledTonalButton(onClick = { onSelect(state.hour * 60 + state.minute) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.action_done))
            }
        }
    }
}

@Composable
private fun <T> OptionsSheet(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, onSelect: (T) -> Unit, onDismiss: () -> Unit) {
    AppBottomSheet(onDismiss = onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = Spacing.xl, vertical = Spacing.sm))
        Column(Modifier.padding(bottom = Spacing.xl), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            options.forEach { option ->
                Surface(onClick = { onSelect(option) }, color = androidx.compose.ui.graphics.Color.Transparent) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Spacing.xl), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = option == selected, onClick = { onSelect(option) })
                        Spacer(Modifier.size(Spacing.md))
                        Text(label(option), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}
