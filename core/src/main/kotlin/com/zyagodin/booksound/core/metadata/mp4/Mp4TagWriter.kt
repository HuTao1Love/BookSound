package com.zyagodin.booksound.core.metadata.mp4

import com.zyagodin.booksound.core.io.BeWriter
import com.zyagodin.booksound.core.io.ByteSink
import com.zyagodin.booksound.core.io.CancellationSignal
import com.zyagodin.booksound.core.io.RandomAccessSource
import com.zyagodin.booksound.core.io.u32
import com.zyagodin.booksound.core.io.u64
import com.zyagodin.booksound.core.io.u8
import com.zyagodin.booksound.core.metadata.CorruptedFileException
import com.zyagodin.booksound.core.metadata.ParsedAudioFile
import com.zyagodin.booksound.core.metadata.UnsupportedFormatException
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.core.model.EmbeddedPicture

/** Everything that is written into the output file's metadata. */
data class Mp4TagSpec(
    val bookId: String,
    val metadata: BookMetadata,
    val chapters: List<Chapter>,
    val cover: EmbeddedPicture?,
    val encoder: String = "BookSound",
)

/**
 * Produces an M4B from an existing non-fragmented MP4 audio file, replacing all iTunes metadata,
 * cover art and chapters (both a QuickTime chapter track and a Nero `chpl` box).
 *
 * Output is written strictly sequentially ("fast start": `ftyp`, `moov`, media data), so it can
 * stream into any writable target, e.g. a SAF document. Media data is copied unmodified and chunk
 * offsets are relocated accordingly.
 */
object Mp4TagWriter {

    private const val CHAPTER_TIMESCALE = 1000L

    fun write(
        source: RandomAccessSource,
        sink: ByteSink,
        spec: Mp4TagSpec,
        cancellation: CancellationSignal = CancellationSignal.NONE,
    ): Long {
        val doc = Mp4Reader.load(source)
        if (doc.isFragmented) throw UnsupportedFormatException("Fragmented MP4 files cannot be re-tagged")
        val moov = doc.moov
        val audioTrack = doc.audioTrack() ?: throw UnsupportedFormatException("MP4 file has no audio track")
        val movieHeader = Mp4Boxes.movieHeader(moov) ?: throw CorruptedFileException("Missing movie header")

        removeExistingChapterTracks(moov)
        rebuildUserData(moov, spec)

        val totalDurationMs = Mp4Parser.durationMs(doc, audioTrack)
            ?: spec.chapters.maxOfOrNull { it.endMs } ?: 0L
        val chapterSamples = buildChapterSamples(spec.chapters, totalDurationMs)
        val chapterTrackId = if (chapterSamples != null) {
            val id = doc.tracks.mapNotNull { Mp4Boxes.trackId(it) }.maxOrNull()?.plus(1) ?: 1L
            moov.requireChildren().add(
                buildChapterTrack(id, chapterSamples, movieHeader.timescale, totalDurationMs),
            )
            setNextTrackId(moov, id + 1)
            addChapterReference(audioTrack, id)
            id
        } else {
            null
        }

        // Boxes whose bytes are copied to the output after the new moov.
        val copied = doc.topLevel.filter { it.type !in setOf("ftyp", "moov", "free", "skip", "wide") }
        val ftyp = buildFtyp()
        val chapterData = chapterSamples?.data

        // Chunk offset tables of every track, to be relocated. The chapter track's table is
        // relocated separately because its data lives in the appended mdat.
        val offsetTables = moov.childrenOf("trak")
            .filter { chapterTrackId == null || Mp4Boxes.trackId(it) != chapterTrackId }
            .mapNotNull { it.find("mdia/minf/stbl") }
        val originalOffsets = offsetTables.associateWith { SampleTable.readChunkOffsets(it) }

        // Relocating may switch a table from stco to co64, which grows moov and shifts every offset
        // again; iterate until the layout is stable (at most one switch can happen).
        var layout = computeLayout(ftyp, moov, copied, chapterData)
        repeat(3) {
            for ((stbl, offsets) in originalOffsets) {
                writeChunkOffsets(stbl, LongArray(offsets.size) { relocate(offsets[it], copied, layout) })
            }
            chapterTrackId?.let { id ->
                val stbl = moov.childrenOf("trak").first { Mp4Boxes.trackId(it) == id }.find("mdia/minf/stbl")!!
                writeChunkOffsets(stbl, longArrayOf(layout.chapterDataOffset))
            }
            val newLayout = computeLayout(ftyp, moov, copied, chapterData)
            if (newLayout == layout) return writeOutput(source, sink, ftyp, moov, copied, layout, chapterData, cancellation)
            layout = newLayout
        }
        error("MP4 layout did not converge")
    }

