package com.zyagodin.booksound.core.metadata.mp3

import com.zyagodin.booksound.core.io.RandomAccessSource
import com.zyagodin.booksound.core.io.readUpTo
import com.zyagodin.booksound.core.io.u32
import com.zyagodin.booksound.core.io.u8
import com.zyagodin.booksound.core.metadata.CorruptedFileException

/** MPEG audio stream properties and duration. */
data class Mp3Info(
    val sampleRate: Int,
    val channels: Int,
    val bitrate: Int,
    val durationMs: Long,
    val isVbr: Boolean,
)

internal data class FrameHeader(
    val version: Int, // 1 = MPEG1, 2 = MPEG2, 25 = MPEG2.5
    val layer: Int,
    val bitrate: Int, // bits per second
    val sampleRate: Int,
    val padding: Int,
    val channelMode: Int,
) {
    val channels: Int get() = if (channelMode == 3) 1 else 2

    val samplesPerFrame: Int
        get() = when (layer) {
            1 -> 384
            2 -> 1152
            else -> if (version == 1) 1152 else 576
        }

    val frameLength: Int
        get() = when (layer) {
            1 -> (12 * bitrate / sampleRate + padding) * 4
            3 -> (if (version == 1) 144 else 72) * bitrate / sampleRate + padding
            else -> 144 * bitrate / sampleRate + padding
        }

    /** Offset of a Xing/Info header from the frame start. */
    val xingOffset: Int
        get() = 4 + if (version == 1) (if (channels == 1) 17 else 32) else (if (channels == 1) 9 else 17)

    companion object {
        private val BITRATES_V1 = arrayOf(
            intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448),
            intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384),
            intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320),
        )
        private val BITRATES_V2 = arrayOf(
            intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256),
            intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
            intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160),
        )
        private val SAMPLE_RATES = mapOf(
            1 to intArrayOf(44100, 48000, 32000),
            2 to intArrayOf(22050, 24000, 16000),
            25 to intArrayOf(11025, 12000, 8000),
        )

        fun parse(h: Int): FrameHeader? {
            if (h ushr 21 and 0x7FF != 0x7FF) return null
            val version = when (h ushr 19 and 3) {
                0 -> 25
                2 -> 2
                3 -> 1
                else -> return null
            }
            val layer = when (h ushr 17 and 3) {
                1 -> 3
                2 -> 2
                3 -> 1
                else -> return null
            }
            val bitrateIndex = h ushr 12 and 0xF
            val sampleRateIndex = h ushr 10 and 3
            if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) return null
            val table = if (version == 1) BITRATES_V1 else BITRATES_V2
            val bitrate = table[layer - 1][bitrateIndex] * 1000
            val sampleRate = SAMPLE_RATES.getValue(version)[sampleRateIndex]
            return FrameHeader(version, layer, bitrate, sampleRate, h ushr 9 and 1, h ushr 6 and 3)
        }
    }
}

object Mp3Reader {

    private const val SYNC_SEARCH_LIMIT = 256 * 1024

    fun looksLikeMp3(header: ByteArray): Boolean {
        if (header.size >= 3 && header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) return true
        return header.size >= 4 && FrameHeader.parse(header.u32(0).toInt()) != null
    }

