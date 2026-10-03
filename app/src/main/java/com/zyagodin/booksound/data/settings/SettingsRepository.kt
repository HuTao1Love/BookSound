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
import com.zyagodin.booksound.core.library.ProgressFilter
import com.zyagodin.booksound.core.library.SortField
import com.zyagodin.booksound.core.model.DeviceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.UUID

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** SERIES groups books into a vertical list of series (books without a series first). */
enum class LibraryLayoutMode { SERIES, GRID, LIST }

data class AppSettings(
    val libraryTreeUri: String? = null,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val skipBackSeconds: Int = 15,
    val skipForwardSeconds: Int = 30,
    val defaultSpeed: Float = 1f,
    val smartRewind: Boolean = true,
    val sleepTimerMinutes: Int = 30,
    val sleepFadeOut: Boolean = true,
    val encoderBitrateKbps: Int = 64,
    val downmixToMono: Boolean = false,
    val autoCoverSearch: Boolean = true,
    val librarySort: SortField = SortField.RECENT,
    val libraryDescending: Boolean = true,
    val libraryFilter: ProgressFilter = ProgressFilter.ALL,
    val libraryLayout: LibraryLayoutMode = LibraryLayoutMode.SERIES,
    val lastBookId: String? = null,
    /** False until the first settings snapshot has been read; lets the UI avoid flashing onboarding. */
    val loaded: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context, scope: CoroutineScope) {

    private object Keys {
        val libraryTree = stringPreferencesKey("library_tree_uri")
        val theme = stringPreferencesKey("theme")
        val skipBack = intPreferencesKey("skip_back")
        val skipForward = intPreferencesKey("skip_forward")
        val defaultSpeed = floatPreferencesKey("default_speed")
        val smartRewind = booleanPreferencesKey("smart_rewind")
        val sleepMinutes = intPreferencesKey("sleep_minutes")
        val sleepFade = booleanPreferencesKey("sleep_fade")
        val bitrate = intPreferencesKey("encoder_bitrate")
        val mono = booleanPreferencesKey("downmix_mono")
        val autoCover = booleanPreferencesKey("auto_cover_search")
        val sort = stringPreferencesKey("library_sort")
        val descending = booleanPreferencesKey("library_descending")
        val filter = stringPreferencesKey("library_filter")
        val layout = stringPreferencesKey("library_layout")
        val lastBook = stringPreferencesKey("last_book_id")
        val deviceId = stringPreferencesKey("device_id")
        val syncCursor = stringPreferencesKey("sync_cursor")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            libraryTreeUri = p[Keys.libraryTree],
            themeMode = enumOrDefault(p[Keys.theme], ThemeMode.DARK),
            skipBackSeconds = p[Keys.skipBack] ?: 15,
            skipForwardSeconds = p[Keys.skipForward] ?: 30,
            defaultSpeed = p[Keys.defaultSpeed] ?: 1f,
            smartRewind = p[Keys.smartRewind] ?: true,
            sleepTimerMinutes = p[Keys.sleepMinutes] ?: 30,
            sleepFadeOut = p[Keys.sleepFade] ?: true,
            encoderBitrateKbps = p[Keys.bitrate] ?: 64,
            downmixToMono = p[Keys.mono] ?: false,
            autoCoverSearch = p[Keys.autoCover] ?: true,
            librarySort = enumOrDefault(p[Keys.sort], SortField.RECENT),
            libraryDescending = p[Keys.descending] ?: true,
            libraryFilter = enumOrDefault(p[Keys.filter], ProgressFilter.ALL),
            libraryLayout = enumOrDefault(p[Keys.layout], LibraryLayoutMode.SERIES),
            lastBookId = p[Keys.lastBook],
            loaded = true,
        )
    }

    val state: StateFlow<AppSettings> = flow.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun current(): AppSettings = flow.first()

    suspend fun setLibraryTree(uri: String?) = edit { if (uri == null) it.remove(Keys.libraryTree) else it[Keys.libraryTree] = uri }
    suspend fun setTheme(mode: ThemeMode) = edit { it[Keys.theme] = mode.name }
    suspend fun setSkipBack(seconds: Int) = edit { it[Keys.skipBack] = seconds }
    suspend fun setSkipForward(seconds: Int) = edit { it[Keys.skipForward] = seconds }
    suspend fun setDefaultSpeed(speed: Float) = edit { it[Keys.defaultSpeed] = speed }
    suspend fun setSmartRewind(enabled: Boolean) = edit { it[Keys.smartRewind] = enabled }
    suspend fun setSleepTimerMinutes(minutes: Int) = edit { it[Keys.sleepMinutes] = minutes }
    suspend fun setSleepFadeOut(enabled: Boolean) = edit { it[Keys.sleepFade] = enabled }
    suspend fun setEncoderBitrate(kbps: Int) = edit { it[Keys.bitrate] = kbps }
    suspend fun setDownmixToMono(enabled: Boolean) = edit { it[Keys.mono] = enabled }
    suspend fun setAutoCoverSearch(enabled: Boolean) = edit { it[Keys.autoCover] = enabled }
    suspend fun setLibrarySort(sort: SortField, descending: Boolean) = edit {
        it[Keys.sort] = sort.name
        it[Keys.descending] = descending
    }
    suspend fun setLibraryFilter(filter: ProgressFilter) = edit { it[Keys.filter] = filter.name }
    suspend fun setLibraryLayout(layout: LibraryLayoutMode) = edit { it[Keys.layout] = layout.name }
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
