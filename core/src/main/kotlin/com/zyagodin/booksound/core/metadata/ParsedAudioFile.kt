package com.zyagodin.booksound.core.metadata

import com.zyagodin.booksound.core.model.ChapterMark
import com.zyagodin.booksound.core.model.EmbeddedPicture

enum class AudioContainer { MP4, MP3, UNKNOWN }

/** Tag values as found in a file, before any interpretation. */
data class AudioTags(
    val title: String? = null,
    val album: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val composer: String? = null,
    val narrator: String? = null,
    val series: String? = null,
    val seriesPart: String? = null,
    val grouping: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val comment: String? = null,
    val description: String? = null,
    val language: String? = null,
    val trackNumber: Int? = null,
    val trackTotal: Int? = null,
    val discNumber: Int? = null,
    val discTotal: Int? = null,
    /** Free-form / user defined fields keyed by upper-case name (e.g. "SERIES", "BOOKSOUND:BOOK_ID"). */
    val custom: Map<String, String> = emptyMap(),
)

/** Description of the main audio stream, used to decide whether a file can be copied without transcoding. */
data class AudioStreamInfo(
    /** Short codec name: "aac", "mp3", "alac", "ac3", "opus", ... */
    val codec: String,
    val sampleRate: Int?,
    val channels: Int?,
    val bitrate: Int?,
    /** Codec-specific configuration (e.g. AAC AudioSpecificConfig) for compatibility checks. */
    val codecConfig: ByteArray? = null,
) {
    /** True when two streams can be concatenated without re-encoding. */
    fun isCompatibleWith(other: AudioStreamInfo): Boolean =
        codec == other.codec && sampleRate == other.sampleRate && channels == other.channels &&
            (codecConfig == null || other.codecConfig == null || codecConfig.contentEquals(other.codecConfig))

    override fun equals(other: Any?): Boolean =
        other is AudioStreamInfo && codec == other.codec && sampleRate == other.sampleRate && channels == other.channels &&
            bitrate == other.bitrate && codecConfig.contentEqualsNullable(other.codecConfig)

    override fun hashCode(): Int = listOf(codec, sampleRate, channels, bitrate).hashCode()
}

private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
    if (this == null) other == null else other != null && contentEquals(other)

data class ParsedAudioFile(
    val container: AudioContainer,
    val stream: AudioStreamInfo?,
    val durationMs: Long?,
    val tags: AudioTags,
    val chapters: List<ChapterMark>,
    val cover: EmbeddedPicture?,
    /** Non-fatal issues found while parsing, for diagnostics. */
    val warnings: List<String> = emptyList(),
    /** MP4 only: the file can be re-tagged in place (non-fragmented, single moov). */
    val rewritable: Boolean = false,
) {
    val bookId: String? get() = tags.custom[BOOKSOUND_BOOK_ID]

    companion object {
        const val BOOKSOUND_NAMESPACE = "com.zyagodin.booksound"
        const val BOOKSOUND_BOOK_ID = "BOOKSOUND:BOOK_ID"
    }
}

/** The file cannot be parsed because its structure is broken or truncated. */
class CorruptedFileException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The file is well-formed but uses a format/codec this app cannot handle. */
class UnsupportedFormatException(message: String) : Exception(message)
