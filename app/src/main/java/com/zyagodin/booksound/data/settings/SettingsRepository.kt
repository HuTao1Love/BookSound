package com.zyagodin.booksound.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zyagodin.booksound.core.audio.VoicePreset
import com.zyagodin.booksound.core.library.ProgressFilter
import com.zyagodin.booksound.core.library.SortField
import com.zyagodin.booksound.core.model.DeviceId
import com.zyagodin.booksound.core.organize.NameTemplate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val libraryTreeUri: String? = null,
    val themeMode: ThemeMode = ThemeMode.DARK,
    /** Pure black instead of graphite whenever the dark theme is shown (saves power on OLED). */
    val amoledBlack: Boolean = false,
    val skipBackSeconds: Int = 15,
    val skipForwardSeconds: Int = 30,
    val defaultSpeed: Float = 1f,
    val smartRewind: Boolean = true,
    /** How far smart rewind goes back… */
    val smartRewindSeconds: Int = 10,
    /** …after a pause of at least this long. */
    val smartRewindAfterSeconds: Int = 60,
    val sleepTimerMinutes: Int = 30,
    val sleepFadeOut: Boolean = true,
    /** Shaking the phone while the sleep timer runs starts it over. */
    val shakeToReset: Boolean = true,
    /** A sleep timer that ran out starts again as soon as the book plays again. */
    val sleepRepeat: Boolean = false,
    /**
     * The timer to start again (see [sleepRepeat]): minutes, or 0 for "end of chapter"; null when
     * none ran out or the user turned it off. Kept on disk so it survives the app being killed overnight.
     */
    val sleepRepeatTimer: Int? = null,
    /** The sleep timer starts by itself when the book plays at night and turns off when the night ends. */
    val sleepAutoNight: Boolean = false,
    /** Night hours for [sleepAutoNight], minutes since midnight; the night may span midnight. */
    val sleepNightStartMinute: Int = 22 * 60,
    val sleepNightEndMinute: Int = 7 * 60,
    /** Voice equalizer for books without their own choice. */
    val voicePreset: VoicePreset = VoicePreset.OFF,
    /** Voice equalizer chosen for a particular book (the narrator's voice), by book id. */
    val bookVoicePresets: Map<String, VoicePreset> = emptyMap(),
    val encoderBitrateKbps: Int = 64,
    val downmixToMono: Boolean = false,
    val autoCoverSearch: Boolean = true,
    /** Books converted at the same time. */
    val parallelImports: Int = DEFAULT_PARALLEL_IMPORTS,
    /** Codecs encoding at the same time across all imports (parts of a book or several books); 0 = automatic. */
    val parallelCodecs: Int = 0,
    val librarySort: SortField = SortField.RECENT,
    val libraryDescending: Boolean = true,
    val libraryFilter: ProgressFilter = ProgressFilter.ALL,
    val lastBookId: String? = null,
    /** The "let BookSound run in the background" question was answered; don't ask again. */
    val backgroundPromptShown: Boolean = false,
    /** Patterns for reading book details from a folder/file/torrent name, in the user's order. */
    val nameTemplates: List<String> = NameTemplate.DEFAULTS,
    /** Template last applied in the import editor; applied again automatically when it fits. */
    val lastNameTemplate: String? = null,
    /** False until the first settings snapshot has been read; lets the UI avoid flashing onboarding. */
    val loaded: Boolean = false,
) {
    fun voicePresetFor(bookId: String?): VoicePreset = bookId?.let { bookVoicePresets[it] } ?: voicePreset
}

const val DEFAULT_PARALLEL_IMPORTS = 2

private const val TEMPLATE_SEPARATOR = "\n"

private fun encodePresets(map: Map<String, VoicePreset>): String = map.entries.joinToString("\n") { "${it.key}=${it.value.name}" }

