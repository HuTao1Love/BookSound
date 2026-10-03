package com.zyagodin.booksound.core.metadata.mp4

import com.zyagodin.booksound.core.io.RandomAccessSource
import com.zyagodin.booksound.core.io.readFully
import com.zyagodin.booksound.core.io.u16
import com.zyagodin.booksound.core.io.u32
import com.zyagodin.booksound.core.io.u64
import com.zyagodin.booksound.core.io.u8
import com.zyagodin.booksound.core.metadata.AudioContainer
import com.zyagodin.booksound.core.metadata.AudioTags
import com.zyagodin.booksound.core.metadata.CorruptedFileException
import com.zyagodin.booksound.core.metadata.ParsedAudioFile
import com.zyagodin.booksound.core.metadata.TextDecoding
import com.zyagodin.booksound.core.metadata.UnsupportedFormatException
import com.zyagodin.booksound.core.model.ChapterMark
import com.zyagodin.booksound.core.model.EmbeddedPicture

/** Reads metadata, chapters and stream information from MP4/M4A/M4B files. */
object Mp4Parser {

    fun parse(source: RandomAccessSource): ParsedAudioFile {
        val doc = Mp4Reader.load(source)
        val warnings = doc.warnings.toMutableList()
        val audioTrack = doc.audioTrack() ?: throw UnsupportedFormatException("MP4 file has no audio track")
        val stream = Mp4Boxes.audioStreamInfo(audioTrack)
        val durationMs = durationMs(doc, audioTrack)
        val ilst = doc.moov.find("udta/meta/ilst") ?: doc.moov.find("meta/ilst")
        val tagReader = IlstReader(ilst)
        val chapters = try {
            readQuickTimeChapters(doc, audioTrack, source).ifEmpty { readNeroChapters(doc.moov) }
        } catch (e: CorruptedFileException) {
            warnings += "Chapters unreadable: ${e.message}"
            readNeroChapters(doc.moov)
        }
        return ParsedAudioFile(
            container = AudioContainer.MP4,
            stream = stream,
            durationMs = durationMs,
            tags = tagReader.tags(),
            chapters = chapters.filter { durationMs == null || it.startMs < durationMs }.sortedBy { it.startMs },
            cover = tagReader.cover(),
            warnings = warnings,
            rewritable = !doc.isFragmented,
        )
    }

    fun durationMs(doc: Mp4Document, audioTrack: Mp4Node): Long? {
        Mp4Boxes.mediaHeader(audioTrack)?.let { (timescale, duration) ->
            if (timescale > 0 && duration > 0 && duration != 0xFFFFFFFFL) return duration * 1000 / timescale
        }
        Mp4Boxes.movieHeader(doc.moov)?.let { header ->
            if (header.timescale > 0 && header.duration > 0) return header.duration * 1000 / header.timescale
        }
        return null
    }

    /** Nero style `udta/chpl` chapters (start times in 100 ns units). */
    fun readNeroChapters(moov: Mp4Node): List<ChapterMark> {
        val p = moov.find("udta/chpl")?.payload ?: return emptyList()
        if (p.size < 5) return emptyList()
        val version = p.u8(0)
        var pos = if (version != 0) 8 else 4
        if (pos >= p.size) return emptyList()
        val count = p.u8(pos)
        pos++
        val marks = mutableListOf<ChapterMark>()
        repeat(count) {
            if (pos + 9 > p.size) return marks
            val start = p.u64(pos) / 10_000
            val len = p.u8(pos + 8)
            if (pos + 9 + len > p.size) return marks
            val title = TextDecoding.clean(TextDecoding.decodeUtf8Lenient(p, pos + 9, len))
            marks += ChapterMark(start, title)
            pos += 9 + len
        }
        return marks
    }

    /** QuickTime chapter track referenced by the audio track's `tref/chap`. */
    private fun readQuickTimeChapters(doc: Mp4Document, audioTrack: Mp4Node, source: RandomAccessSource): List<ChapterMark> {
        val refs = Mp4Boxes.chapterTrackRefs(audioTrack)
        if (refs.isEmpty()) return emptyList()
        val chapterTrack = doc.tracks.firstOrNull { Mp4Boxes.trackId(it) in refs } ?: return emptyList()
        val timescale = Mp4Boxes.mediaHeader(chapterTrack)?.timescale?.takeIf { it > 0 } ?: return emptyList()
        val stbl = chapterTrack.find("mdia/minf/stbl") ?: return emptyList()
        val table = SampleTable.read(stbl)
        val marks = ArrayList<ChapterMark>(table.count)
        for (i in 0 until table.count) {
            val size = table.sampleSizes[i]
            val offset = table.sampleOffsets[i]
            var title: String? = null
            if (size >= 2 && offset >= 0 && offset + size <= source.size) {
                val sample = source.readFully(offset, minOf(size, 4096))
                val textLength = minOf(sample.u16(0), sample.size - 2)
                title = decodeChapterText(sample, 2, textLength)
            }
            marks += ChapterMark(table.sampleStartTimes[i] * 1000 / timescale, title)
        }
        return marks
    }

