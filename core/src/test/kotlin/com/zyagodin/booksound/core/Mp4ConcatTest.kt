package com.zyagodin.booksound.core

import com.zyagodin.booksound.core.io.ByteArraySource
import com.zyagodin.booksound.core.io.RandomAccessSource
import com.zyagodin.booksound.core.io.StreamSink
import com.zyagodin.booksound.core.io.readFully
import com.zyagodin.booksound.core.metadata.AudioProbe
import com.zyagodin.booksound.core.metadata.CorruptedFileException
import com.zyagodin.booksound.core.metadata.mp4.Mp4Concat
import com.zyagodin.booksound.core.metadata.mp4.Mp4Reader
import com.zyagodin.booksound.core.metadata.mp4.Mp4TagSpec
import com.zyagodin.booksound.core.metadata.mp4.Mp4TagWriter
import com.zyagodin.booksound.core.metadata.mp4.SampleTable
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.Chapter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class Mp4ConcatTest {

    private fun samplesOf(source: RandomAccessSource): List<ByteArray> {
        val doc = Mp4Reader.load(source)
        val table = SampleTable.read(doc.audioTrack()!!.find("mdia/minf/stbl")!!)
        return (0 until table.count).map { source.readFully(table.sampleOffsets[it], table.sampleSizes[it]) }
    }

    private class Tracked(data: ByteArray) : RandomAccessSource by ByteArraySource(data) {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    @Test
    fun `joined parts keep every sample in order and the summed duration`() {
        val a = TestMedia.aacMp4(chunks = 4, samplesPerChunk = 10, sampleSize = 32, moovFirst = false)
        val b = TestMedia.aacMp4(chunks = 3, samplesPerChunk = 7, sampleSize = 20, moovFirst = true, extraIlst = listOf(TestMedia.ilstText("©nam", "Part 2")))
        val c = TestMedia.aacMp4(chunks = 2, samplesPerChunk = 10, sampleSize = 32)
        val expected = listOf(a, b, c).flatMap { samplesOf(ByteArraySource(it)) }

        val sources = listOf(a, b, c).map { Tracked(it) }
        val joined = Mp4Concat.join(sources)
        assertEquals(expected.size, samplesOf(joined).size)
        samplesOf(joined).forEachIndexed { i, sample -> assertArrayEquals("sample $i", expected[i], sample) }

        val parsed = AudioProbe.probe(joined, "audio.m4a")
        assertEquals("aac", parsed.stream?.codec)
        assertEquals((40 + 21 + 20) * 1024 * 1000L / 44100, parsed.durationMs)
        assertEquals(null, parsed.tags.title) // the parts' own tags are not carried over

        // The tag writer streams the joined parts into a normal fast-start file.
        val out = ByteArrayOutputStream()
        val spec = Mp4TagSpec("id", BookMetadata(title = "Joined"), listOf(Chapter(0, "One", 0, 900), Chapter(1, "Two", 900, 1800)), null)
        Mp4TagWriter.write(joined, StreamSink(out), spec)
        val written = ByteArraySource(out.toByteArray())
        val final = samplesOf(written)
        assertEquals(expected.size, final.size)
        final.forEachIndexed { i, sample -> assertArrayEquals("written sample $i", expected[i], sample) }
        assertEquals("Joined", AudioProbe.probe(written, "book.m4b").tags.title)
        assertEquals(listOf("ftyp", "moov", "mdat", "mdat"), Mp4Reader.scanTopLevel(written).map { it.type })

        joined.close()
        assertTrue(sources.all { it.closed })
    }

    @Test
    fun `a part that is not an mp4 fails and closes every part`() {
        val sources = listOf(Tracked(TestMedia.aacMp4()), Tracked(TestMedia.mp3Frames(10)))
        try {
            Mp4Concat.join(sources)
            throw AssertionError("expected a failure")
        } catch (e: CorruptedFileException) {
            // expected
        }
        assertTrue(sources.all { it.closed })
    }

    @Test
    fun `a duration beyond 32 bits switches the media header to version 1`() {
        // Two parts of 40 samples lasting 2^31 - 1 ticks each: far past what 32 bits hold.
        fun longPart(): ByteArray {
            val bytes = TestMedia.aacMp4()
            val stts = String(bytes, Charsets.ISO_8859_1).indexOf("stts")
            val delta = stts + 4 + 12
            bytes[delta] = 0x7F; bytes[delta + 1] = -1; bytes[delta + 2] = -1; bytes[delta + 3] = -1
            return bytes
        }
        val joined = Mp4Concat.join(listOf(ByteArraySource(longPart()), ByteArraySource(longPart())))
        val ticks = 80L * 0x7FFFFFFF
        assertTrue(ticks > 0xFFFFFFFFL)
        assertEquals(ticks * 1000 / 44100, AudioProbe.probe(joined, "audio.m4a").durationMs)
    }
}
