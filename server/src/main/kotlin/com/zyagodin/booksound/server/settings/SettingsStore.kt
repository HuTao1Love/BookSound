package com.zyagodin.booksound.server.settings

import com.zyagodin.booksound.api.ApiJson
import com.zyagodin.booksound.api.ServerSettingsDto
import com.zyagodin.booksound.api.WatchProfileDto
import com.zyagodin.booksound.api.WatchProfileMode
import com.zyagodin.booksound.server.db.Database
import com.zyagodin.booksound.server.db.str
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** Settings an admin changes while the server runs, and the watch profiles. */
class SettingsStore(private val db: Database) {
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ServerSettingsDto> = _settings
    val current get() = _settings.value

    private fun load(): ServerSettingsDto = db.tx {
        queryOne("SELECT value FROM settings WHERE key = 'server'") { it.str("value") }
    }?.let { runCatching { ApiJson.decodeFromString(ServerSettingsDto.serializer(), it) }.getOrNull() } ?: DEFAULTS

    fun update(new: ServerSettingsDto): ServerSettingsDto {
        val clean = new.copy(
            encoderBitrateKbps = new.encoderBitrateKbps.coerceIn(16, 256),
            parallelImports = new.parallelImports.coerceIn(1, 16),
            torrentUploadLimitKibps = new.torrentUploadLimitKibps.coerceIn(0, 100_000),
            torrentListenPort = new.torrentListenPort.coerceIn(1024, 65535),
            watchCacheLimitMib = new.watchCacheLimitMib.coerceIn(100, 1_000_000),
        )
        db.tx {
            update(
                "INSERT INTO settings (key, value) VALUES ('server', ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                ApiJson.encodeToString(ServerSettingsDto.serializer(), clean),
            )
        }
        _settings.value = clean
        return clean
    }

    fun watchProfiles(): List<WatchProfileDto> {
        val stored = db.tx { query("SELECT body FROM watch_profiles") { ApiJson.decodeFromString(WatchProfileDto.serializer(), it.str("body")!!) } }
        if (stored.isNotEmpty()) return stored.sortedBy { it.bitrateKbps }
        saveWatchProfile(DEFAULT_PROFILE)
        return listOf(DEFAULT_PROFILE)
    }

    fun watchProfile(id: String): WatchProfileDto? = watchProfiles().firstOrNull { it.id == id }

    fun saveWatchProfile(profile: WatchProfileDto): WatchProfileDto {
        val clean = profile.copy(
            id = profile.id.ifBlank { UUID.randomUUID().toString() },
            name = profile.name.trim().ifEmpty { "${profile.bitrateKbps} kbps" }.take(60),
            bitrateKbps = profile.bitrateKbps.coerceIn(16, 128),
            sampleRate = profile.sampleRate?.takeIf { it in SAMPLE_RATES },
            coverSize = profile.coverSize.coerceIn(0, 1024),
        )
        db.tx {
            update(
                "INSERT INTO watch_profiles (id, body) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET body = excluded.body",
                clean.id, ApiJson.encodeToString(WatchProfileDto.serializer(), clean),
            )
        }
        return clean
    }

    fun deleteWatchProfile(id: String) = db.tx { update("DELETE FROM watch_profiles WHERE id = ?", id) }

    companion object {
        val SAMPLE_RATES = setOf(16000, 22050, 24000, 32000, 44100, 48000)

        val DEFAULTS = ServerSettingsDto(
            encoderBitrateKbps = 64,
            downmixMono = false,
            parallelImports = maxOf(1, Runtime.getRuntime().availableProcessors() / 2),
            torrentUploadLimitKibps = 0,
            torrentListenPort = 6881,
            watchCacheLimitMib = 20_000,
        )

        /** Speech stays clear at 32 kbps mono; a 10-hour book is about 150 MB. */
        val DEFAULT_PROFILE = WatchProfileDto(
            id = "watch-32-mono",
            name = "32 kbps mono",
            bitrateKbps = 32,
            mono = true,
            sampleRate = 22050,
            coverSize = 256,
            mode = WatchProfileMode.ON_DEMAND,
        )
    }
}
