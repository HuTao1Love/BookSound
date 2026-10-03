package com.zyagodin.booksound.core.metadata.id3

import com.zyagodin.booksound.core.io.RandomAccessSource
import com.zyagodin.booksound.core.io.readFully
import com.zyagodin.booksound.core.io.readUpTo
import com.zyagodin.booksound.core.io.synchsafe32
import com.zyagodin.booksound.core.io.u24
import com.zyagodin.booksound.core.io.u32
import com.zyagodin.booksound.core.io.u8
import com.zyagodin.booksound.core.metadata.AudioTags
import com.zyagodin.booksound.core.metadata.TextDecoding
import com.zyagodin.booksound.core.model.EmbeddedPicture
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/** Result of reading ID3 tags from the start (v2) and end (v1) of a file. */
data class Id3Result(
    val tags: AudioTags,
    val cover: EmbeddedPicture?,
    /** Offset of the first byte after all leading ID3v2 tags (start of audio). */
    val audioStart: Long,
    /** Number of trailing bytes occupied by ID3v1/APE tags. */
    val trailingTagBytes: Long,
    val warnings: List<String>,
)

object Id3Reader {

    private const val MAX_TAG_SIZE = 64 * 1024 * 1024

    private val V22_TO_V23 = mapOf(
        "TT1" to "TIT1", "TT2" to "TIT2", "TT3" to "TIT3", "TP1" to "TPE1", "TP2" to "TPE2", "TP3" to "TPE3",
        "TAL" to "TALB", "TCM" to "TCOM", "TRK" to "TRCK", "TPA" to "TPOS", "TYE" to "TYER", "TCO" to "TCON",
        "COM" to "COMM", "PIC" to "PIC2", "TXX" to "TXXX", "TLA" to "TLAN",
    )