    // ---------------------------------------------------------------- layout

    private data class Layout(val moovSize: Long, val payloadStarts: List<Long>, val headerSizes: List<Int>, val chapterDataOffset: Long, val total: Long)

    private fun computeLayout(ftyp: ByteArray, moov: Mp4Node, copied: List<TopLevelBox>, chapterData: ByteArray?): Layout {
        val moovSize = moov.size()
        var pos = ftyp.size + moovSize
        val starts = ArrayList<Long>(copied.size)
        val headers = ArrayList<Int>(copied.size)
        for (box in copied) {
            val header = if (box.payloadSize + 8 > UInt.MAX_VALUE.toLong()) 16 else 8
            headers += header
            starts += pos + header
            pos += header + box.payloadSize
        }
        val chapterOffset = pos + 8
        if (chapterData != null) pos += 8 + chapterData.size
        return Layout(moovSize, starts, headers, chapterOffset, pos)
    }

    private fun relocate(offset: Long, copied: List<TopLevelBox>, layout: Layout): Long {
        for ((i, box) in copied.withIndex()) {
            if (offset >= box.payloadOffset && offset < box.end) {
                return offset - box.payloadOffset + layout.payloadStarts[i]
            }
        }
        // An empty chunk may point exactly at the end of the media data.
        for ((i, box) in copied.withIndex()) {
            if (offset == box.end) return offset - box.payloadOffset + layout.payloadStarts[i]
        }
        throw CorruptedFileException("Chunk offset $offset points outside the media data")
    }

    /** Rewrites stco/co64 with [offsets], using a 64-bit table only when needed. */
    private fun writeChunkOffsets(stbl: Mp4Node, offsets: LongArray) {
        val needs64 = offsets.any { it > UInt.MAX_VALUE.toLong() }
        val children = stbl.requireChildren()
        val index = children.indexOfFirst { it.type == "stco" || it.type == "co64" }
        val w = BeWriter(8 + offsets.size * 8).u32(0).u32(offsets.size)
        val node = if (needs64) {
            offsets.forEach { w.u64(it) }
            Mp4Node.leaf("co64", w.toByteArray())
        } else {
            offsets.forEach { w.u32(it) }
            Mp4Node.leaf("stco", w.toByteArray())
        }
        if (index >= 0) children[index] = node else children += node
    }

    private fun writeOutput(
        source: RandomAccessSource,
        sink: ByteSink,
        ftyp: ByteArray,
        moov: Mp4Node,
        copied: List<TopLevelBox>,
        layout: Layout,
        chapterData: ByteArray?,
        cancellation: CancellationSignal,
    ): Long {
        sink.write(ftyp)
        sink.write(moov.toByteArray())
        for ((i, box) in copied.withIndex()) {
            cancellation.throwIfCancelled()
            val header = BeWriter(16)
            if (layout.headerSizes[i] == 16) header.u32(1).fourCC(box.type).u64(box.payloadSize + 16)
            else header.u32(box.payloadSize + 8).fourCC(box.type)
            sink.write(header.toByteArray())
            source.copyTo(box.payloadOffset, box.payloadSize, sink, cancellation)
        }
        if (chapterData != null) {
            sink.write(BeWriter(8).u32(chapterData.size + 8L).fourCC("mdat").toByteArray())
            sink.write(chapterData)
        }
        check(sink.bytesWritten == layout.total) { "Wrote ${sink.bytesWritten} bytes, expected ${layout.total}" }
        return layout.total
    }

