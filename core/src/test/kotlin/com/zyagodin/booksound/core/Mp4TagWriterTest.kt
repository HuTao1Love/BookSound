package com.zyagodin.booksound.core

import com.zyagodin.booksound.core.io.ByteArraySource
import com.zyagodin.booksound.core.io.StreamSink
import com.zyagodin.booksound.core.io.readFully
import com.zyagodin.booksound.core.metadata.AudioContainer
import com.zyagodin.booksound.core.metadata.AudioProbe
import com.zyagodin.booksound.core.metadata.CorruptedFileException
import com.zyagodin.booksound.core.metadata.mp4.Mp4Reader
import com.zyagodin.booksound.core.metadata.mp4.Mp4TagSpec
import com.zyagodin.booksound.core.metadata.mp4.Mp4TagWriter
import com.zyagodin.booksound.core.metadata.mp4.SampleTable
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.core.model.EmbeddedPicture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class Mp4TagWriterTest {

    private val spec = Mp4TagSpec(
        bookId = "0b0c3c1e-1111-4222-8333-944455556666",
        metadata = BookMetadata(
            title = "Ночной дозор",
            author = "Сергей Лукьяненко",
            narrator = "Kirill Radtsig",
            series = "Дозоры",
            seriesIndex = "1",
            year = "1998",
            description = "Первая книга цикла 🌙",
        ),
        chapters = listOf(
            Chapter(0, "Пролог", 0, 300),
            Chapter(1, "Судьба не для тебя", 300, 600),
            Chapter(2, "Chapter 3 — эпилог", 600, 928),
        ),
        cover = EmbeddedPicture(TestMedia.TINY_JPEG, "image/jpeg"),
    )

    private fun rewrite(input: ByteArray, s: Mp4TagSpec = spec): ByteArray {
        val out = ByteArrayOutputStream()
        val written = Mp4TagWriter.write(ByteArraySource(input), StreamSink(out), s)
        assertEquals(written, out.size().toLong())
        return out.toByteArray()
    }

    private fun samplesOf(file: ByteArray): List<ByteArray> {
        val source = ByteArraySource(file)
        val doc = Mp4Reader.load(source)
        val table = SampleTable.read(doc.audioTrack()!!.find("mdia/minf/stbl")!!)
        return (0 until table.count).map { source.readFully(table.sampleOffsets[it], table.sampleSizes[it]) }
    }

    @Test
    fun `round trip preserves media and writes metadata`() {
        for (moovFirst in listOf(false, true)) {
            val input = TestMedia.aacMp4(moovFirst = moovFirst)
            val output = rewrite(input)

            val parsed = AudioProbe.probe(ByteArraySource(output), "book.m4b")
            assertEquals(AudioContainer.MP4, parsed.container)
            assertEquals("aac", parsed.stream?.codec)
            assertEquals("Ночной дозор", parsed.tags.title)
            assertEquals("Сергей Лукьяненко", parsed.tags.artist)
            assertEquals("Kirill Radtsig", parsed.tags.narrator)
            assertEquals("Дозоры", parsed.tags.series)
            assertEquals("1", parsed.tags.seriesPart)
            assertEquals("1998", parsed.tags.year)
            assertEquals("Первая книга цикла 🌙", parsed.tags.description)
            assertEquals(spec.bookId, parsed.bookId)
            assertArrayEquals(TestMedia.TINY_JPEG, parsed.cover!!.bytes)
            assertEquals(listOf(0L, 300L, 600L), parsed.chapters.map { it.startMs })
            assertEquals(listOf("Пролог", "Судьба не для тебя", "Chapter 3 — эпилог"), parsed.chapters.map { it.title })
            assertTrue(parsed.rewritable)

            // Every audio sample must still be readable at its relocated offset with identical bytes.
            val before = samplesOf(input)
            val after = samplesOf(output)
            assertEquals(before.size, after.size)
            before.indices.forEach { assertArrayEquals(before[it], after[it]) }

            // Output is "fast start": moov precedes the media data.
            val boxes = Mp4Reader.scanTopLevel(ByteArraySource(output)).map { it.type }
            assertEquals(listOf("ftyp", "moov", "mdat", "mdat"), boxes)
        }
    }

    @Test
    fun `rewriting twice replaces chapters instead of accumulating tracks`() {
        val once = rewrite(TestMedia.aacMp4())
        val changed = spec.copy(
            metadata = spec.metadata.copy(title = "Дневной дозор", seriesIndex = "2"),
            chapters = listOf(Chapter(0, "Один", 0, 500), Chapter(1, "Два", 500, 928)),
            cover = null,
        )
        val twice = rewrite(once, changed)
        val doc = Mp4Reader.load(ByteArraySource(twice))
        assertEquals(2, doc.tracks.size) // audio + exactly one chapter track
        val parsed = AudioProbe.probe(ByteArraySource(twice), "book.m4b")
        assertEquals("Дневной дозор", parsed.tags.title)
        assertEquals("2", parsed.tags.seriesPart)
        assertEquals(listOf("Один", "Два"), parsed.chapters.map { it.title })
        assertEquals(null, parsed.cover)
        val original = samplesOf(TestMedia.aacMp4())
        val final = samplesOf(twice)
        original.indices.forEach { assertArrayEquals(original[it], final[it]) }
    }

    @Test
    fun `existing tags are read`() {
        val input = TestMedia.aacMp4(
            extraIlst = listOf(TestMedia.ilstText("©nam", "Title"), TestMedia.ilstText("©ART", "Author")),
        )
        val parsed = AudioProbe.probe(ByteArraySource(input), "x.m4a")
        assertEquals("Title", parsed.tags.title)
        assertEquals("Author", parsed.tags.artist)
        assertNotNull(parsed.durationMs)
        assertEquals(40 * 1024 * 1000L / 44100, parsed.durationMs)
    }

    @Test(expected = CorruptedFileException::class)
    fun `truncated file is reported as corrupted`() {
        val input = TestMedia.aacMp4(moovFirst = false)
        AudioProbe.probe(ByteArraySource(input.copyOf(input.size - 100)), "broken.m4b")
    }

    @Test
    fun `more than 255 chapters still produce a chapter track`() {
        val many = (0 until 300).map { Chapter(it, "Ch $it", it * 3L, it * 3L + 3) }
        val output = rewrite(TestMedia.aacMp4(), spec.copy(chapters = many))
        val parsed = AudioProbe.probe(ByteArraySource(output), "book.m4b")
        assertEquals(300, parsed.chapters.size)
        assertEquals("Ch 299", parsed.chapters.last().title)
    }
}
