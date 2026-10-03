package com.zyagodin.booksound.torrent

import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.torrent.AudiobookLayout
import com.zyagodin.booksound.core.torrent.ReviewedPart
import com.zyagodin.booksound.core.torrent.TorrentFile
import com.zyagodin.booksound.core.torrent.TorrentReview
import com.zyagodin.booksound.importer.ImportJob
import kotlinx.serialization.Serializable

/**
 * Life cycle of a torrent import. Persisted, so every phase survives the app being closed, killed
 * or the device losing its connection; work resumes from the stored phase on next start.
 */
@Serializable
enum class TorrentPhase {
    /** Magnet link: waiting for peers to send the file list. */
    FETCHING_METADATA,
    DOWNLOADING,

    /** All files are here, but the user has not confirmed the details yet. */
    DOWNLOADED,

    /** Downloaded files are being read and checked. */
    VERIFYING,

    /** The import pipeline is converting the book; see [TorrentRecord.importJobId]. */
    CONVERTING,
    COMPLETED,
    FAILED,
}

@Serializable
enum class TorrentFailureCode {
    /** Magnet link: no peer sent the torrent's metadata. */
    METADATA_NOT_FOUND,

    /** Content does not look like a single audiobook (see [TorrentFailure.problem]). */
    CONTENT_INVALID,
    NOT_ENOUGH_SPACE,

    /** libtorrent reported a storage or torrent error. */
    DOWNLOAD_ERROR,

    /** Finished files disappeared or have the wrong size. */
    FILES_MISSING,

    /** Downloaded files are damaged or not what they claim to be. */
    AUDIOBOOK_INVALID,

    /** The import pipeline failed; [TorrentFailure.detail] is the user-facing reason. */
    CONVERSION_FAILED,
    CONVERSION_CANCELLED,
}

@Serializable
data class TorrentFailure(
    val code: TorrentFailureCode,
    val detail: String? = null,
    /** [com.zyagodin.booksound.core.torrent.TorrentContentProblem] name for [TorrentFailureCode.CONTENT_INVALID]. */
    val problem: String? = null,
    /** Files the failure is about. */
    val files: List<String> = emptyList(),
) {
    /** Failures the user can retry without changing anything (after freeing space, reconnecting…). */
    val retryable: Boolean get() = code != TorrentFailureCode.CONTENT_INVALID
}

@Serializable
data class StoredFile(val index: Int, val path: String, val size: Long, val padding: Boolean = false) {
    fun toTorrentFile() = TorrentFile(index, path, size, padding)
}

@Serializable
data class StoredMetadata(
    val title: String = "",
    val author: String? = null,
    val narrator: String? = null,
    val series: String? = null,
    val seriesIndex: String? = null,
    val year: String? = null,
    val description: String? = null,
) {
    fun toMetadata() = BookMetadata(title, author, narrator, series, seriesIndex, year, description = description)

    companion object {
        fun of(m: BookMetadata) = StoredMetadata(m.title, m.author, m.narrator, m.series, m.seriesIndex, m.year, m.description)
    }
}

/** One audio file of the book, in the order and with the title the user chose. */
@Serializable
data class StoredPart(val fileIndex: Int, val path: String, val title: String, val suggestedTitle: String, val size: Long)

@Serializable
data class TorrentRecord(
    val id: String,
    /** Torrent name (or the magnet's display name until metadata arrives). */
    val name: String,
    val infoHash: String,
    val magnetUri: String? = null,
    val addedAt: Long,
    val phase: TorrentPhase,
    /** Folder the files are downloaded into (app-specific storage). */
    val dataDir: String,
    /** The user paused the download. */
    val paused: Boolean = false,
    /** The user confirmed the book's details; conversion starts as soon as the download is done. */
    val reviewed: Boolean = false,
    val layout: AudiobookLayout? = null,
    val files: List<StoredFile> = emptyList(),
    /** Indices of the files to download: the kept audio parts and cover images. */
    val wanted: Set<Int> = emptySet(),
    val suggested: StoredMetadata? = null,
    val edited: StoredMetadata? = null,
    val parts: List<StoredPart> = emptyList(),
    /** A cover the user chose in the editor is stored next to the record. */
    val hasCustomCover: Boolean = false,
    /** Id the imported book gets; fixed so a conversion interrupted by a crash is redone, not duplicated. */
    val bookId: String,
    val importJobId: String? = null,
    val resultBookId: String? = null,
    val failure: TorrentFailure? = null,
    /** Last known download progress, shown before the engine reports after a restart. */
    val progress: Float = 0f,
    val finishedAt: Long? = null,
) {
    val title: String get() = edited?.title?.takeIf { it.isNotBlank() } ?: suggested?.title?.takeIf { it.isNotBlank() } ?: name
    val author: String? get() = edited?.author ?: suggested?.author
    val wantedBytes: Long get() = files.filter { it.index in wanted }.sumOf { it.size }
    val isActive: Boolean get() = phase != TorrentPhase.COMPLETED && phase != TorrentPhase.FAILED

    /** The download should be running in the torrent engine. */
    val needsEngine: Boolean get() = phase == TorrentPhase.DOWNLOADING && !paused

    fun review(): TorrentReview? {
        val s = suggested ?: return null
        val e = edited ?: s
        return TorrentReview(s.toMetadata(), e.toMetadata(), parts.map { ReviewedPart(it.path, it.title, it.suggestedTitle) })
    }
}

/** Download state that is not persisted: speed, peers, network. */
data class TorrentLive(
    val progress: Float,
    val downloadRate: Int,
    /** Connected peers. */
    val peers: Int,
    val seeds: Int,
    /** Peers known in the swarm, connected or not. */
    val swarm: Int,
    val checking: Boolean,
    val trackerError: String?,
)

/** Everything the UI shows about one torrent. */
data class TorrentItem(
    val record: TorrentRecord,
    val live: TorrentLive?,
    /** The conversion job while [TorrentPhase.CONVERTING]. */
    val job: ImportJob?,
    val coverPath: String?,
    val online: Boolean,
    /** An editor for this torrent is open; conversion waits for it to close. */
    val editing: Boolean,
) {
    val id: String get() = record.id
}
