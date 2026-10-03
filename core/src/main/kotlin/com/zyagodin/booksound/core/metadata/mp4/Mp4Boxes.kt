package com.zyagodin.booksound.core.metadata.mp4

import com.zyagodin.booksound.core.io.fourCC
import com.zyagodin.booksound.core.io.u16
import com.zyagodin.booksound.core.io.u32
import com.zyagodin.booksound.core.io.u64
import com.zyagodin.booksound.core.io.u8
import com.zyagodin.booksound.core.metadata.AudioStreamInfo
import com.zyagodin.booksound.core.metadata.CorruptedFileException

/** Readers for individual well-known boxes. */
object Mp4Boxes {

    data class MovieHeader(val timescale: Long, val duration: Long, val nextTrackIdOffset: Int)

    fun movieHeader(moov: Mp4Node): MovieHeader? {
        val p = moov.child("mvhd")?.payload ?: return null
        return guard("mvhd") {
            val version = p.u8(0)
            if (version == 1) MovieHeader(p.u32(20), p.u64(24), 108)
            else MovieHeader(p.u32(12), p.u32(16), 96)
        }
    }

    fun trackId(trak: Mp4Node): Long? {
        val p = trak.child("tkhd")?.payload ?: return null
        return guard("tkhd") { if (p.u8(0) == 1) p.u32(20) else p.u32(12) }
    }

    data class MediaHeader(val timescale: Long, val duration: Long)

    fun mediaHeader(trak: Mp4Node): MediaHeader? {
        val p = trak.find("mdia/mdhd")?.payload ?: return null
        return guard("mdhd") {
            if (p.u8(0) == 1) MediaHeader(p.u32(20), p.u64(24)) else MediaHeader(p.u32(12), p.u32(16))
        }
    }

    fun handlerType(trak: Mp4Node): String? {
        val p = trak.find("mdia/hdlr")?.payload ?: return null
        return if (p.size >= 12) p.fourCC(8) else null
    }

    /** Track IDs referenced as chapter tracks by [trak]'s `tref/chap`. */
    fun chapterTrackRefs(trak: Mp4Node): List<Long> {
        val p = trak.find("tref/chap")?.payload ?: return emptyList()
        return (0 until p.size / 4).map { p.u32(it * 4) }
    }

    fun audioStreamInfo(trak: Mp4Node): AudioStreamInfo? {
        val stsd = trak.find("mdia/minf/stbl/stsd")?.payload ?: return null
        if (stsd.size < 16) return null
        val entrySize = stsd.u32(8).toInt()
        val entryType = stsd.fourCC(12)
        val entryStart = 16
        val entryEnd = minOf(stsd.size, 8 + entrySize)
        if (entryEnd - entryStart < 28) return AudioStreamInfo(codecName(entryType, null), null, null, null)
        val version = stsd.u16(entryStart + 8)
        val channels = stsd.u16(entryStart + 16)
        val sampleRate = stsd.u16(entryStart + 24)
        val childrenStart = entryStart + when (version) {
            1 -> 28 + 16
            2 -> 28 + 36
            else -> 28
        }
        var esds: Esds? = null
        var pos = childrenStart
        while (pos + 8 <= entryEnd) {
            val size = stsd.u32(pos).toInt()
            if (size < 8 || pos + size > entryEnd) break
            val type = stsd.fourCC(pos + 4)
            if (type == "esds") esds = parseEsds(stsd, pos + 12, pos + size)
            if (type == "wave") {
                // QuickTime: esds nested inside 'wave'.
                var inner = pos + 8
                while (inner + 8 <= pos + size) {
                    val innerSize = stsd.u32(inner).toInt()
                    if (innerSize < 8) break
                    if (stsd.fourCC(inner + 4) == "esds") esds = parseEsds(stsd, inner + 12, inner + innerSize)
                    inner += innerSize
                }
            }
            pos += size
        }
        return AudioStreamInfo(
            codec = codecName(entryType, esds?.objectType),
            sampleRate = sampleRate.takeIf { it > 0 },
            channels = channels.takeIf { it > 0 },
            bitrate = esds?.avgBitrate?.takeIf { it > 0 },
            codecConfig = esds?.decoderSpecificInfo,
        )
    }

    private fun codecName(entryType: String, objectType: Int?): String = when (entryType) {
        "mp4a" -> when (objectType) {
            null, 0x40, 0x66, 0x67, 0x68 -> "aac"
            0x69, 0x6B -> "mp3"
            0xA5 -> "ac3"
            0xA6 -> "eac3"
            else -> "mp4a-${objectType.toString(16)}"
        }
        "alac" -> "alac"
        "ac-3" -> "ac3"
        "ec-3" -> "eac3"
        "Opus" -> "opus"
        "fLaC" -> "flac"
        ".mp3" -> "mp3"
        else -> entryType.trim().lowercase()
    }

    private class Esds(val objectType: Int, val avgBitrate: Int, val decoderSpecificInfo: ByteArray?)

