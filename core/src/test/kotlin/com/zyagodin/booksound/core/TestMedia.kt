package com.zyagodin.booksound.core

import com.zyagodin.booksound.core.io.BeWriter
import com.zyagodin.booksound.core.metadata.mp4.Mp4Node

/** Builders for small synthetic media files used in tests. */
object TestMedia {

    /**
     * Minimal AAC-in-MP4 file: [chunks] chunks of [samplesPerChunk] samples of [sampleSize] bytes,
     * sample i filled with byte value (i % 251). 44.1 kHz, 1024 samples per frame.
     * If [moovFirst] the moov precedes mdat, otherwise it follows it (typical for recorders).
     */
    fun aacMp4(
        chunks: Int = 4,
        samplesPerChunk: Int = 10,
        sampleSize: Int = 32,
        moovFirst: Boolean = false,
        extraIlst: List<Mp4Node> = emptyList(),
    ): ByteArray {
        val sampleCount = chunks * samplesPerChunk
        val media = ByteArray(sampleCount * sampleSize) { (it / sampleSize % 251).toByte() }
        val ftyp = BeWriter().u32(24).fourCC("ftyp").fourCC("M4A ").u32(0).fourCC("isom").fourCC("mp42").toByteArray()

        fun build(mdatPayloadOffset: Long): ByteArray {
            val offsets = LongArray(chunks) { mdatPayloadOffset + it.toLong() * samplesPerChunk * sampleSize }
            val timescale = 44100L
            val duration = sampleCount * 1024L
            val esds = BeWriter().u32(0)
                .u8(0x03).u8(25).u16(1).u8(0)
                .u8(0x04).u8(17).u8(0x40).u8(0x15).u24(0).u32(128000).u32(64000)
                .u8(0x05).u8(2).u8(0x12).u8(0x10)
                .u8(0x06).u8(1).u8(2)
                .toByteArray()
            val mp4a = BeWriter().zeros(6).u16(1).u16(0).u16(0).u32(0).u16(2).u16(16).u16(0).u16(0).u32(44100L shl 16)
                .u32(8 + esds.size).fourCC("esds").bytes(esds).toByteArray()
            val stsd = BeWriter().u32(0).u32(1).u32(8 + mp4a.size).fourCC("mp4a").bytes(mp4a).toByteArray()
            val stbl = Mp4Node.container(
                "stbl",
                listOf(
                    Mp4Node.leaf("stsd", stsd),
                    Mp4Node.leaf("stts", BeWriter().u32(0).u32(1).u32(sampleCount).u32(1024).toByteArray()),
                    Mp4Node.leaf("stsc", BeWriter().u32(0).u32(1).u32(1).u32(samplesPerChunk).u32(1).toByteArray()),
                    Mp4Node.leaf("stsz", BeWriter().u32(0).u32(sampleSize).u32(sampleCount).toByteArray()),
                    Mp4Node.leaf("stco", BeWriter().u32(0).u32(chunks).also { w -> offsets.forEach { w.u32(it) } }.toByteArray()),
                ),
            )
            val trak = Mp4Node.container(
                "trak",
                listOf(
                    Mp4Node.leaf("tkhd", BeWriter().u32(3).u32(0).u32(0).u32(1).u32(0).u32(duration * 1000 / timescale).zeros(60).toByteArray()),
                    Mp4Node.container(
                        "mdia",
                        listOf(
                            Mp4Node.leaf("mdhd", BeWriter().u32(0).u32(0).u32(0).u32(timescale).u32(duration).u16(0x55C4).u16(0).toByteArray()),
                            Mp4Node.leaf("hdlr", BeWriter().u32(0).u32(0).fourCC("soun").zeros(12).u8(0).toByteArray()),
                            Mp4Node.container("minf", listOf(stbl)),
                        ),
                    ),
                ),
            )
            val mvhd = BeWriter().u32(0).u32(0).u32(0).u32(1000).u32(duration * 1000 / timescale).u32(0x00010000).u16(0x0100)
                .zeros(10).zeros(36).zeros(24).u32(2).toByteArray()
            val children = mutableListOf(Mp4Node.leaf("mvhd", mvhd), trak)
            if (extraIlst.isNotEmpty()) {
                children += Mp4Node.container(
                    "udta",
                    listOf(
                        Mp4Node.container(
                            "meta",
                            listOf(
                                Mp4Node.leaf("hdlr", BeWriter().u32(0).u32(0).fourCC("mdir").fourCC("appl").u32(0).u32(0).u8(0).toByteArray()),
                                Mp4Node.container("ilst", extraIlst),
                            ),
                            prefix = ByteArray(4),
                        ),
                    ),
                )
            }
            return Mp4Node.container("moov", children).toByteArray()
        }

        val mdatHeader = BeWriter().u32(media.size + 8L).fourCC("mdat").toByteArray()
        return if (moovFirst) {
            val moovSize = build(0).size
            val moov = build((ftyp.size + moovSize + 8).toLong())
            ftyp + moov + mdatHeader + media
        } else {
            val moov = build((ftyp.size + 8).toLong())
            ftyp + mdatHeader + media + moov
        }
    }

    fun ilstText(type: String, value: String): Mp4Node = Mp4Node.container(
        type,
        listOf(Mp4Node.leaf("data", BeWriter().u32(1).u32(0).bytes(value.toByteArray(Charsets.UTF_8)).toByteArray())),
    )

    val TINY_JPEG: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 16, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1, 2, 3, 4, 5, 0xFF.toByte(), 0xD9.toByte())

    /** MPEG-1 Layer III, 128 kbps, 44.1 kHz, stereo frame header (frame length 417/418 bytes). */
    private const val MP3_HEADER = 0xFFFB9000.toInt()

    fun mp3Frames(count: Int): ByteArray {
        val frameLength = 144 * 128000 / 44100 // 417, padding bit is 0
        val out = BeWriter()
        repeat(count) {
            out.u32(MP3_HEADER)
            out.zeros(frameLength - 4)
        }
        return out.toByteArray()
    }

    /** ID3v2.3 tag with the given frames (each frame body already encoded). */
    fun id3v23(frames: List<Pair<String, ByteArray>>): ByteArray {
        val body = BeWriter()
        for ((id, data) in frames) body.fourCC(id).u32(data.size).u16(0).bytes(data)
        val bytes = body.toByteArray()
        val size = bytes.size
        val header = BeWriter().fourCC("ID3\u0003").u8(0).u8(0)
            .u8(size ushr 21 and 0x7F).u8(size ushr 14 and 0x7F).u8(size ushr 7 and 0x7F).u8(size and 0x7F)
        return header.toByteArray() + bytes
    }

    fun id3TextUtf16(text: String): ByteArray = byteArrayOf(1, 0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)

    fun id3TextLatin1Raw(bytes: ByteArray): ByteArray = byteArrayOf(0) + bytes

    fun id3TxxxUtf8(key: String, value: String): ByteArray =
        byteArrayOf(3) + key.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + value.toByteArray(Charsets.UTF_8)

    fun id3Apic(jpeg: ByteArray): ByteArray =
        byteArrayOf(0) + "image/jpeg".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0, 3, 0) + jpeg
}
