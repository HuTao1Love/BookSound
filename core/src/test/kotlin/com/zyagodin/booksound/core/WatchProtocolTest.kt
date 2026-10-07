package com.zyagodin.booksound.core

import com.zyagodin.booksound.core.model.BookId
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.core.model.DeviceId
import com.zyagodin.booksound.core.model.SyncStamp
import com.zyagodin.booksound.core.sync.PlaybackRecord
import com.zyagodin.booksound.core.wear.WatchBookHeader
import com.zyagodin.booksound.core.wear.WatchCodec
import com.zyagodin.booksound.core.wear.WatchPaths
import com.zyagodin.booksound.core.wear.WatchResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class WatchProtocolTest {

    private val record = PlaybackRecord(
        BookId("b1"), positionMs = 3_723_000, speed = 1.25f, finished = false, lastPlayedAt = 1_700_000_000_000,
        stamp = SyncStamp(7, 1_700_000_000_500, DeviceId("phone"), dirty = false),
    )

    @Test
    fun `header survives a round trip`() {
        val header = WatchBookHeader(
            id = BookId("b1"),
            metadata = BookMetadata("Мастер и Маргарита", author = "Булгаков", series = "Цикл", seriesIndex = "2.5", description = "д".repeat(70_000)),
            durationMs = 36_000_000,
            chapters = listOf(Chapter(0, "Глава 1", 0, 1_000), Chapter(1, "", 1_000, 36_000_000)),
            fileSize = 512_000_000,
            fileRevision = 3,
            playback = record,
            cover = byteArrayOf(1, 2, 3),
        )
        val decoded = WatchCodec.decodeHeader(WatchCodec.encodeHeader(header))
        assertEquals(header, decoded)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.cover)
    }

    @Test
    fun `header without playback and cover`() {
        val header = WatchBookHeader(BookId("x"), BookMetadata("T"), 1, emptyList(), 2, 1, null, null)
        val decoded = WatchCodec.decodeHeader(WatchCodec.encodeHeader(header))
        assertEquals(header, decoded)
        assertNull(decoded.cover)
    }

    @Test
    fun `position survives a round trip`() {
        assertEquals(record, WatchCodec.decodePosition(WatchCodec.encodePosition(record)))
        val neverPlayed = record.copy(lastPlayedAt = null, finished = true)
        assertEquals(neverPlayed, WatchCodec.decodePosition(WatchCodec.encodePosition(neverPlayed)))
    }

    @Test(expected = IOException::class)
    fun `a position is not read as a header`() {
        WatchCodec.decodeHeader(WatchCodec.encodePosition(record))
    }

    @Test
    fun `book id from a path`() {
        assertEquals(BookId("abc"), WatchPaths.bookId("/booksound/book/abc", WatchPaths.BOOK_PREFIX))
        assertNull(WatchPaths.bookId("/booksound/book/", WatchPaths.BOOK_PREFIX))
        assertNull(WatchPaths.bookId("/booksound/book/a/b", WatchPaths.BOOK_PREFIX))
        assertNull(WatchPaths.bookId("/booksound/header/abc", WatchPaths.BOOK_PREFIX))
    }

    @Test
    fun `results round trip`() {
        listOf(WatchResult.Saved, WatchResult.NoSpace, WatchResult.Failed("size")).forEach {
            assertEquals(it, WatchResult.decode(it.encode()))
        }
    }
}