    private fun decodeChapterText(b: ByteArray, offset: Int, length: Int): String? {
        if (length <= 0) return null
        val text = if (length >= 2 && b.u8(offset) == 0xFE && b.u8(offset + 1) == 0xFF) {
            String(b, offset + 2, length - 2, Charsets.UTF_16BE)
        } else if (length >= 2 && b.u8(offset) == 0xFF && b.u8(offset + 1) == 0xFE) {
            String(b, offset + 2, length - 2, Charsets.UTF_16LE)
        } else {
            TextDecoding.decodeUtf8Lenient(b, offset, length)
        }
        return TextDecoding.clean(text)
    }
}

/** Interprets an iTunes-style `ilst` box. */
internal class IlstReader(private val ilst: Mp4Node?) {

    private fun items(): List<Mp4Node> = ilst?.children.orEmpty()

    private data class DataAtom(val type: Int, val value: ByteArray)

    private fun dataOf(item: Mp4Node): DataAtom? {
        val data = item.child("data")?.payload ?: return null
        if (data.size < 8) return null
        return DataAtom(data.u32(0).toInt() and 0xFFFFFF, data.copyOfRange(8, data.size))
    }

    private fun text(atom: DataAtom): String? = TextDecoding.clean(
        when (atom.type) {
            2 -> String(atom.value, Charsets.UTF_16BE)
            21, 22 -> integer(atom)?.toString()
            else -> TextDecoding.decodeUtf8Lenient(atom.value)
        },
    )

    private fun integer(atom: DataAtom): Long? {
        val v = atom.value
        return when (v.size) {
            1 -> v.u8(0).toLong()
            2 -> v.u16(0).toLong()
            3 -> ((v.u8(0) shl 16) or v.u16(1)).toLong()
            4 -> v.u32(0)
            8 -> v.u64(0)
            else -> null
        }
    }

    private fun stringFor(type: String): String? =
        items().firstOrNull { it.type == type }?.let { dataOf(it) }?.let { text(it) }

    fun tags(): AudioTags {
        val custom = LinkedHashMap<String, String>()
        for (item in items().filter { it.type == "----" }) {
            val mean = item.child("mean")?.payload?.let { String(it, 4, it.size - 4, Charsets.UTF_8) } ?: continue
            val name = item.child("name")?.payload?.let { String(it, 4, it.size - 4, Charsets.UTF_8) } ?: continue
            val value = dataOf(item)?.let { text(it) } ?: continue
            val key = if (mean == ParsedAudioFile.BOOKSOUND_NAMESPACE) "BOOKSOUND:${name.uppercase()}" else name.uppercase()
            custom.putIfAbsent(key, value)
        }
        val (track, trackTotal) = pair("trkn")
        val (disc, discTotal) = pair("disk")
        return AudioTags(
            title = stringFor("©nam"),
            album = stringFor("©alb"),
            artist = stringFor("©ART"),
            albumArtist = stringFor("aART"),
            composer = stringFor("©wrt"),
            narrator = stringFor("©nrt") ?: custom["NARRATOR"] ?: custom["NARRATEDBY"] ?: custom["READER"],
            series = stringFor("©mvn") ?: custom["SERIES"] ?: custom["MVNM"],
            seriesPart = stringFor("©mvi") ?: custom["SERIES-PART"] ?: custom["SERIES_PART"] ?: custom["SERIESPART"] ?: custom["MVIN"],
            grouping = stringFor("©grp"),
            genre = stringFor("©gen"),
            year = stringFor("©day")?.let { Regex("""\d{4}""").find(it)?.value ?: it },
            comment = stringFor("©cmt"),
            description = stringFor("ldes") ?: stringFor("desc"),
            language = custom["LANGUAGE"],
            trackNumber = track,
            trackTotal = trackTotal,
            discNumber = disc,
            discTotal = discTotal,
            custom = custom,
        )
    }

    private fun pair(type: String): Pair<Int?, Int?> {
        val v = items().firstOrNull { it.type == type }?.let { dataOf(it) }?.value ?: return null to null
        if (v.size < 6) return null to null
        return v.u16(2).takeIf { it > 0 } to v.u16(4).takeIf { it > 0 }
    }

    fun cover(): EmbeddedPicture? {
        val covr = items().firstOrNull { it.type == "covr" } ?: return null
        for (data in covr.childrenOf("data")) {
            val p = data.payload ?: continue
            if (p.size <= 8) continue
            val bytes = p.copyOfRange(8, p.size)
            val mime = EmbeddedPicture.sniffMimeType(bytes) ?: when (p.u32(0).toInt() and 0xFFFFFF) {
                13 -> "image/jpeg"
                14 -> "image/png"
                else -> null
            } ?: continue
            return EmbeddedPicture(bytes, mime)
        }
        return null
    }
}