    // ---------------------------------------------------------------- metadata

    private fun buildFtyp(): ByteArray = BeWriter(32)
        .u32(32).fourCC("ftyp")
        .fourCC("M4B ").u32(0x200)
        .fourCC("isom").fourCC("M4B ").fourCC("M4A ").fourCC("mp42")
        .toByteArray()

    private fun rebuildUserData(moov: Mp4Node, spec: Mp4TagSpec) {
        val children = moov.requireChildren()
        children.removeAll { it.type == "meta" }
        val existing = moov.child("udta")
        val kept = existing?.children?.filter { it.type != "meta" && it.type != "chpl" }.orEmpty()
        children.removeAll { it.type == "udta" }

        val udtaChildren = mutableListOf<Mp4Node>()
        buildChpl(spec.chapters)?.let { udtaChildren += it }
        udtaChildren += Mp4Node.container(
            "meta",
            listOf(
                Mp4Node.leaf(
                    "hdlr",
                    BeWriter().u32(0).u32(0).fourCC("mdir").fourCC("appl").u32(0).u32(0).u8(0).toByteArray(),
                ),
                buildIlst(spec),
            ),
            prefix = ByteArray(4),
        )
        udtaChildren += kept
        children += Mp4Node.container("udta", udtaChildren)
    }

    private fun buildIlst(spec: Mp4TagSpec): Mp4Node {
        val m = spec.metadata
        val items = mutableListOf<Mp4Node>()
        fun text(type: String, value: String?) {
            if (!value.isNullOrBlank()) items += Mp4Node.container(type, listOf(dataAtom(1, value.toByteArray(Charsets.UTF_8))))
        }
        fun freeform(mean: String, name: String, value: String?) {
            if (value.isNullOrBlank()) return
            items += Mp4Node.container(
                "----",
                listOf(
                    Mp4Node.leaf("mean", BeWriter().u32(0).bytes(mean.toByteArray(Charsets.UTF_8)).toByteArray()),
                    Mp4Node.leaf("name", BeWriter().u32(0).bytes(name.toByteArray(Charsets.UTF_8)).toByteArray()),
                    dataAtom(1, value.toByteArray(Charsets.UTF_8)),
                ),
            )
        }
        val seriesLabel = m.series?.let { s -> m.seriesIndex?.let { "$s #$it" } ?: s }

        text("©nam", m.title)
        text("©alb", m.title)
        text("©ART", m.author)
        text("aART", m.author)
        text("©wrt", m.narrator)
        text("©nrt", m.narrator)
        text("©gen", m.genre ?: "Audiobook")
        text("©day", m.year)
        text("©grp", seriesLabel)
        text("©mvn", m.series)
        m.seriesIndex?.toIntOrNull()?.takeIf { m.series != null && it in 0..0xFFFF }?.let {
            items += Mp4Node.container("©mvi", listOf(dataAtom(21, BeWriter().u16(it).toByteArray())))
        }
        text("desc", m.description?.take(255))
        text("ldes", m.description)
        text("©too", spec.encoder)
        // Media kind 2 = audiobook.
        items += Mp4Node.container("stik", listOf(dataAtom(21, byteArrayOf(2))))
        spec.cover?.let { cover ->
            val type = if (cover.mimeType == "image/png") 14 else 13
            items += Mp4Node.container("covr", listOf(dataAtom(type, cover.bytes)))
        }
        freeform("com.apple.iTunes", "SERIES", m.series)
        freeform("com.apple.iTunes", "SERIES-PART", m.series?.let { m.seriesIndex })
        freeform("com.apple.iTunes", "NARRATOR", m.narrator)
        freeform("com.apple.iTunes", "LANGUAGE", m.language)
        freeform(ParsedAudioFile.BOOKSOUND_NAMESPACE, "BOOK_ID", spec.bookId)
        return Mp4Node.container("ilst", items)
    }

