package com.zyagodin.booksound.core.metadata.mp4

import com.zyagodin.booksound.core.io.BeWriter
import com.zyagodin.booksound.core.io.ByteSink
import com.zyagodin.booksound.core.io.CancellationSignal
import com.zyagodin.booksound.core.io.RandomAccessSource
import com.zyagodin.booksound.core.io.u32
import com.zyagodin.booksound.core.io.u8
import com.zyagodin.booksound.core.metadata.CorruptedFileException
import com.zyagodin.booksound.core.metadata.UnsupportedFormatException
import java.io.EOFException

/**
 * Joins MP4 audio files of one stream format (same codec configuration and timescale) into one,
 * without re-encoding.
 *
 * The result is a virtual MP4 file that reads from the parts on demand: `ftyp`, one `mdat` holding
 * every part's media data back to back, then a `moov` whose audio track lists all their samples.
 * Only the audio track is kept; other tracks, edit lists and metadata are dropped ([Mp4TagWriter]
 * writes the book's own). Nothing is copied until the result is read, so the tag writer streams
 * the parts straight into the final file, and the sample tables cost a few bytes per sample.
 */
object Mp4Concat {

    /**
     * Takes ownership of [parts]: closing the result closes them, and so does a failure.
     * Throws [UnsupportedFormatException] when the parts can't be joined as they are.
     */
    fun join(parts: List<RandomAccessSource>): RandomAccessSource {
        try {
            require(parts.isNotEmpty())
            return build(parts)
        } catch (t: Throwable) {
            parts.forEach { runCatching { it.close() } }
            throw t
        }
    }

    /** One part's audio track, reduced to what the joined file needs. */
    private class Part(
        val source: RandomAccessSource,
        val mdats: List<TopLevelBox>,
        val timescale: Long,
        val stsd: ByteArray,
        val sampleCount: Int,
        /** stsz payload; per-sample sizes from byte 12 unless [constantSize] is set. */
        val stsz: ByteArray,
        val constantSize: Int,
        /** stts payload: entry count at 4, (count, delta) pairs from 8. */
        val stts: ByteArray,
        /** stsc payload: entry count at 4, (first chunk, samples per chunk, description) from 8. */
        val stsc: ByteArray,
        val chunkOffsets: LongArray,
    )

    private fun build(sources: List<RandomAccessSource>): RandomAccessSource {
        val firstDoc = Mp4Reader.load(sources.first())
        val firstTrack = firstDoc.audioTrack() ?: throw UnsupportedFormatException("MP4 file has no audio track")
        val firstStream = Mp4Boxes.audioStreamInfo(firstTrack)
        val parts = sources.mapIndexed { i, source ->
            // The first part's moov is kept as the template; the others are dropped once read.
            val doc = if (i == 0) firstDoc else Mp4Reader.load(source)
            val part = readPart(source, doc)
            if (i > 0) {
                val stream = doc.audioTrack()?.let { Mp4Boxes.audioStreamInfo(it) }
                val sameStream = stream != null && firstStream != null && stream.codec == firstStream.codec &&
                    stream.sampleRate == firstStream.sampleRate && stream.channels == firstStream.channels &&
                    stream.codecConfig.contentEqualsNullable(firstStream.codecConfig)
                if (!sameStream) throw UnsupportedFormatException("Part ${i + 1} has a different audio format")
            }
            part
        }.filter { it.sampleCount > 0 }
        if (parts.isEmpty()) throw CorruptedFileException("MP4 files contain no audio samples")
        val first = parts.first()
        if (parts.any { it.timescale != first.timescale || it.stsd.stsdEntryType() != first.stsd.stsdEntryType() }) {
            throw UnsupportedFormatException("Parts use different timescales or sample formats")
        }

        // Virtual layout: ftyp, mdat (every part's media data in order), moov.
        val ftyp = BeWriter(24).u32(24).fourCC("ftyp").fourCC("M4A ").u32(0).fourCC("isom").fourCC("mp42").toByteArray()
        val mdatPayloadStart = ftyp.size + MDAT_HEADER_SIZE.toLong()
        val segments = ArrayList<Segment>()
        var position = mdatPayloadStart
        val chunkOffsets = LongArray(parts.sumOf { it.chunkOffsets.size })
        var chunk = 0
        for (part in parts) {
            val mdatStarts = LongArray(part.mdats.size)
            for ((i, mdat) in part.mdats.withIndex()) {
                mdatStarts[i] = position
                if (mdat.payloadSize > 0) segments += Segment.Slice(position, mdat.payloadSize, part.source, mdat.payloadOffset)
                position += mdat.payloadSize
            }
            for (offset in part.chunkOffsets) {
                val i = part.mdats.indexOfFirst { offset >= it.payloadOffset && offset <= it.end }
                if (i < 0) throw CorruptedFileException("Chunk offset $offset points outside the media data")
                chunkOffsets[chunk++] = mdatStarts[i] + offset - part.mdats[i].payloadOffset
            }
        }
        val mdatPayloadSize = position - mdatPayloadStart
        val mdatHeader = BeWriter(MDAT_HEADER_SIZE).u32(1).fourCC("mdat").u64(mdatPayloadSize + MDAT_HEADER_SIZE).toByteArray()

        val mediaDuration = parts.sumOf { totalDuration(it.stts) }
        val moov = buildMoov(firstDoc.moov, firstTrack, parts, chunkOffsets, mediaDuration).toByteArray()

        val all = ArrayList<Segment>(segments.size + 3)
        all += Segment.Bytes(0, ftyp)
        all += Segment.Bytes(ftyp.size.toLong(), mdatHeader)
        all += segments
        all += Segment.Bytes(position, moov)
        return JoinedSource(all, sources)
    }

