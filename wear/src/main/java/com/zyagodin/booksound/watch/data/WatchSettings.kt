package com.zyagodin.booksound.watch.data

import android.content.Context
import androidx.core.content.edit
import com.zyagodin.booksound.core.model.DeviceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** The watch app's few settings. */
class WatchSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Identifies this watch in synced listening positions. */
    val deviceId: DeviceId = DeviceId(
        prefs.getString(KEY_DEVICE, null) ?: "watch-${UUID.randomUUID()}".also { prefs.edit { putString(KEY_DEVICE, it) } },
    )

    var lastBookId: String?
        get() = prefs.getString(KEY_LAST_BOOK, null)
        set(value) = prefs.edit { putString(KEY_LAST_BOOK, value) }

    private val _speaker = MutableStateFlow(prefs.getBoolean(KEY_SPEAKER, false))
    /**
     * Play through the watch's own speaker. Off by default: Wear OS expects media in headphones,
     * so playback then waits for Bluetooth headphones and offers to connect them.
     */
    val speaker: StateFlow<Boolean> = _speaker

    fun setSpeaker(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_SPEAKER, enabled) }
        _speaker.value = enabled
    }

    private companion object {
        const val KEY_DEVICE = "device_id"
        const val KEY_LAST_BOOK = "last_book"
        const val KEY_SPEAKER = "speaker"
    }
}
