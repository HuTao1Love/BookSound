package com.zyagodin.booksound.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The BookSound server's HTTP API. Every body is JSON in the shape of these classes; paths are in [ApiPaths].
 *
 * Raised when a change would break clients built against the previous version; clients compare it with
 * [StatusDto.apiVersion] and ask for an update instead of misreading the server.
 */
const val API_VERSION = 1

/** JSON settings both sides use: unknown fields are ignored so either side can add optional ones. */
val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

object ApiPaths {
    const val STATUS = "/api/status"
    const val SETUP = "/api/auth/setup"
    const val LOGIN = "/api/auth/login"
    const val WEB_LOGIN = "/api/auth/web-login"
    const val PASSWORD = "/api/auth/password"
    const val REFRESH = "/api/auth/refresh"
    const val LOGOUT = "/api/auth/logout"
    const val ME = "/api/auth/me"
    const val DEVICE_CODE = "/api/auth/device-code"
    const val DEVICE_CODE_POLL = "/api/auth/device-code/poll"
    const val DEVICE_CODE_APPROVE = "/api/auth/device-code/approve"
    const val DEVICES = "/api/devices"
    const val WATCH_DEVICE = "/api/devices/watch"
    const val SYNC_PULL = "/api/sync/pull"
    const val SYNC_PUSH = "/api/sync/push"
    const val BOOKS = "/api/books"
    const val UPLOADS = "/api/uploads"
    const val IMPORTS = "/api/imports"
    const val TORRENTS = "/api/torrents"
    const val EVENTS = "/api/events"
    const val ADMIN_USERS = "/api/admin/users"
    const val ADMIN_SETTINGS = "/api/admin/settings"
    const val ADMIN_RESCAN = "/api/admin/rescan"
    const val WATCH_PROFILES = "/api/watch-profiles"
    const val PREFERENCES = "/api/preferences"

    fun book(id: String) = "$BOOKS/$id"
    fun bookFile(id: String) = "$BOOKS/$id/file"
    fun bookWatchFile(id: String, profileId: String) = "$BOOKS/$id/watch-file?profile=$profileId"
    fun bookCover(id: String, size: Int? = null) = "$BOOKS/$id/cover" + (size?.let { "?size=$it" } ?: "")
    fun upload(id: String) = "$UPLOADS/$id"
    fun uploadComplete(id: String) = "$UPLOADS/$id/complete"

    /** Header a cookie-authenticated (web) request must carry to change anything: a form on another site can't set it. */
    const val CSRF_HEADER = "X-BookSound-Csrf"
}

// ------------------------------------------------------------------------------------------- status

@Serializable
data class StatusDto(
    val serverVersion: String,
    val apiVersion: Int,
    /** No account exists yet: the first one is created with the setup token from the server's log. */
    val setupRequired: Boolean,
    /** Optional parts this server has, see [Features]. */
    val features: List<String>,
)

object Features {
    const val TORRENTS = "torrents"
    const val TRANSCODE = "transcode"
}

@Serializable
data class ErrorDto(val code: String, val message: String? = null)

object ErrorCodes {
    const val UNAUTHORIZED = "unauthorized"
    const val FORBIDDEN = "forbidden"
    const val NOT_FOUND = "not_found"
    const val BAD_REQUEST = "bad_request"
    const val CONFLICT = "conflict"
    const val INVALID_CREDENTIALS = "invalid_credentials"
    const val TOO_MANY_ATTEMPTS = "too_many_attempts"
    const val SETUP_DONE = "setup_done"
    const val PENDING = "pending"
    const val EXPIRED = "expired"
    const val NOT_AUDIOBOOK = "not_audiobook"
    const val UNSUPPORTED = "unsupported"
    const val INSUFFICIENT_STORAGE = "insufficient_storage"
}

// --------------------------------------------------------------------------------------------- auth

@Serializable
enum class DeviceKind { PHONE, WATCH, WEB }

@Serializable
data class UserDto(val id: String, val name: String, val isAdmin: Boolean)

@Serializable
data class SetupRequest(val setupToken: String, val username: String, val password: String)

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
    val deviceName: String,
    val deviceKind: DeviceKind,
    /** The device's sync id, used as `updatedBy` in its changes; the server makes one when absent. */
    val deviceId: String? = null,
)