    private fun readPart(source: RandomAccessSource, doc: Mp4Document): Part {
        if (doc.isFragmented) throw UnsupportedFormatException("Fragmented MP4 files cannot be joined")
        val track = doc.audioTrack() ?: throw UnsupportedFormatException("MP4 file has no audio track")
        val stbl = track.find("mdia/minf/stbl") ?: throw CorruptedFileException("Missing sample table")
        if (stbl.child("ctts") != null) throw UnsupportedFormatException("Audio track has composition offsets")
        val timescale = Mp4Boxes.mediaHeader(track)?.timescale?.takeIf { it > 0 } ?: throw CorruptedFileException("Missing media header")
        val stsd = stbl.child("stsd")?.payload ?: throw CorruptedFileException("Missing stsd")
        if (stsd.size < 16 || stsd.u32(4) != 1L) throw UnsupportedFormatException("Audio track has several sample descriptions")
        val stsz = stbl.child("stsz")?.payload ?: throw CorruptedFileException("Missing stsz")
        val stts = stbl.child("stts")?.payload ?: throw CorruptedFileException("Missing stts")
        val stsc = stbl.child("stsc")?.payload ?: throw CorruptedFileException("Missing stsc")
        try {
            val constantSize = stsz.u32(4).toInt()
            val count = stsz.u32(8)
            if (count > Int.MAX_VALUE / 8) throw UnsupportedFormatException("Too many samples")
            if (constantSize == 0 && stsz.size < 12 + count * 4) throw CorruptedFileException("Truncated stsz")
            if (stts.size < 8 + stts.u32(4) * 8) throw CorruptedFileException("Truncated stts")
            if (stsc.size < 8 + stsc.u32(4) * 12) throw CorruptedFileException("Truncated stsc")
            if (stts.entries().sumOf { stts.u32(8 + it * 8) } != count) throw CorruptedFileException("stts and stsz disagree")
            if (stsc.u32(4) > 0 && stsc.u32(8) != 1L) throw CorruptedFileException("stsc does not start at the first chunk")
            return Part(
                source = source,
                mdats = doc.topLevel.filter { it.type == "mdat" },
                timescale = timescale,
                stsd = stsd,
                sampleCount = count.toInt(),
                stsz = stsz,
                constantSize = constantSize,
                stts = stts,
                stsc = stsc,
                chunkOffsets = SampleTable.readChunkOffsets(stbl),
            )
        } catch (e: IndexOutOfBoundsException) {
            throw CorruptedFileException("Damaged sample table", e)
        }
    }

    // ---------------------------------------------------------------- moov