    private fun dataAtom(type: Int, value: ByteArray): Mp4Node =
        Mp4Node.leaf("data", BeWriter(8 + value.size).u32(type).u32(0).bytes(value).toByteArray())

    private fun buildChpl(chapters: List<Chapter>): Mp4Node? {
        if (chapters.isEmpty() || chapters.size > 255) return null
        val w = BeWriter().u8(1).u24(0).u32(0).u8(chapters.size)
        for (c in chapters) {
            val title = truncateUtf8(c.title, 255)
            w.u64(c.startMs * 10_000).u8(title.size).bytes(title)
        }
        return Mp4Node.leaf("chpl", w.toByteArray())
    }

    private fun truncateUtf8(text: String, maxBytes: Int): ByteArray {
        var bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return bytes
        var end = text.length
        while (end > 0 && bytes.size > maxBytes) {
            end--
            if (end > 0 && Character.isLowSurrogate(text[end])) end--
            bytes = text.substring(0, end).toByteArray(Charsets.UTF_8)
        }
        return bytes
    }

    // ---------------------------------------------------------------- chapter track

    private class ChapterSamples(val data: ByteArray, val sizes: IntArray, val durations: LongArray)

    private fun buildChapterSamples(chapters: List<Chapter>, totalDurationMs: Long): ChapterSamples? {
        if (chapters.isEmpty()) return null
        val sorted = chapters.sortedBy { it.startMs }
        val data = BeWriter()
        val sizes = IntArray(sorted.size)
        val durations = LongArray(sorted.size)
        for ((i, c) in sorted.withIndex()) {
            val title = truncateUtf8(c.title, 1024)
            val before = data.size
            data.u16(title.size).bytes(title)
            // 'encd' atom declaring UTF-8, as written by QuickTime and ffmpeg.
            data.u32(12).fourCC("encd").u32(0x00000100)
            sizes[i] = data.size - before
            val end = if (i + 1 < sorted.size) sorted[i + 1].startMs else maxOf(totalDurationMs, c.startMs + 1)
            val start = if (i == 0) 0L else c.startMs
            durations[i] = (end - start).coerceAtLeast(1)
        }
        return ChapterSamples(data.toByteArray(), sizes, durations)
    }

