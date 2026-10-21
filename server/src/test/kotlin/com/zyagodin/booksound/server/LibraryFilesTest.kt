package com.zyagodin.booksound.server

import com.zyagodin.booksound.server.library.LibraryFiles
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class LibraryFilesTest {

    @Test
    fun `a name taken while converting gets a number, the book's own file does not`() {
        val root = Files.createTempDirectory("library").toFile()
        try {
            val files = LibraryFiles(root)
            val dir = root.resolve("Author").apply { mkdirs() }
            val wanted = dir.resolve("Title.m4b")
            assertEquals(wanted, files.stillFree(wanted, own = null))
            wanted.writeText("another book")
            assertEquals(dir.resolve("Title (2).m4b"), files.stillFree(wanted, own = null))
            assertEquals(wanted, files.stillFree(wanted, own = wanted))
        } finally {
            root.deleteRecursively()
        }
    }
}