    private fun buildMoov(template: Mp4Node, track: Mp4Node, parts: List<Part>, chunkOffsets: LongArray, mediaDuration: Long): Mp4Node {
        val timescale = parts.first().timescale
        val movieTimescale = Mp4Boxes.movieHeader(template)?.timescale?.takeIf { it > 0 } ?: timescale
        val movieDuration = mediaDuration * movieTimescale / timescale

        val stbl = Mp4Node.container(
            "stbl",
            listOf(
                Mp4Node.leaf("stsd", parts.first().stsd),
                Mp4Node.leaf("stts", joinStts(parts)),
                Mp4Node.leaf("stsc", joinStsc(parts)),
                Mp4Node.leaf("stsz", joinStsz(parts)),
                chunkOffsetBox(chunkOffsets),
            ),
        )
        val minf = track.find("mdia/minf") ?: throw CorruptedFileException("Missing minf")
        val newMinf = Mp4Node.container("minf", minf.requireChildren().map { if (it.type == "stbl") stbl else it })
        val mdia = track.child("mdia")!!
        val newMdia = Mp4Node.container(
            "mdia",
            mdia.requireChildren().map {
                when (it.type) {
                    "mdhd" -> Mp4Node.leaf("mdhd", withDuration(it.payload!!, tkhd = false, mediaDuration))
                    "minf" -> newMinf
                    else -> it
                }
            },
        )
        val tkhd = track.child("tkhd")?.payload ?: throw CorruptedFileException("Missing tkhd")
        val trak = Mp4Node.container("trak", listOf(Mp4Node.leaf("tkhd", withDuration(tkhd, tkhd = true, movieDuration)), newMdia))
        val mvhd = template.child("mvhd")?.payload ?: throw CorruptedFileException("Missing mvhd")
        return Mp4Node.container("moov", listOf(Mp4Node.leaf("mvhd", withDuration(mvhd, tkhd = false, movieDuration)), trak))
    }

    /** Sample durations of all parts, runs of equal durations merged. */
    private fun joinStts(parts: List<Part>): ByteArray {
        val counts = LongList()
        val deltas = LongList()
        for (part in parts) {
            for (e in part.stts.entries()) {
                val count = part.stts.u32(8 + e * 8)
                val delta = part.stts.u32(12 + e * 8)
                if (count == 0L) continue
                if (deltas.size > 0 && deltas.last() == delta && counts.last() + count <= 0xFFFFFFFFL) {
                    counts.setLast(counts.last() + count)
                } else {
                    counts.add(count)
                    deltas.add(delta)
                }
            }
        }
        val out = ByteArray(8 + counts.size * 8)
        putU32(out, 4, counts.size.toLong())
        for (i in 0 until counts.size) {
            putU32(out, 8 + i * 8, counts[i])
            putU32(out, 12 + i * 8, deltas[i])
        }
        return out
    }

    /** Samples per chunk of all parts, chunk numbers shifted; repeated runs merged. */
    private fun joinStsc(parts: List<Part>): ByteArray {
        val firstChunks = LongList()
        val perChunk = LongList()
        var chunkBase = 0L
        for (part in parts) {
            for (e in part.stsc.entries(entrySize = 12)) {
                val firstChunk = part.stsc.u32(8 + e * 12)
                if (firstChunk > part.chunkOffsets.size) break
                val samples = part.stsc.u32(12 + e * 12)
                if (perChunk.size > 0 && perChunk.last() == samples) continue
                firstChunks.add(chunkBase + firstChunk)
                perChunk.add(samples)
            }
            chunkBase += part.chunkOffsets.size
        }
        val out = ByteArray(8 + firstChunks.size * 12)
        putU32(out, 4, firstChunks.size.toLong())
        for (i in 0 until firstChunks.size) {
            putU32(out, 8 + i * 12, firstChunks[i])
            putU32(out, 12 + i * 12, perChunk[i])
            putU32(out, 16 + i * 12, 1)
        }
        return out
    }

    private fun joinStsz(parts: List<Part>): ByteArray {
        val total = parts.sumOf { it.sampleCount.toLong() }
        val constant = parts.first().constantSize
        if (constant != 0 && parts.all { it.constantSize == constant }) {
            return ByteArray(12).also { putU32(it, 4, constant.toLong()); putU32(it, 8, total) }
        }
        if (12 + total * 4 > Int.MAX_VALUE - 64) throw UnsupportedFormatException("Too many samples")
        val out = ByteArray((12 + total * 4).toInt())
        putU32(out, 8, total)
        var at = 12
        for (part in parts) {
            if (part.constantSize == 0) {
                System.arraycopy(part.stsz, 12, out, at, part.sampleCount * 4)
                at += part.sampleCount * 4
            } else {
                repeat(part.sampleCount) {
                    putU32(out, at, part.constantSize.toLong())
                    at += 4
                }
            }
        }
        return out
    }

    private fun chunkOffsetBox(offsets: LongArray): Mp4Node {
        val wide = offsets.any { it > 0xFFFFFFFFL }
        val out = ByteArray(8 + offsets.size * if (wide) 8 else 4)
        putU32(out, 4, offsets.size.toLong())
        for ((i, offset) in offsets.withIndex()) {
            if (wide) {
                putU32(out, 8 + i * 8, offset ushr 32)
                putU32(out, 12 + i * 8, offset and 0xFFFFFFFFL)
            } else {
                putU32(out, 8 + i * 4, offset)
            }
        }
        return Mp4Node.leaf(if (wide) "co64" else "stco", out)
    }

