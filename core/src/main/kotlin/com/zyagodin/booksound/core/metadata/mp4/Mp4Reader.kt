package com.zyagodin.booksound.core.metadata.mp4

import com.zyagodin.booksound.core.io.RandomAccessSource
import com.zyagodin.booksound.core.io.fourCC
import com.zyagodin.booksound.core.io.readFully
import com.zyagodin.booksound.core.io.readUpTo
import com.zyagodin.booksound.core.io.u32
import com.zyagodin.booksound.core.io.u64
import com.zyagodin.booksound.core.metadata.CorruptedFileException
import com.zyagodin.booksound.core.metadata.UnsupportedFormatException

/** Top level structure of an MP4 file with the `moov` box loaded into memory. */
class Mp4Document(
    val topLevel: List<TopLevelBox>,
    val moov: Mp4Node,
    val majorBrand: String?,
    val warnings: List<String>,
) {
    val isFragmented: Boolean get() = topLevel.any { it.type == "moof" } || moov.child("mvex") != null

    val tracks: List<Mp4Node> get() = moov.childrenOf("trak")

    fun audioTrack(): Mp4Node? = tracks.firstOrNull { Mp4Boxes.handlerType(it) == "soun" }
}

object Mp4Reader {
    /** Upper bound for an in-memory moov; ~40 hours of AAC sample tables need about 25 MB. */
    private const val MAX_MOOV_SIZE = 128L * 1024 * 1024

    private val KNOWN_TOP_LEVEL = setOf(
        "ftyp", "moov", "mdat", "free", "skip", "wide", "uuid", "pdin", "moof", "mfra", "meta", "sidx", "styp", "pnot", "junk",
    )

    fun looksLikeMp4(header: ByteArray): Boolean {
        if (header.size < 8) return false
        val type = header.fourCC(4)
        return type == "ftyp" || type == "moov" || type == "mdat" || type == "wide" || type == "free" || type == "skip"
    }

    fun scanTopLevel(source: RandomAccessSource, warnings: MutableList<String> = mutableListOf()): List<TopLevelBox> {
        val boxes = mutableListOf<TopLevelBox>()
        val fileSize = source.size
        var pos = 0L
        while (pos < fileSize) {
            val remaining = fileSize - pos
            val header = source.readUpTo(pos, 16)
            if (remaining < 8) {
                if (header.all { it.toInt() == 0 }) break
                warnings += "Ignored ${remaining} trailing bytes"
                break
            }
            var size = header.u32(0)
            val type = header.fourCC(4)
            var headerSize = 8
            if (size == 1L) {
                if (remaining < 16) throw CorruptedFileException("Truncated box header at $pos")
                size = header.u64(8)
                headerSize = 16
            } else if (size == 0L) {
                size = remaining
            }
            val plausibleType = type.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == ' ' || it == '-' }
            if (!plausibleType || size < headerSize) {
                if (boxes.any { it.type == "moov" } && boxes.any { it.type == "mdat" }) {
                    warnings += "Ignored unparseable data after offset $pos"
                    break
                }
                throw CorruptedFileException("Invalid box at offset $pos")
            }
            if (pos + size > fileSize) {
                throw CorruptedFileException("File is truncated: box '$type' ends beyond the end of the file")
            }
            if (type !in KNOWN_TOP_LEVEL) warnings += "Unknown top level box '$type'"
            boxes += TopLevelBox(type, pos, headerSize, size)
            pos += size
        }
        return boxes
    }

    fun load(source: RandomAccessSource): Mp4Document {
        val warnings = mutableListOf<String>()
        val boxes = scanTopLevel(source, warnings)
        val moovBoxes = boxes.filter { it.type == "moov" }
        if (moovBoxes.isEmpty()) {
            throw if (boxes.any { it.type == "mdat" }) CorruptedFileException("MP4 file has no 'moov' index (incomplete recording or download)")
            else UnsupportedFormatException("Not an MP4 file")
        }
        if (moovBoxes.size > 1) warnings += "Multiple 'moov' boxes; using the first one"
        val moovBox = moovBoxes.first()
        if (moovBox.size > MAX_MOOV_SIZE) throw UnsupportedFormatException("MP4 index is too large (${moovBox.size} bytes)")
        if (boxes.none { it.type == "mdat" } && boxes.none { it.type == "moof" }) {
            throw CorruptedFileException("MP4 file contains no audio data")
        }
        val moovBytes = source.readFully(moovBox.offset, moovBox.size.toInt())
        val moov = try {
            if (moovBox.headerSize == 16) {
                // Normalise a 64-bit header to a 32-bit one so Mp4Node.parse handles it uniformly.
                val body = moovBytes.copyOfRange(16, moovBytes.size)
                Mp4Node.container("moov", Mp4Node.parseChildren(body, 0, body.size, "moov"))
            } else {
                Mp4Node.parse(moovBytes)
            }
        } catch (e: CorruptedFileException) {
            throw e
        } catch (e: RuntimeException) {
            throw CorruptedFileException("Damaged MP4 index", e)
        }
        val brand = boxes.firstOrNull { it.type == "ftyp" }?.let {
            if (it.payloadSize >= 4) source.readFully(it.payloadOffset, 4).fourCC(0) else null
        }
        return Mp4Document(boxes, moov, brand, warnings)
    }
}