private fun decodePresets(text: String?): Map<String, VoicePreset> = text.orEmpty().lineSequence().mapNotNull { line ->
    val id = line.substringBefore('=', "").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    val preset = runCatching { VoicePreset.valueOf(line.substringAfter('=')) }.getOrNull() ?: return@mapNotNull null
    id to preset
}.toMap()

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context, scope: CoroutineScope) {

    private object Keys {
        val libraryTree = stringPreferencesKey("library_tree_uri")
        val theme = stringPreferencesKey("theme")
        val amoled = booleanPreferencesKey("amoled_black")
        val skipBack = intPreferencesKey("skip_back")
        val skipForward = intPreferencesKey("skip_forward")
        val defaultSpeed = floatPreferencesKey("default_speed")
        val smartRewind = booleanPreferencesKey("smart_rewind")
        val smartRewindSeconds = intPreferencesKey("smart_rewind_seconds")
        val smartRewindAfter = intPreferencesKey("smart_rewind_after")
        val sleepMinutes = intPreferencesKey("sleep_minutes")
        val sleepFade = booleanPreferencesKey("sleep_fade")
        val shake = booleanPreferencesKey("shake_to_reset")
        val sleepRepeat = booleanPreferencesKey("sleep_repeat")
        val sleepRepeatTimer = intPreferencesKey("sleep_repeat_timer")
        val sleepAutoNight = booleanPreferencesKey("sleep_auto_night")
        val sleepNightStart = intPreferencesKey("sleep_night_start")
        val sleepNightEnd = intPreferencesKey("sleep_night_end")
        val voicePreset = stringPreferencesKey("voice_preset")
        val bookVoicePresets = stringPreferencesKey("book_voice_presets")
        val bitrate = intPreferencesKey("encoder_bitrate")
        val mono = booleanPreferencesKey("downmix_mono")
        val autoCover = booleanPreferencesKey("auto_cover_search")
        val parallelImports = intPreferencesKey("parallel_imports")
        val parallelCodecs = intPreferencesKey("parallel_codecs")
        val sort = stringPreferencesKey("library_sort")
        val descending = booleanPreferencesKey("library_descending")
        val filter = stringPreferencesKey("library_filter")
        val lastBook = stringPreferencesKey("last_book_id")
        val backgroundPrompt = booleanPreferencesKey("background_prompt_shown")
        val deviceId = stringPreferencesKey("device_id")
        val syncCursor = stringPreferencesKey("sync_cursor")
        val nameTemplates = stringPreferencesKey("name_templates")
        val lastNameTemplate = stringPreferencesKey("last_name_template")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            libraryTreeUri = p[Keys.libraryTree],
            themeMode = enumOrDefault(p[Keys.theme], ThemeMode.DARK),
            amoledBlack = p[Keys.amoled] ?: false,
            skipBackSeconds = p[Keys.skipBack] ?: 15,
            skipForwardSeconds = p[Keys.skipForward] ?: 30,
            defaultSpeed = p[Keys.defaultSpeed] ?: 1f,
            smartRewind = p[Keys.smartRewind] ?: true,
            smartRewindSeconds = p[Keys.smartRewindSeconds] ?: 10,
            smartRewindAfterSeconds = p[Keys.smartRewindAfter] ?: 60,
            sleepTimerMinutes = p[Keys.sleepMinutes] ?: 30,
            sleepFadeOut = p[Keys.sleepFade] ?: true,
            shakeToReset = p[Keys.shake] ?: true,
            sleepRepeat = p[Keys.sleepRepeat] ?: false,
            sleepRepeatTimer = p[Keys.sleepRepeatTimer],
            sleepAutoNight = p[Keys.sleepAutoNight] ?: false,
            sleepNightStartMinute = p[Keys.sleepNightStart] ?: (22 * 60),
            sleepNightEndMinute = p[Keys.sleepNightEnd] ?: (7 * 60),
            voicePreset = enumOrDefault(p[Keys.voicePreset], VoicePreset.OFF),
            bookVoicePresets = decodePresets(p[Keys.bookVoicePresets]),
            encoderBitrateKbps = p[Keys.bitrate] ?: 64,
            downmixToMono = p[Keys.mono] ?: false,
            autoCoverSearch = p[Keys.autoCover] ?: true,
            parallelImports = p[Keys.parallelImports] ?: DEFAULT_PARALLEL_IMPORTS,
            parallelCodecs = p[Keys.parallelCodecs] ?: 0,
            librarySort = enumOrDefault(p[Keys.sort], SortField.RECENT),
            libraryDescending = p[Keys.descending] ?: true,
            libraryFilter = enumOrDefault(p[Keys.filter], ProgressFilter.ALL),
            lastBookId = p[Keys.lastBook],
            backgroundPromptShown = p[Keys.backgroundPrompt] ?: false,
            nameTemplates = p[Keys.nameTemplates]?.split(TEMPLATE_SEPARATOR)?.filter { it.isNotBlank() } ?: NameTemplate.DEFAULTS,
            lastNameTemplate = p[Keys.lastNameTemplate],
            loaded = true,
        )
    }

    val state: StateFlow<AppSettings> = flow.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun current(): AppSettings = flow.first()

    suspend fun setLibraryTree(uri: String?) = edit { if (uri == null) it.remove(Keys.libraryTree) else it[Keys.libraryTree] = uri }
    suspend fun setTheme(mode: ThemeMode) = edit { it[Keys.theme] = mode.name }
    suspend fun setAmoledBlack(enabled: Boolean) = edit { it[Keys.amoled] = enabled }
    suspend fun setSkipBack(seconds: Int) = edit { it[Keys.skipBack] = seconds }
    suspend fun setSkipForward(seconds: Int) = edit { it[Keys.skipForward] = seconds }
    suspend fun setDefaultSpeed(speed: Float) = edit { it[Keys.defaultSpeed] = speed }
    suspend fun setSmartRewind(enabled: Boolean) = edit { it[Keys.smartRewind] = enabled }
    suspend fun setSmartRewindSeconds(seconds: Int) = edit { it[Keys.smartRewindSeconds] = seconds }
    suspend fun setSmartRewindAfter(seconds: Int) = edit { it[Keys.smartRewindAfter] = seconds }
    suspend fun setSleepTimerMinutes(minutes: Int) = edit { it[Keys.sleepMinutes] = minutes }
    suspend fun setSleepFadeOut(enabled: Boolean) = edit { it[Keys.sleepFade] = enabled }
    suspend fun setShakeToReset(enabled: Boolean) = edit { it[Keys.shake] = enabled }
    suspend fun setSleepRepeat(enabled: Boolean) = edit {
        it[Keys.sleepRepeat] = enabled
        if (!enabled) it.remove(Keys.sleepRepeatTimer)
    }
    suspend fun setSleepRepeatTimer(timer: Int?) = edit { if (timer == null) it.remove(Keys.sleepRepeatTimer) else it[Keys.sleepRepeatTimer] = timer }
    suspend fun setSleepAutoNight(enabled: Boolean) = edit { it[Keys.sleepAutoNight] = enabled }
    suspend fun setSleepNightStart(minuteOfDay: Int) = edit { it[Keys.sleepNightStart] = minuteOfDay }
    suspend fun setSleepNightEnd(minuteOfDay: Int) = edit { it[Keys.sleepNightEnd] = minuteOfDay }
    suspend fun setVoicePreset(preset: VoicePreset) = edit { it[Keys.voicePreset] = preset.name }

    /** Remembers [preset] for [bookId]; null goes back to the default preset. */
    suspend fun setBookVoicePreset(bookId: String, preset: VoicePreset?) = edit {
        val map = decodePresets(it[Keys.bookVoicePresets]).toMutableMap()
        if (preset == null) map.remove(bookId) else map[bookId] = preset
        if (map.isEmpty()) it.remove(Keys.bookVoicePresets) else it[Keys.bookVoicePresets] = encodePresets(map)
    }
    suspend fun setEncoderBitrate(kbps: Int) = edit { it[Keys.bitrate] = kbps }
    suspend fun setDownmixToMono(enabled: Boolean) = edit { it[Keys.mono] = enabled }
    suspend fun setAutoCoverSearch(enabled: Boolean) = edit { it[Keys.autoCover] = enabled }
    suspend fun setParallelImports(books: Int) = edit { it[Keys.parallelImports] = books }
    suspend fun setParallelCodecs(codecs: Int) = edit { it[Keys.parallelCodecs] = codecs }
    suspend fun setLibrarySort(sort: SortField, descending: Boolean) = edit {
        it[Keys.sort] = sort.name
        it[Keys.descending] = descending
    }
    suspend fun setLibraryFilter(filter: ProgressFilter) = edit { it[Keys.filter] = filter.name }
    suspend fun setNameTemplates(templates: List<String>) = edit {
        it[Keys.nameTemplates] = templates.map { t -> t.replace(TEMPLATE_SEPARATOR, " ") }.joinToString(TEMPLATE_SEPARATOR)
    }
    suspend fun setLastNameTemplate(template: String?) = edit {
        if (template == null) it.remove(Keys.lastNameTemplate) else it[Keys.lastNameTemplate] = template
    }
    suspend fun setBackgroundPromptShown() = edit { it[Keys.backgroundPrompt] = true }
    suspend fun setLastBook(bookId: String?) = edit { if (bookId == null) it.remove(Keys.lastBook) else it[Keys.lastBook] = bookId }

    /** Random per-installation identifier used to attribute changes for future sync. */
    suspend fun deviceId(): DeviceId {
        context.dataStore.data.first()[Keys.deviceId]?.let { return DeviceId(it) }
        val created = UUID.randomUUID().toString()
        var result = created
        context.dataStore.edit { prefs ->
            val existing = prefs[Keys.deviceId]
            if (existing == null) prefs[Keys.deviceId] = created else result = existing
        }
        return DeviceId(result)
    }

    suspend fun syncCursor(): String? = context.dataStore.data.first()[Keys.syncCursor]
    suspend fun setSyncCursor(cursor: String) = edit { it[Keys.syncCursor] = cursor }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default
}