    /**
     * An mvhd/mdhd (or, with [tkhd], a tkhd) payload with [duration]; switched to version 1 when the
     * duration doesn't fit 32 bits (a long book at a 44.1 kHz media timescale passes that at 27 h).
     */
    private fun withDuration(p: ByteArray, tkhd: Boolean, duration: Long): ByteArray {
        val v1 = p.u8(0) == 1
        val at = when {
            tkhd -> if (v1) 28 else 20
            else -> if (v1) 24 else 16
        }
        if (v1) return p.copyOf().also { putU32(it, at, duration ushr 32); putU32(it, at + 4, duration and 0xFFFFFFFFL) }
        if (duration <= 0xFFFFFFFFL) return p.copyOf().also { putU32(it, at, duration) }
        // Version 0 → 1: creation and modification times and the duration become 64-bit.
        val w = BeWriter(p.size + 12).u8(1).u24(p.u32(0).toInt() and 0xFFFFFF).u64(p.u32(4)).u64(p.u32(8)).u32(p.u32(12))
        if (tkhd) w.u32(p.u32(16))
        w.u64(duration)
        val rest = at + 4
        return w.bytes(p.copyOfRange(rest, p.size)).toByteArray()
    }

    private fun totalDuration(stts: ByteArray): Long = stts.entries().sumOf { stts.u32(8 + it * 8) * stts.u32(12 + it * 8) }

    private fun ByteArray.entries(entrySize: Int = 8): IntRange = 0 until minOf(u32(4), ((size - 8) / entrySize).toLong()).toInt()

    private fun ByteArray.stsdEntryType(): String = String(this, 12, 4, Charsets.ISO_8859_1)

    private fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean =
        if (this == null || other == null) this == null && other == null else contentEquals(other)

    private fun putU32(b: ByteArray, at: Int, v: Long) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }

    private const val MDAT_HEADER_SIZE = 16

    /** A growable list of longs, without boxing a value per sample run. */
    private class LongList {
        private var data = LongArray(16)
        var size = 0
            private set

        fun add(v: Long) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        operator fun get(i: Int) = data[i]
        fun last() = data[size - 1]
        fun setLast(v: Long) {
            data[size - 1] = v
        }
    }

    // ---------------------------------------------------------------- virtual file

    private sealed class Segment(val start: Long, val length: Long) {
        val end: Long get() = start + length

        class Bytes(start: Long, val data: ByteArray) : Segment(start, data.size.toLong())
        class Slice(start: Long, length: Long, val source: RandomAccessSource, val offset: Long) : Segment(start, length)
    }

    private class JoinedSource(private val segments: List<Segment>, private val owned: List<RandomAccessSource>) : RandomAccessSource {
        private val starts = LongArray(segments.size) { segments[it].start }
        override val size: Long = segments.last().end

        private fun segmentAt(position: Long): Segment {
            var i = starts.binarySearch(position)
            if (i < 0) i = -i - 2
            return segments[i]
        }

        override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            if (length == 0) return 0
            val segment = segmentAt(position)
            val within = position - segment.start
            val n = minOf(length.toLong(), segment.length - within).toInt()
            return when (segment) {
                is Segment.Bytes -> {
                    System.arraycopy(segment.data, within.toInt(), buffer, offset, n)
                    n
                }
                is Segment.Slice -> segment.source.read(segment.offset + within, buffer, offset, n)
            }
        }

        override fun copyTo(position: Long, length: Long, sink: ByteSink, cancellation: CancellationSignal) {
            var pos = position
            var left = length
            while (left > 0) {
                cancellation.throwIfCancelled()
                if (pos >= size) throw EOFException("Unexpected end of file at $pos")
                val segment = segmentAt(pos)
                val within = pos - segment.start
                val n = minOf(left, segment.length - within)
                when (segment) {
                    is Segment.Bytes -> sink.write(segment.data, within.toInt(), n.toInt())
                    // Delegated, so a file part keeps its fast channel-to-channel copy.
                    is Segment.Slice -> segment.source.copyTo(segment.offset + within, n, sink, cancellation)
                }
                pos += n
                left -= n
            }
        }

        override fun close() {
            var failure: Throwable? = null
            for (source in owned) {
                try {
                    source.close()
                } catch (t: Throwable) {
                    if (failure == null) failure = t else failure.addSuppressed(t)
                }
            }
            failure?.let { throw it }
        }
    }
}
