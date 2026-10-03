package com.zyagodin.booksound.core.model

import java.util.UUID

/** Stable identifier of a book. Embedded into the audio file, independent of its path. */
@JvmInline
value class BookId(val value: String) {
    override fun toString(): String = value

    companion object {
        fun random(): BookId = BookId(UUID.randomUUID().toString())
    }
}

/** Stable identifier of a device participating in sync. */
@JvmInline
value class DeviceId(val value: String) {
    override fun toString(): String = value
}

/** User-editable descriptive metadata of a book. */
data class BookMetadata(
    val title: String,
    val author: String? = null,
    val narrator: String? = null,
    val series: String? = null,
    /** Position inside the series. Decimal string ("3", "3.5") so that novellas between books keep their order. */
    val seriesIndex: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val description: String? = null,
    val language: String? = null,
) {
    fun normalized(): BookMetadata = copy(
        title = title.cleanField().orEmpty(),
        author = author.cleanField(),
        narrator = narrator.cleanField(),
        series = series.cleanField(),
        seriesIndex = SeriesIndex.normalize(seriesIndex),
        year = year.cleanField(),
        genre = genre.cleanField(),
        description = description?.trim()?.takeIf { it.isNotEmpty() },
        language = language.cleanField(),
    )
}

data class Chapter(
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

/** A chapter marker as found in a file, before the end positions are known. */
data class ChapterMark(val startMs: Long, val title: String?)

data class EmbeddedPicture(val bytes: ByteArray, val mimeType: String) {
    override fun equals(other: Any?): Boolean =
        other is EmbeddedPicture && other.mimeType == mimeType && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + mimeType.hashCode()

    companion object {
        /** Detects the image type from magic bytes; returns null for unsupported data. */
        fun sniffMimeType(bytes: ByteArray): String? = when {
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
            bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> "image/png"
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "image/webp"
            bytes.size >= 2 && bytes[0] == 'B'.code.toByte() && bytes[1] == 'M'.code.toByte() -> "image/bmp"
            else -> null
        }
    }
}

object SeriesIndex {
    private val numberPattern = Regex("""^\d{1,4}([.,]\d{1,3})?$""")

    /** Returns a canonical decimal string ("03" -> "3", "2,5" -> "2.5") or null when not a number. */
    fun normalize(raw: String?): String? {
        val text = raw?.trim()?.removePrefix("#")?.trim() ?: return null
        if (!numberPattern.matches(text)) return null
        val value = text.replace(',', '.').toBigDecimal().stripTrailingZeros()
        return value.toPlainString()
    }

    fun isValid(raw: String?): Boolean = raw.isNullOrBlank() || normalize(raw) != null

    fun sortKey(index: String?): Double = index?.toDoubleOrNull() ?: Double.MAX_VALUE
}

internal fun String?.cleanField(): String? =
    this?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotEmpty() }