    fun read(source: RandomAccessSource): Id3Result {
        val frames = mutableListOf<Frame>()
        val warnings = mutableListOf<String>()
        var pos = 0L
        // Some files carry several consecutive ID3v2 tags; read them all.
        while (pos + 10 <= source.size) {
            val header = source.readFully(pos, 10)
            if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) break
            val major = header.u8(3)
            val flags = header.u8(5)
            val size = header.synchsafe32(6)
            val footer = if (major == 4 && flags and 0x10 != 0) 10 else 0
            val total = 10L + size + footer
            if (major !in 2..4 || size > MAX_TAG_SIZE || pos + total > source.size) {
                warnings += "Damaged ID3v2 tag at $pos"
                break
            }
            try {
                frames += parseTag(source.readFully(pos + 10, size), major, flags)
            } catch (e: RuntimeException) {
                warnings += "Unreadable ID3v2 tag: ${e.message}"
            }
            pos += total
            // Skip zero padding written by some taggers after the declared size.
            var scanned = 0
            while (pos < source.size && scanned < 256 * 1024) {
                val chunk = source.readUpTo(pos, 4096)
                val firstNonZero = chunk.indexOfFirst { it.toInt() != 0 }
                if (firstNonZero >= 0) {
                    pos += firstNonZero
                    break
                }
                pos += chunk.size
                scanned += chunk.size
            }
        }
        val audioStart = pos
        var trailing = 0L
        var v1: AudioTags? = null
        if (source.size - audioStart >= 128) {
            val tail = source.readFully(source.size - 128, 128)
            if (tail[0] == 'T'.code.toByte() && tail[1] == 'A'.code.toByte() && tail[2] == 'G'.code.toByte()) {
                trailing = 128
                v1 = parseV1(tail)
            }
        }
        if (source.size - audioStart - trailing >= 32) {
            // APEv2 footer just before ID3v1 (or at the very end).
            val apeFooterPos = source.size - trailing - 32
            val ape = source.readFully(apeFooterPos, 32)
            if (String(ape, 0, 8, Charsets.ISO_8859_1) == "APETAGEX") {
                val apeSize = (ape.u8(12) or (ape.u8(13) shl 8) or (ape.u8(14) shl 16) or (ape.u8(15) shl 24)).toLong()
                val hasHeader = ape.u8(23) and 0x80 != 0
                trailing += apeSize + if (hasHeader) 32 else 0
            }
        }
        val tags = buildTags(frames, v1)
        return Id3Result(tags, pickCover(frames), audioStart, trailing.coerceAtMost(source.size - audioStart), warnings)
    }

    private class Frame(val id: String, val data: ByteArray)

    private fun parseTag(raw: ByteArray, major: Int, flags: Int): List<Frame> {
        var data = raw
        if (major < 4 && flags and 0x80 != 0) data = removeUnsynchronisation(data)
        var pos = 0
        if (flags and 0x40 != 0 && major >= 3) {
            pos = if (major == 4) data.synchsafe32(0) else 4 + data.u32(0).toInt()
        }
        val frames = mutableListOf<Frame>()
        val idLength = if (major == 2) 3 else 4
        val headerLength = if (major == 2) 6 else 10
        while (pos + headerLength <= data.size) {
            if (data[pos].toInt() == 0) break // padding
            val id = String(data, pos, idLength, Charsets.ISO_8859_1)
            if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
            var size = when (major) {
                2 -> data.u24(pos + 3)
                3 -> data.u32(pos + 4).toInt()
                else -> data.synchsafe32(pos + 4)
            }
            if (major == 4 && !isPlausibleNextFrame(data, pos + headerLength + size)) {
                // Some v2.4 writers store plain integers instead of synchsafe ones.
                val plain = data.u32(pos + 4).toInt()
                if (isPlausibleNextFrame(data, pos + headerLength + plain)) size = plain
            }
            if (size < 0 || pos + headerLength + size > data.size) break
            val formatFlags = if (major >= 3) data.u8(pos + 9) else 0
            var body = data.copyOfRange(pos + headerLength, pos + headerLength + size)
            pos += headerLength + size
            val normalizedId = if (major == 2) V22_TO_V23[id] ?: continue else id
            try {
                body = decodeFrameBody(body, major, formatFlags) ?: continue
            } catch (_: RuntimeException) {
                continue
            }
            frames += Frame(normalizedId, body)
        }
        return frames
    }

    private fun isPlausibleNextFrame(data: ByteArray, at: Int): Boolean {
        if (at == data.size) return true
        if (at > data.size || at < 0) return false
        if (data[at].toInt() == 0) return true
        if (at + 4 > data.size) return false
        return (0 until 4).all { val c = data[at + it].toInt().toChar(); c in 'A'..'Z' || c in '0'..'9' }
    }

    private fun decodeFrameBody(body: ByteArray, major: Int, formatFlags: Int): ByteArray? {
        var data = body
        if (major == 3) {
            if (formatFlags and 0x40 != 0) return null // encrypted
            if (formatFlags and 0x80 != 0) data = inflate(data.copyOfRange(4, data.size)) // decompressed size prefix
        } else if (major == 4) {
            if (formatFlags and 0x04 != 0) return null // encrypted
            var offset = 0
            if (formatFlags and 0x01 != 0) offset = 4 // data length indicator
            if (formatFlags and 0x40 != 0) offset += 1 // grouping identity
            data = data.copyOfRange(offset.coerceAtMost(data.size), data.size)
            if (formatFlags and 0x02 != 0) data = removeUnsynchronisation(data)
            if (formatFlags and 0x08 != 0) data = inflate(data)
        }
        return data
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = ByteArrayOutputStream(data.size * 2)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_TAG_SIZE) break
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun removeUnsynchronisation(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            out.write(data[i].toInt())
            if (data.u8(i) == 0xFF && i + 1 < data.size && data[i + 1].toInt() == 0) i++
            i++
        }
        return out.toByteArray()
    }

    // ------------------------------------------------------------- text helpers

    /** Decodes text in the given ID3 encoding. */
    private fun decode(encoding: Int, b: ByteArray, offset: Int, length: Int): String {
        if (length <= 0) return ""
        return when (encoding) {
            1 -> decodeUtf16WithBom(b, offset, length)
            2 -> String(b, offset, length, Charsets.UTF_16BE)
            3 -> TextDecoding.decodeUtf8Lenient(b, offset, length)
            else -> TextDecoding.decodeLegacy(b, offset, length)
        }
    }

    private fun decodeUtf16WithBom(b: ByteArray, offset: Int, length: Int): String {
        if (length >= 2) {
            if (b.u8(offset) == 0xFF && b.u8(offset + 1) == 0xFE) return String(b, offset + 2, length - 2, Charsets.UTF_16LE)
            if (b.u8(offset) == 0xFE && b.u8(offset + 1) == 0xFF) return String(b, offset + 2, length - 2, Charsets.UTF_16BE)
        }
        return String(b, offset, length, Charsets.UTF_16LE)
    }

    /** Finds the terminator of a string starting at [from]; returns the index of the terminator. */
    private fun terminator(encoding: Int, b: ByteArray, from: Int): Int {
        if (encoding == 1 || encoding == 2) {
            var i = from
            while (i + 1 < b.size) {
                if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) return i
                i += 2
            }
            return b.size
        }
        var i = from
        while (i < b.size && b[i].toInt() != 0) i++
        return i
    }

    private fun terminatorLength(encoding: Int) = if (encoding == 1 || encoding == 2) 2 else 1

    private fun textValue(frame: Frame): String? {
        val b = frame.data
        if (b.isEmpty()) return null
        val encoding = b.u8(0)
        // Multiple values are NUL separated (v2.4); join them for display.
        val text = decode(encoding, b, 1, b.size - 1)
        return TextDecoding.clean(text.split('\u0000').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", "))
    }

    private fun userText(frame: Frame): Pair<String, String>? {
        val b = frame.data
        if (b.isEmpty()) return null
        val encoding = b.u8(0)
        val end = terminator(encoding, b, 1)
        val description = decode(encoding, b, 1, end - 1)
        val valueStart = (end + terminatorLength(encoding)).coerceAtMost(b.size)
        val value = decode(encoding, b, valueStart, b.size - valueStart)
        val key = TextDecoding.clean(description)?.uppercase() ?: return null
        return TextDecoding.clean(value)?.let { key to it }
    }

    private fun comment(frame: Frame): Pair<String?, String>? {
        val b = frame.data
        if (b.size < 4) return null
        val encoding = b.u8(0)
        val end = terminator(encoding, b, 4)
        val description = TextDecoding.clean(decode(encoding, b, 4, end - 4))
        val valueStart = (end + terminatorLength(encoding)).coerceAtMost(b.size)
        val value = TextDecoding.clean(decode(encoding, b, valueStart, b.size - valueStart)) ?: return null
        return description to value
    }

    private fun buildTags(frames: List<Frame>, v1: AudioTags?): AudioTags {
        fun text(id: String) = frames.firstOrNull { it.id == id }?.let { textValue(it) }
        val custom = LinkedHashMap<String, String>()
        frames.filter { it.id == "TXXX" }.mapNotNull { userText(it) }.forEach { (k, v) -> custom.putIfAbsent(k, v) }
        val comments = frames.filter { it.id == "COMM" }.mapNotNull { comment(it) }
        val (track, trackTotal) = parsePair(text("TRCK"))
        val (disc, discTotal) = parsePair(text("TPOS"))
        val year = (text("TDRC") ?: text("TYER") ?: text("TDRL"))?.let { Regex("""\d{4}""").find(it)?.value }
        val tags = AudioTags(
            title = text("TIT2"),
            album = text("TALB"),
            artist = text("TPE1"),
            albumArtist = text("TPE2"),
            composer = text("TCOM"),
            narrator = custom["NARRATOR"] ?: custom["NARRATEDBY"] ?: custom["READER"] ?: custom["PERFORMER"],
            series = text("MVNM") ?: custom["SERIES"] ?: custom["MVNM"],
            seriesPart = text("MVIN") ?: custom["SERIES-PART"] ?: custom["SERIES_PART"] ?: custom["SERIESPART"],
            grouping = text("TIT1") ?: text("GRP1"),
            genre = text("TCON")?.let(::cleanGenre),
            year = year,
            comment = comments.firstOrNull { it.first.isNullOrEmpty() }?.second ?: comments.firstOrNull()?.second,
            description = custom["DESCRIPTION"] ?: comments.firstOrNull { it.first?.uppercase() == "DESCRIPTION" }?.second,
            language = text("TLAN"),
            trackNumber = track,
            trackTotal = trackTotal,
            discNumber = disc,
            discTotal = discTotal,
            custom = custom,
        )
        return if (v1 == null) tags else tags.mergeMissing(v1)
    }

    private fun AudioTags.mergeMissing(other: AudioTags) = copy(
        title = title ?: other.title,
        album = album ?: other.album,
        artist = artist ?: other.artist,
        year = year ?: other.year,
        comment = comment ?: other.comment,
        trackNumber = trackNumber ?: other.trackNumber,
        genre = genre ?: other.genre,
    )

    /** "(101)Speech" / "101" -> "Speech"; leaves other values unchanged. */
    private fun cleanGenre(raw: String): String? {
        val numeric = Regex("""^\((\d+)\)(.*)$""").find(raw)
        if (numeric != null) return numeric.groupValues[2].trim().ifEmpty { Id3Genres.name(numeric.groupValues[1].toInt()) }
        return raw.toIntOrNull()?.let { Id3Genres.name(it) } ?: raw
    }

    private fun parsePair(value: String?): Pair<Int?, Int?> {
        if (value == null) return null to null
        val parts = value.split('/')
        return parts[0].trim().toIntOrNull() to parts.getOrNull(1)?.trim()?.toIntOrNull()
    }

    private fun pickCover(frames: List<Frame>): EmbeddedPicture? {
        val pictures = frames.filter { it.id == "APIC" || it.id == "PIC2" }.mapNotNull { parsePicture(it) }
        return (pictures.firstOrNull { it.first == 3 } ?: pictures.firstOrNull())?.second
    }

    private fun parsePicture(frame: Frame): Pair<Int, EmbeddedPicture>? {
        val b = frame.data
        if (b.size < 4) return null
        val encoding = b.u8(0)
        // APIC: NUL terminated Latin-1 mime type; v2.2 PIC: fixed 3-char image format.
        var pos = if (frame.id == "PIC2") 4 else terminator(0, b, 1) + 1
        if (pos >= b.size) return null
        val pictureType = b.u8(pos)
        pos++
        val descriptionEnd = terminator(encoding, b, pos)
        pos = descriptionEnd + terminatorLength(encoding)
        if (pos >= b.size) return null
        var image = b.copyOfRange(pos, b.size)
        var mime = EmbeddedPicture.sniffMimeType(image)
        if (mime == null) {
            // Some writers add an extra NUL after the description; try skipping it.
            val skipped = image.dropWhile { it.toInt() == 0 }.toByteArray()
            mime = EmbeddedPicture.sniffMimeType(skipped) ?: return null
            image = skipped
        }
        return pictureType to EmbeddedPicture(image, mime)
    }

    private fun parseV1(tail: ByteArray): AudioTags {
        fun field(from: Int, len: Int): String? {
            var end = from
            while (end < from + len && tail[end].toInt() != 0) end++
            return TextDecoding.clean(TextDecoding.decodeLegacy(tail, from, end - from))
        }
        val track = if (tail[125].toInt() == 0 && tail[126].toInt() != 0) tail.u8(126) else null
        return AudioTags(
            title = field(3, 30),
            artist = field(33, 30),
            album = field(63, 30),
            year = field(93, 4),
            comment = field(97, if (track != null) 28 else 30),
            trackNumber = track,
            genre = Id3Genres.name(tail.u8(127)),
        )
    }
}

internal object Id3Genres {
    // Only the genres relevant for spoken word; other ids are left unnamed.
    fun name(id: Int): String? = when (id) {
        28 -> "Vocal"
        57 -> "Comedy"
        65 -> "Cabaret"
        101 -> "Speech"
        183 -> "Audiobook"
        186 -> "Audio Theatre"
        else -> null
    }
}
