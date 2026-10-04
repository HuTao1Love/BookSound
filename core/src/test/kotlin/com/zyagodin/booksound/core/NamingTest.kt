package com.zyagodin.booksound.core

import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.naming.FileNameSanitizer
import com.zyagodin.booksound.core.naming.LibraryLayout
import com.zyagodin.booksound.core.naming.NaturalOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NamingTest {

    @Test
    fun `invalid characters are replaced`() {
        assertEquals("Title - Subtitle", FileNameSanitizer.sanitize("Title: Subtitle"))
        assertEquals("AC - DC", FileNameSanitizer.sanitize("AC/DC"))
        assertEquals("What", FileNameSanitizer.sanitize("What?*"))
        assertEquals("Say 'hi' (now)", FileNameSanitizer.sanitize("Say \"hi\" <now>"))
        assertEquals("a b", FileNameSanitizer.sanitize("a\u0000\tb"))
    }

    @Test
    fun `unicode is preserved and normalized`() {
        assertEquals("Сергей Лукьяненко", FileNameSanitizer.sanitize("Сергей  Лукьяненко "))
        assertEquals("Café 🌙", FileNameSanitizer.sanitize("Café 🌙"))
        assertEquals("東京物語", FileNameSanitizer.sanitize("東京物語"))
    }

    @Test
    fun `dots, reserved names and empties`() {
        assertEquals("Hidden", FileNameSanitizer.sanitize("...Hidden..."))
        assertEquals("_CON", FileNameSanitizer.sanitize("con").let { it!!.uppercase() })
        assertEquals("_nul.txt", FileNameSanitizer.sanitize("nul.txt"))
        assertNull(FileNameSanitizer.sanitize("  ?? ** "))
        assertNull(FileNameSanitizer.sanitize("..."))
    }

    @Test
    fun `truncation respects bytes and grapheme clusters`() {
        val long = "Я".repeat(200) // 400 bytes in UTF-8
        val result = FileNameSanitizer.sanitize(long, maxBytes = 101)!!
        assertTrue(result.toByteArray().size <= 101)
        assertEquals(50, result.length)
        val emoji = "👨‍👩‍👧".repeat(10)
        val cut = FileNameSanitizer.truncateToBytes(emoji, 30)
        assertTrue(cut.toByteArray().size <= 30)
        assertEquals(0, cut.length % "👨‍👩‍👧".length) // never splits a family emoji
    }

    @Test
    fun `layout with series and number`() {
        val path = LibraryLayout.pathFor(BookMetadata(title = "Ночной дозор", author = "Сергей Лукьяненко", series = "Дозоры", seriesIndex = "1"))
        assertEquals(listOf("Сергей Лукьяненко", "Дозоры"), path.directories)
        assertEquals("01 - Ночной дозор.m4b", path.fileName)
        val half = LibraryLayout.pathFor(BookMetadata(title = "T", author = "A", series = "S", seriesIndex = "2.5"))
        assertEquals("02.5 - T.m4b", half.fileName)
    }

    @Test
    fun `layout with missing metadata is deterministic`() {
        val noAuthor = LibraryLayout.pathFor(BookMetadata(title = "Book"))
        assertEquals(listOf(LibraryLayout.UNKNOWN_AUTHOR), noAuthor.directories)
        assertEquals("Book.m4b", noAuthor.fileName)
        val noTitle = LibraryLayout.pathFor(BookMetadata(title = "  "), fallbackTitle = "folder_name")
        assertEquals("folder_name.m4b", noTitle.fileName)
        val nothing = LibraryLayout.pathFor(BookMetadata(title = "?"))
        assertEquals("${LibraryLayout.UNTITLED}.m4b", nothing.fileName)
        // Number without series is ignored for naming.
        assertEquals("Book.m4b", LibraryLayout.pathFor(BookMetadata(title = "Book", seriesIndex = "3")).fileName)
    }

    @Test
    fun `unique names are case insensitive`() {
        val existing = listOf("Book.m4b", "book (2).M4B")
        assertEquals("BOOK (3).m4b", LibraryLayout.uniqueFileName("BOOK.m4b", existing))
        assertEquals("Other.m4b", LibraryLayout.uniqueFileName("Other.m4b", existing))
    }

    @Test
    fun `natural order`() {
        val sorted = listOf("Part 10.mp3", "part 2.mp3", "Part 1.mp3", "Part 02b.mp3", "Глава 3.mp3", "Глава 11.mp3")
            .sortedWith(NaturalOrder)
        assertEquals(listOf("Part 1.mp3", "part 2.mp3", "Part 02b.mp3", "Part 10.mp3", "Глава 3.mp3", "Глава 11.mp3"), sorted)
    }
}