    private fun parseEsds(b: ByteArray, start: Int, end: Int): Esds? {
        var pos = start
        fun readDescriptor(): Pair<Int, Int>? { // tag, length; pos moves to body
            if (pos >= end) return null
            val tag = b.u8(pos++)
            var len = 0
            for (i in 0 until 4) {
                if (pos >= end) return null
                val v = b.u8(pos++)
                len = (len shl 7) or (v and 0x7F)
                if (v and 0x80 == 0) break
            }
            return tag to len
        }
        val (esTag, _) = readDescriptor() ?: return null
        if (esTag != 0x03) return null
        pos += 2 // ES_ID
        if (pos >= end) return null
        val flags = b.u8(pos++)
        if (flags and 0x80 != 0) pos += 2
        if (flags and 0x40 != 0) { if (pos >= end) return null; pos += 1 + b.u8(pos) }
        if (flags and 0x20 != 0) pos += 2
        val (dcTag, _) = readDescriptor() ?: return null
        if (dcTag != 0x04 || pos + 13 > end) return null
        val objectType = b.u8(pos)
        val avgBitrate = b.u32(pos + 9).toInt()
        pos += 13
        var dsi: ByteArray? = null
        val dsiDescriptor = readDescriptor()
        if (dsiDescriptor != null && dsiDescriptor.first == 0x05 && pos + dsiDescriptor.second <= end) {
            dsi = b.copyOfRange(pos, pos + dsiDescriptor.second)
        }
        return Esds(objectType, avgBitrate, dsi)
    }

    private inline fun <T> guard(box: String, block: () -> T): T = try {
        block()
    } catch (e: IndexOutOfBoundsException) {
        throw CorruptedFileException("Box '$box' is truncated", e)
    }
}

/** Decoded sample table of a track (stts/stsc/stsz/stco). */
class SampleTable private constructor(
    val sampleSizes: IntArray,
    val sampleOffsets: LongArray,
    val sampleStartTimes: LongArray,
    val sampleDurations: LongArray,
) {
    val count: Int get() = sampleSizes.size

    companion object {
        /** Limits reading of chapter-like tracks to sane sizes. */
        private const val MAX_SAMPLES = 100_000

        fun read(stbl: Mp4Node): SampleTable {
            try {
                val stsz = stbl.child("stsz")?.payload ?: throw CorruptedFileException("Missing stsz")
                val constantSize = stsz.u32(4).toInt()
                val sampleCount = stsz.u32(8).toInt()
                if (sampleCount < 0 || sampleCount > MAX_SAMPLES) throw CorruptedFileException("Unexpected sample count $sampleCount")
                val sizes = IntArray(sampleCount) { if (constantSize != 0) constantSize else stsz.u32(12 + it * 4).toInt() }

                val chunkOffsets = readChunkOffsets(stbl)
                val stsc = stbl.child("stsc")?.payload ?: throw CorruptedFileException("Missing stsc")
                val stscCount = stsc.u32(4).toInt()
                val offsets = LongArray(sampleCount)
                var sample = 0
                for (entry in 0 until stscCount) {
                    val firstChunk = stsc.u32(8 + entry * 12).toInt()
                    val samplesPerChunk = stsc.u32(12 + entry * 12).toInt()
                    val nextFirstChunk = if (entry + 1 < stscCount) stsc.u32(8 + (entry + 1) * 12).toInt() else chunkOffsets.size + 1
                    for (chunk in firstChunk until nextFirstChunk) {
                        if (chunk - 1 !in chunkOffsets.indices) break
                        var offset = chunkOffsets[chunk - 1]
                        repeat(samplesPerChunk) {
                            if (sample < sampleCount) {
                                offsets[sample] = offset
                                offset += sizes[sample]
                                sample++
                            }
                        }
                    }
                }

                val stts = stbl.child("stts")?.payload ?: throw CorruptedFileException("Missing stts")
                val sttsCount = stts.u32(4).toInt()
                val starts = LongArray(sampleCount)
                val durations = LongArray(sampleCount)
                var time = 0L
                var index = 0
                for (entry in 0 until sttsCount) {
                    val count = stts.u32(8 + entry * 8)
                    val delta = stts.u32(12 + entry * 8)
                    var n = 0L
                    while (n < count && index < sampleCount) {
                        starts[index] = time
                        durations[index] = delta
                        time += delta
                        index++
                        n++
                    }
                }
                return SampleTable(sizes, offsets, starts, durations)
            } catch (e: IndexOutOfBoundsException) {
                throw CorruptedFileException("Damaged sample table", e)
            }
        }

        fun readChunkOffsets(stbl: Mp4Node): LongArray {
            stbl.child("stco")?.payload?.let { p ->
                val n = p.u32(4).toInt()
                return LongArray(n) { p.u32(8 + it * 4) }
            }
            stbl.child("co64")?.payload?.let { p ->
                val n = p.u32(4).toInt()
                return LongArray(n) { p.u64(8 + it * 8) }
            }
            throw CorruptedFileException("Missing chunk offsets")
        }
    }
}