    private fun buildChapterTrack(trackId: Long, samples: ChapterSamples, movieTimescale: Long, durationMs: Long): Mp4Node {
        val mediaDuration = samples.durations.sum()
        val movieDuration = if (movieTimescale > 0) durationMs * movieTimescale / 1000 else mediaDuration
        val tkhd = BeWriter().also { w ->
            // Flags 0: the track is disabled and only serves as the audio track's chapter reference.
            if (movieDuration > UInt.MAX_VALUE.toLong()) {
                w.u8(1).u24(0).u64(0).u64(0).u32(trackId).u32(0).u64(movieDuration)
            } else {
                w.u8(0).u24(0).u32(0).u32(0).u32(trackId).u32(0).u32(movieDuration)
            }
        }
            .zeros(8).u16(0).u16(0).u16(0).u16(0)
            .u32(0x00010000).u32(0).u32(0).u32(0).u32(0x00010000).u32(0).u32(0).u32(0).u32(0x40000000)
            .u32(0).u32(0)
            .toByteArray()
        val mdhd = BeWriter().u32(0).u32(0).u32(0).u32(CHAPTER_TIMESCALE).u32(mediaDuration).u16(0x55C4).u16(0).toByteArray()
        val hdlr = BeWriter().u32(0).u32(0).fourCC("text").zeros(12).bytes("Chapters".toByteArray()).u8(0).toByteArray()
        val gmhd = Mp4Node.container(
            "gmhd",
            listOf(
                Mp4Node.leaf("gmin", BeWriter().u32(0).u16(0x40).u16(0x8000).u16(0x8000).u16(0x8000).u16(0).u16(0).toByteArray()),
                Mp4Node.leaf(
                    "text",
                    BeWriter().u32(0x00010000).u32(0).u32(0).u32(0).u32(0x00010000).u32(0).u32(0).u32(0).u32(0x40000000).toByteArray(),
                ),
            ),
        )
        val dinf = Mp4Node.container(
            "dinf",
            listOf(Mp4Node.leaf("dref", BeWriter().u32(0).u32(1).u32(12).fourCC("url ").u32(1).toByteArray())),
        )
        val textEntry = BeWriter()
            .zeros(6).u16(1)
            .u32(1) // display flags
            .u8(0).u8(0) // justification
            .u32(0) // background colour
            .u32(0).u32(0) // default text box
            .u32(0).u16(1).u8(0).u8(0).u32(0) // style record
            .u32(13).fourCC("ftab").u16(1).u16(1).u8(0) // font table
            .toByteArray()
        val stsd = BeWriter().u32(0).u32(1).u32(textEntry.size + 8L).fourCC("text").bytes(textEntry).toByteArray()
        val stts = BeWriter().u32(0).u32(samples.durations.size).also { w ->
            samples.durations.forEach { w.u32(1).u32(it) }
        }.toByteArray()
        val stsz = BeWriter().u32(0).u32(0).u32(samples.sizes.size).also { w -> samples.sizes.forEach { w.u32(it) } }.toByteArray()
        val stsc = BeWriter().u32(0).u32(1).u32(1).u32(samples.sizes.size).u32(1).toByteArray()
        val stbl = Mp4Node.container(
            "stbl",
            listOf(
                Mp4Node.leaf("stsd", stsd),
                Mp4Node.leaf("stts", stts),
                Mp4Node.leaf("stsc", stsc),
                Mp4Node.leaf("stsz", stsz),
                Mp4Node.leaf("stco", BeWriter().u32(0).u32(1).u32(0).toByteArray()),
            ),
        )
        val minf = Mp4Node.container("minf", listOf(gmhd, dinf, stbl))
        val mdia = Mp4Node.container("mdia", listOf(Mp4Node.leaf("mdhd", mdhd), Mp4Node.leaf("hdlr", hdlr), minf))
        return Mp4Node.container("trak", listOf(Mp4Node.leaf("tkhd", tkhd), mdia))
    }

    private fun removeExistingChapterTracks(moov: Mp4Node) {
        val tracks = moov.childrenOf("trak")
        val chapterIds = tracks.flatMap { Mp4Boxes.chapterTrackRefs(it) }.toSet()
        moov.requireChildren().removeAll { trak ->
            trak.type == "trak" && Mp4Boxes.trackId(trak) in chapterIds &&
                Mp4Boxes.handlerType(trak) in setOf("text", "sbtl", "subt")
        }
        for (trak in moov.childrenOf("trak")) {
            val tref = trak.child("tref") ?: continue
            tref.requireChildren().removeAll { it.type == "chap" }
            if (tref.children.isNullOrEmpty()) trak.requireChildren().remove(tref)
        }
    }

    private fun addChapterReference(audioTrack: Mp4Node, chapterTrackId: Long) {
        val chap = Mp4Node.leaf("chap", BeWriter().u32(chapterTrackId).toByteArray())
        val tref = audioTrack.child("tref")
        if (tref != null) {
            tref.requireChildren().add(chap)
        } else {
            val children = audioTrack.requireChildren()
            val tkhdIndex = children.indexOfFirst { it.type == "tkhd" }
            children.add(tkhdIndex + 1, Mp4Node.container("tref", listOf(chap)))
        }
    }

    private fun setNextTrackId(moov: Mp4Node, next: Long) {
        val mvhd = moov.child("mvhd") ?: return
        val p = mvhd.payload!!.copyOf()
        val at = if (p.u8(0) == 1) 108 else 96
        if (p.size < at + 4) return
        val current = p.u32(at)
        if (current >= next) return
        p[at] = (next ushr 24).toByte()
        p[at + 1] = (next ushr 16).toByte()
        p[at + 2] = (next ushr 8).toByte()
        p[at + 3] = next.toByte()
        mvhd.payload = p
    }
}
