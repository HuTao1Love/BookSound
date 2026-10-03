package com.zyagodin.booksound.core

import com.zyagodin.booksound.core.metadata.AudioContainer
import com.zyagodin.booksound.core.metadata.AudioTags
import com.zyagodin.booksound.core.metadata.ParsedAudioFile
import com.zyagodin.booksound.core.model.ChapterMark
import com.zyagodin.booksound.core.organize.ChapterPlanner
import com.zyagodin.booksound.core.organize.DraftPart
import com.zyagodin.booksound.core.organize.ImportDraftBuilder
import com.zyagodin.booksound.core.organize.ImportSourceFile
import com.zyagodin.booksound.core.organize.NamePatternParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrganizeTest {

    private fun file(name: String, tags: AudioTags = AudioTags(), duration: Long = 1000, chapters: List<ChapterMark> = emptyList()) =
        ImportSourceFile(
            id = name,
            displayName = name,
            relativeDir = emptyList(),
            sizeBytes = 1,
            parsed = ParsedAudioFile(AudioContainer.MP3, null, duration, tags, chapters, null),
        )

    @Test
    fun `folder name patterns`() {
        val g = NamePatternParser.parse("Сергей Лукьяненко - Дозоры 01 - Ночной дозор (Читает Кузнецов) [2014]")
        assertEquals("Сергей Лукьяненко", g.author)
        assertEquals("Дозоры", g.series)
        assertEquals("1", g.seriesIndex)
        assertEquals("Ночной дозор", g.title)
        assertEquals("Кузнецов", g.narrator)
        assertEquals("2014", g.year)

        val two = NamePatternParser.parse("Frank Herbert - Dune [128 kbps]")
        assertEquals("Frank Herbert", two.author)
        assertEquals("Dune", two.title)

        val bracketed = NamePatternParser.parse("[Discworld 7] Pyramids")
        assertEquals("Discworld", bracketed.series)
        assertEquals("7", bracketed.seriesIndex)
        assertEquals("Pyramids", bracketed.title)

        val plain = NamePatternParser.parse("The Hobbit.m4b")
        assertEquals("The Hobbit", plain.title)
        assertNull(plain.author)
    }

    @Test
    fun `draft from mp3 folder uses tags and natural order`() {
        val tags = { t: String, n: Int -> AudioTags(title = t, album = "Азазель", artist = "Борис Акунин", trackNumber = n, composer = "Сергей Чонишвили") }
        val files = listOf(
            file("10.mp3", tags("Глава 10", 10)),
            file("2.mp3", tags("Глава 2", 2)),
            file("1.mp3", tags("Глава 1", 1)),
        )
        val draft = ImportDraftBuilder.build("Акунин - Азазель", files)
        assertEquals("Азазель", draft.metadata.title)
        assertEquals("Борис Акунин", draft.metadata.author)
        assertEquals("Сергей Чонишвили", draft.metadata.narrator)
        assertEquals(listOf("1.mp3", "2.mp3", "10.mp3"), draft.parts.map { it.displayName })
        assertEquals(listOf("Глава 1", "Глава 2", "Глава 10"), draft.parts.map { it.title })
    }

    @Test
    fun `untagged parts fall back to file names and numbering`() {
        val files = listOf(file("Book - 01.mp3"), file("Book - 02.mp3"))
        val draft = ImportDraftBuilder.build("Author - Book", files) { "Chapter $it" }
        assertEquals("Book", draft.metadata.title)
        assertEquals("Author", draft.metadata.author)
        assertEquals(listOf("Chapter 1", "Chapter 2"), draft.parts.map { it.title })

        val named = ImportDraftBuilder.build("X", listOf(file("01 Intro.mp3"), file("02 The End.mp3")))
        assertEquals(listOf("01 Intro", "02 The End"), named.parts.map { it.title })
    }

    @Test
    fun `chapter planning combines parts and embedded chapters`() {
        val parts = listOf(
            DraftPart("a", "a.m4b", "Part A", 10_000, listOf(ChapterMark(0, "A1"), ChapterMark(4_000, "A2"))),
            DraftPart("b", "b.mp3", "Part B", 5_000, emptyList()),
        )
        val chapters = ChapterPlanner.plan(parts)
        assertEquals(listOf("A1", "A2", "Part B"), chapters.map { it.title })
        assertEquals(listOf(0L, 4_000L, 10_000L), chapters.map { it.startMs })
        assertEquals(listOf(4_000L, 10_000L, 15_000L), chapters.map { it.endMs })
        assertEquals(listOf(0, 1, 2), chapters.map { it.index })
    }
}
