package com.zyagodin.booksound.core

import com.zyagodin.booksound.core.io.ByteArraySource
import com.zyagodin.booksound.core.library.ContentId
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.core.organize.ImportChecks
import com.zyagodin.booksound.core.organize.SourceAnalysis
import com.zyagodin.booksound.core.organize.SourceAnalyzer
import com.zyagodin.booksound.core.organize.SourceCandidate
import com.zyagodin.booksound.core.organize.SkipReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

class ImportChecksTest {

    @Test
    fun `decoded duration within tolerance`() {
        assertNull(ImportChecks.decodedDurationProblem(3_595_000, 3_600_000))
        assertNull(ImportChecks.decodedDurationProblem(null, 0))
        assertNotNull(ImportChecks.decodedDurationProblem(3_000_000, 3_600_000))
        assertNotNull(ImportChecks.decodedDurationProblem(null, 60_000))
    }

    @Test
    fun `chapters stretch to the real duration`() {
        val chapters = listOf(Chapter(0, "a", 0, 50_000), Chapter(1, "b", 50_000, 100_000))
        val aligned = ImportChecks.alignChapters(chapters, 110_000, 100_000)
        assertEquals(55_000, aligned[1].startMs)
        assertEquals(55_000, aligned[0].endMs)
        assertEquals(110_000, aligned[1].endMs)
    }

    @Test
    fun `content id is a name-based uuid of the head digest and size`() {
        val bytes = ByteArray(10_000) { (it % 251).toByte() }
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(bytes)
            update(bytes.size.toString().toByteArray())
        }.digest()
        assertEquals(UUID.nameUUIDFromBytes(digest).toString(), ContentId.of(ByteArraySource(bytes)))
    }

    @Test
    fun `analysis skips unreadable files and reports nothing readable`() {
        val audio = listOf(SourceCandidate("1", "a.mp3", emptyList(), 3), SourceCandidate("2", "b.flac", emptyList(), 3))
        val result = SourceAnalyzer.analyze(audio, emptyList(), "Book", open = { ByteArraySource(byteArrayOf(1, 2, 3)) }, readImage = { _, _ -> null })
        assertTrue(result is SourceAnalysis.NothingReadable)
        val skipped = (result as SourceAnalysis.NothingReadable).skipped
        assertEquals(listOf(SkipReason.CORRUPTED, SkipReason.UNSUPPORTED), skipped.map { it.reason })
    }

    @Test
    fun `analysis without audio`() {
        assertEquals(SourceAnalysis.NoAudio, SourceAnalyzer.analyze(emptyList(), emptyList(), "x", { error("") }, { _, _ -> null }))
    }
}
