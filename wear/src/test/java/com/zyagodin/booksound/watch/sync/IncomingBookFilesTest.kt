package com.zyagodin.booksound.watch.sync

import com.zyagodin.booksound.core.model.BookId
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.wear.WatchBookHeader
import com.zyagodin.booksound.core.wear.WatchCodec
import com.zyagodin.booksound.core.wear.WatchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class IncomingBookFilesTest {
    @get:Rule val folder = TemporaryFolder()
    private val id = "book"
    private fun prepare(files: IncomingBookFiles, received: Int, closed: Boolean = true) {
        val header = WatchBookHeader(BookId(id), BookMetadata("Book"), 1, emptyList(), 100, 1, null, null)
        files.header(id).writeBytes(WatchCodec.encodeHeader(header))
        files.part(id).writeBytes(ByteArray(received))
        if (closed) files.done(id).createNewFile()
    }
    private fun assertRemoved(files: IncomingBookFiles) {
        assertFalse(files.header(id).exists())
        assertFalse(files.part(id).exists())
        assertFalse(files.done(id).exists())
    }

    @Test fun `a normally closed but truncated transfer is removed`() = runBlocking {
        val files = IncomingBookFiles(folder.root)
        prepare(files, received = 9)
        val result = files.finish(id) { _, _ -> fail("Incomplete audio must not be installed") }
        assertEquals(WatchResult.Failed("size"), result)
        assertRemoved(files)
    }

    @Test fun `an open transfer is retained until its close callback`() = runBlocking {
        val files = IncomingBookFiles(folder.root)
        prepare(files, received = 9, closed = false)
        assertNull(files.finish(id) { _, _ -> fail("Still receiving") })
        assertTrue(files.header(id).exists())
        assertEquals(9, files.part(id).length().toInt())
    }

    @Test fun `failed installation removes the pending row`() = runBlocking {
        val files = IncomingBookFiles(folder.root)
        prepare(files, received = 100)
        assertEquals(WatchResult.Failed("IOException"), files.finish(id) { _, _ -> throw IOException("disk") })
        assertRemoved(files)
    }

    @Test fun `successful installation retains the completed book`() = runBlocking {
        val files = IncomingBookFiles(folder.root)
        prepare(files, received = 100)
        val installed = folder.newFolder("library").resolve("book.m4b")
        assertEquals(WatchResult.Saved, files.finish(id) { _, audio -> assertTrue(audio.renameTo(installed)) })
        assertEquals(100L, installed.length())
        assertRemoved(files)
    }

    @Test fun `cancel removes only this phones temporary transfer`() {
        val files = IncomingBookFiles(folder.root)
        prepare(files, received = 9)
        val server = folder.newFile("book.server").apply { writeText("server download") }
        val other = folder.newFile("other.part").apply { writeText("another book") }
        files.discard(id)
        assertRemoved(files)
        assertEquals("server download", server.readText())
        assertEquals("another book", other.readText())
    }
}