/** Answer to a login or refresh. Access tokens are short-lived; the refresh token is replaced on every refresh. */
@Serializable
data class TokenResponse(
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
    val deviceId: String,
    val user: UserDto,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

/** Web login: the session is a cookie, so no tokens are returned. */
@Serializable
data class WebLoginRequest(val username: String, val password: String)

@Serializable
data class SessionDto(val user: UserDto, val deviceId: String)

@Serializable
data class DeviceDto(
    val id: String,
    val name: String,
    val kind: DeviceKind,
    val createdAt: Long,
    val lastSeenAt: Long?,
    /** The device this request was made from. */
    val current: Boolean,
)

/** A phone hands the watch its own login: the watch keeps this refresh token. */
@Serializable
data class WatchDeviceRequest(val deviceName: String, val deviceId: String? = null)

/** Sent from the phone to its watch over the Wear Data Layer: the server and the watch's own tokens. */
@Serializable
data class WatchServerLogin(val url: String, val tokens: TokenResponse, val watchProfileId: String? = null)

@Serializable
data class DeviceCodeStartRequest(val deviceName: String, val deviceKind: DeviceKind, val deviceId: String? = null)

@Serializable
data class DeviceCodeResponse(
    /** Short code shown on the device and typed in on the web or the phone, e.g. "K7QX-2M4P". */
    val userCode: String,
    /** Secret the device polls with. */
    val pollToken: String,
    val expiresAt: Long,
    val intervalSeconds: Int,
)

@Serializable
data class DeviceCodePollRequest(val pollToken: String)

@Serializable
data class DeviceCodeApproveRequest(val userCode: String)

// ------------------------------------------------------------------------------------------ library

@Serializable
data class MetadataDto(
    val title: String,
    val author: String? = null,
    val narrator: String? = null,
    val series: String? = null,
    /** Decimal string ("3", "3.5"). */
    val seriesIndex: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val description: String? = null,
    val language: String? = null,
)

@Serializable
data class ChapterDto(val index: Int, val title: String, val startMs: Long, val endMs: Long)

@Serializable
data class StampDto(val revision: Long, val updatedAt: Long, val updatedBy: String, val deleted: Boolean = false)

@Serializable
data class BookDto(
    val id: String,
    val metadata: MetadataDto,
    val durationMs: Long,
    val sizeBytes: Long,
    val sha256: String?,
    /** Raised each time the file is rewritten; a client with an older revision downloads again. */
    val fileRevision: Int,
    val coverSha256: String?,
    val addedAt: Long,
    val stamp: StampDto,
    val chapters: List<ChapterDto> = emptyList(),
    /** Path inside the server's library folder, e.g. "Author/Series/01 - Title.m4b". */
    val relativePath: String? = null,
)

@Serializable
data class PlaybackDto(
    val bookId: String,
    val positionMs: Long,
    val speed: Float,
    val finished: Boolean,
    val lastPlayedAt: Long?,
    val stamp: StampDto,
)

@Serializable
data class PreferencesDto(
    val defaultSpeed: Float,
    val sleepTimerMinutes: Int,
    val skipBackSeconds: Int,
    val skipForwardSeconds: Int,
    /** Voice equalizer preset per book id (names of `VoicePreset`). */
    val voicePresets: Map<String, String> = emptyMap(),
    /** Watch profile the user's watches download, or null for the original file. */
    val watchProfileId: String? = null,
    val stamp: StampDto,
)

/** A book with the caller's progress, for screens that show both. */
@Serializable
data class LibraryEntryDto(val book: BookDto, val playback: PlaybackDto? = null)

@Serializable
data class MetadataPatch(val metadata: MetadataDto, val baseRevision: Long)

// --------------------------------------------------------------------------------------------- sync

@Serializable
data class ChangeSetDto(
    val books: List<BookDto> = emptyList(),
    val playback: List<PlaybackDto> = emptyList(),
    val preferences: PreferencesDto? = null,
)

@Serializable
data class PullResponse(val changes: ChangeSetDto, val cursor: String, val hasMore: Boolean)

@Serializable
data class PushRequest(val changes: ChangeSetDto)

@Serializable
data class PushResponse(
    /** Book ids (and "playback:<bookId>", "preferences") the server took, with the revision it now holds. */
    val accepted: Map<String, Long>,
    /** Book ids whose metadata changed on the server since the client's base revision; pull and merge them. */
    val conflicts: List<String>,
)

// ---------------------------------------------------------------------------------- uploads, imports

@Serializable
enum class UploadKind {
    /** A finished BookSound M4B (with its id); it goes straight into the library. */
    BOOK,

    /** One audio or image file of a book to import; several are joined with [ImportFromUploadsRequest]. */
    SOURCE,
}

@Serializable
data class UploadCreateRequest(
    val fileName: String,
    val sizeBytes: Long,
    val kind: UploadKind,
    val sha256: String? = null,
    /** Folders between the selected import root and the file (for [UploadKind.SOURCE]). */
    val relativeDir: List<String> = emptyList(),
)

@Serializable
data class UploadDto(val id: String, val fileName: String, val sizeBytes: Long, val receivedBytes: Long, val kind: UploadKind)

/** Result of completing a [UploadKind.BOOK] upload. */
@Serializable
data class UploadResultDto(val book: BookDto? = null, val job: ImportJobDto? = null)

@Serializable
data class ImportFromUploadsRequest(
    val uploadIds: List<String>,
    val sourceName: String,
    /** Fields the user set; the rest comes from the files' tags. */
    val metadata: MetadataDto? = null,
)

@Serializable
enum class ImportStage { QUEUED, PREPARING, CONVERTING, WRITING, VERIFYING, FINISHING, DONE, FAILED, CANCELLED }

@Serializable
data class ImportJobDto(
    val id: String,
    val title: String,
    val stage: ImportStage,
    val progress: Float? = null,
    val error: String? = null,
    val bookId: String? = null,
    val torrentId: String? = null,
    val createdAt: Long,
    val finishedAt: Long? = null,
)

// ------------------------------------------------------------------------------------------ torrents

@Serializable
enum class TorrentPhase { FETCHING_METADATA, AWAITING_REVIEW, DOWNLOADING, DOWNLOADED, VERIFYING, CONVERTING, COMPLETED, FAILED, PAUSED }

@Serializable
data class AddTorrentRequest(
    /** Magnet link, link to a .torrent file, or a bare info-hash. A .torrent file is posted as multipart instead. */
    val link: String,
    /** Convert as soon as the download ends, with the suggested details. */
    val autoImport: Boolean = false,
)

@Serializable
data class TorrentFileDto(val index: Int, val path: String, val sizeBytes: Long, val wanted: Boolean)

@Serializable
data class TorrentBookDto(
    val id: String,
    /** Folder of the book inside a collection; null for a single-book torrent. */
    val volume: String?,
    val metadata: MetadataDto,
    val files: List<TorrentFileDto>,
    val phase: TorrentPhase,
    val reviewed: Boolean,
    val autoImport: Boolean,
    val progress: Float,
    val bookId: String,
    val importJobId: String? = null,
    val error: String? = null,
    val hasCustomCover: Boolean = false,
)

@Serializable
data class TorrentDto(
    val id: String,
    val name: String,
    val infoHash: String?,
    val phase: TorrentPhase,
    val progress: Float,
    val downloadRate: Long,
    val peers: Int,
    val sizeBytes: Long,
    val downloadedBytes: Long,
    val error: String? = null,
    val books: List<TorrentBookDto>,
    val addedAt: Long,
)

@Serializable
data class TorrentReviewRequest(
    val metadata: MetadataDto,
    /** File indices the book is made of, in order; files left out are not downloaded. */
    val files: List<Int>,
    val autoImport: Boolean = true,
)

// ------------------------------------------------------------------------------- watch, admin, events

@Serializable
enum class WatchProfileMode {
    /** Built the first time a watch asks for the book. */
    ON_DEMAND,

    /** Built for every book after it is added. */
    ALL_BOOKS,

    /** Built for books someone has started and not finished. */
    IN_PROGRESS,
}

@Serializable
data class WatchProfileDto(
    val id: String,
    val name: String,
    val bitrateKbps: Int,
    val mono: Boolean,
    /** Output sample rate in Hz, or null to keep the source's. */
    val sampleRate: Int? = null,
    /** Longest edge of the embedded cover in pixels; 0 leaves the cover out. */
    val coverSize: Int,
    val mode: WatchProfileMode,
)

@Serializable
data class WatchFileStatusDto(val ready: Boolean, val sizeBytes: Long? = null, val job: ImportJobDto? = null)

@Serializable
data class ServerSettingsDto(
    val encoderBitrateKbps: Int,
    val downmixMono: Boolean,
    val parallelImports: Int,
    /** Upload speed limit while downloading torrents, KiB/s; 0 is no limit. Nothing is seeded after a download. */
    val torrentUploadLimitKibps: Int,
    val torrentListenPort: Int,
    /** Size limit of the folder of watch versions, MiB. */
    val watchCacheLimitMib: Int,
)

@Serializable
data class UserCreateRequest(val name: String, val password: String, val isAdmin: Boolean = false)

@Serializable
data class UserUpdateRequest(val password: String? = null, val isAdmin: Boolean? = null)

@Serializable
data class PasswordChangeRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class RescanResultDto(val added: Int, val relinked: Int, val missing: Int)

/** One server-sent event on [ApiPaths.EVENTS]; the SSE event name is [type]. */
@Serializable
data class EventDto(
    val type: String,
    val job: ImportJobDto? = null,
    val torrent: TorrentDto? = null,
    val bookId: String? = null,
)

object EventTypes {
    const val JOB = "job"
    const val TORRENT = "torrent"
    const val TORRENT_REMOVED = "torrent_removed"
    const val LIBRARY = "library"
}