    /**
     * Reads stream properties. [audioStart] / [audioEnd] exclude ID3/APE tags.
     * Throws [CorruptedFileException] when no valid MPEG frames are found.
     */
    fun read(source: RandomAccessSource, audioStart: Long, audioEnd: Long = source.size): Mp3Info {
        val (firstPos, first) = findFirstFrame(source, audioStart, audioEnd)
            ?: throw CorruptedFileException("No MPEG audio frames found")
        val frame = source.readUpTo(firstPos, minOf(first.frameLength, 2048).coerceAtLeast(64))

        // Xing / Info header (LAME, most VBR encoders).
        val xingAt = first.xingOffset
        if (frame.size >= xingAt + 12) {
            val tag = String(frame, xingAt, 4, Charsets.ISO_8859_1)
            if (tag == "Xing" || tag == "Info") {
                val flags = frame.u32(xingAt + 4).toInt()
                if (flags and 1 != 0) {
                    val frames = frame.u32(xingAt + 8)
                    if (frames > 0) {
                        val durationMs = frames * first.samplesPerFrame * 1000 / first.sampleRate
                        val audioBytes = audioEnd - firstPos
                        val bitrate = if (durationMs > 0) (audioBytes * 8 * 1000 / durationMs).toInt() else first.bitrate
                        return Mp3Info(first.sampleRate, first.channels, bitrate, durationMs, tag == "Xing")
                    }
                }
            }
        }
        // VBRI header (Fraunhofer encoders), always 32 bytes after the frame header.
        if (frame.size >= 36 + 18 && String(frame, 36, 4, Charsets.ISO_8859_1) == "VBRI") {
            val frames = frame.u32(36 + 14)
            if (frames > 0) {
                val durationMs = frames * first.samplesPerFrame * 1000 / first.sampleRate
                val bitrate = if (durationMs > 0) ((audioEnd - firstPos) * 8 * 1000 / durationMs).toInt() else first.bitrate
                return Mp3Info(first.sampleRate, first.channels, bitrate, durationMs, true)
            }
        }
        // No header: check whether the bitrate varies over the first frames; scan the whole file if so.
        if (isVariableBitrate(source, firstPos, audioEnd)) {
            return scanAllFrames(source, firstPos, audioEnd, first)
        }
        val durationMs = (audioEnd - firstPos) * 8 * 1000 / first.bitrate
        return Mp3Info(first.sampleRate, first.channels, first.bitrate, durationMs, false)
    }

    private fun findFirstFrame(source: RandomAccessSource, start: Long, end: Long): Pair<Long, FrameHeader>? {
        val window = source.readUpTo(start, minOf(SYNC_SEARCH_LIMIT.toLong(), end - start).toInt())
        var i = 0
        while (i + 4 <= window.size) {
            if (window.u8(i) == 0xFF && window.u8(i + 1) and 0xE0 == 0xE0) {
                val header = FrameHeader.parse(window.u32(i).toInt())
                if (header != null && header.frameLength > 4) {
                    // Confirm with the following frame to avoid false syncs inside garbage/tags.
                    val nextPos = start + i + header.frameLength
                    if (nextPos + 4 > end) return (start + i) to header
                    val next = source.readUpTo(nextPos, 4)
                    if (next.size == 4) {
                        val nextHeader = FrameHeader.parse(next.u32(0).toInt())
                        if (nextHeader != null && nextHeader.sampleRate == header.sampleRate && nextHeader.layer == header.layer) {
                            return (start + i) to header
                        }
                    }
                }
            }
            i++
        }
        return null
    }

    private fun isVariableBitrate(source: RandomAccessSource, firstPos: Long, end: Long): Boolean {
        var pos = firstPos
        var bitrate = -1
        repeat(40) {
            if (pos + 4 > end) return false
            val header = FrameHeader.parse(source.readUpTo(pos, 4).let { if (it.size < 4) return false else it.u32(0).toInt() }) ?: return false
            if (bitrate != -1 && header.bitrate != bitrate) return true
            bitrate = header.bitrate
            pos += header.frameLength
        }
        return false
    }

    private fun scanAllFrames(source: RandomAccessSource, firstPos: Long, end: Long, first: FrameHeader): Mp3Info {
        val buffer = ByteArray(1 shl 16)
        var bufferStart = -1L
        var bufferLength = 0
        fun headerAt(pos: Long): Int? {
            if (pos < bufferStart || pos + 4 > bufferStart + bufferLength) {
                bufferStart = pos
                bufferLength = source.read(pos, buffer, 0, buffer.size).coerceAtLeast(0)
                if (bufferLength < 4) return null
            }
            return buffer.u32((pos - bufferStart).toInt()).toInt()
        }
        var pos = firstPos
        var samples = 0L
        while (pos + 4 <= end) {
            val raw = headerAt(pos) ?: break
            val header = FrameHeader.parse(raw)
            if (header == null || header.frameLength <= 4) {
                pos++ // resync
                continue
            }
            samples += header.samplesPerFrame
            pos += header.frameLength
        }
        val durationMs = samples * 1000 / first.sampleRate
        val bitrate = if (durationMs > 0) ((end - firstPos) * 8 * 1000 / durationMs).toInt() else first.bitrate
        return Mp3Info(first.sampleRate, first.channels, bitrate, durationMs, true)
    }
}
