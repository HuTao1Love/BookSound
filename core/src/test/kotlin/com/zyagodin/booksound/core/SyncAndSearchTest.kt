package com.zyagodin.booksound.core

import com.zyagodin.booksound.core.library.LibraryQuery
import com.zyagodin.booksound.core.library.LibrarySearch
import com.zyagodin.booksound.core.library.ProgressFilter
import com.zyagodin.booksound.core.library.SortField
import com.zyagodin.booksound.core.model.Audiobook
import com.zyagodin.booksound.core.model.BookFile
import com.zyagodin.booksound.core.model.BookId
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.DeviceId
import com.zyagodin.booksound.core.model.LibraryEntry
import com.zyagodin.booksound.core.model.SyncStamp
import com.zyagodin.booksound.core.sync.PlaybackConflictResolver
import com.zyagodin.booksound.core.sync.PlaybackRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SyncAndSearchTest {

    private val phone = DeviceId("phone")
    private val tablet = DeviceId("tablet")
    private val book = BookId("b")

    private fun record(position: Long, revision: Long, updatedAt: Long, device: DeviceId, finished: Boolean = false) =
        PlaybackRecord(book, position, 1f, finished, updatedAt, SyncStamp(revision, updatedAt, device))

    @Test
    fun `only one side changed`() {
        val base = record(1_000, 1, 0, phone)
        val local = record(1_000, 1, 0, phone)
        val remote = record(90_000, 2, 10_000_000, tablet)
        val r = PlaybackConflictResolver().resolve(local, remote, base)
        assertEquals(90_000, r.winner.positionMs)
        assertNull(r.alternative)
    }

    @Test
    fun `both changed - latest wins and loser is offered`() {
        val base = record(1_000, 1, 0, phone)
        val local = record(500_000, 2, 1_000_000, phone)
        val remote = record(200_000, 2, 9_000_000, tablet)
        val r = PlaybackConflictResolver().resolve(local, remote, base)
        assertEquals(200_000, r.winner.positionMs)
        assertNotNull(r.alternative)
        assertEquals(500_000, r.alternative!!.positionMs)
    }

    @Test
    fun `both changed within clock skew - furthest wins`() {
        val base = record(1_000, 1, 0, phone)
        val local = record(500_000, 2, 5_000_000, phone)
        val remote = record(200_000, 2, 5_030_000, tablet)
        assertEquals(500_000, PlaybackConflictResolver().resolve(local, remote, base).winner.positionMs)
    }

    private fun entry(title: String, author: String?, added: Long, played: Long?, position: Long = 0, finished: Boolean = false, series: String? = null, index: String? = null) =
        LibraryEntry(
            Audiobook(
                BookId(title), BookMetadata(title, author, series = series, seriesIndex = index), 100_000, emptyList(),
                BookFile("x", 1, null, 1), added, SyncStamp(1, 0, phone),
            ),
            position, finished, played,
        )

    @Test
    fun `search is accent and case insensitive across fields`() {
        val all = listOf(entry("Ёлка", "Иванов", 1, null), entry("Café Society", "Smith", 2, null), entry("Other", "Ann", 3, null))
        assertEquals(listOf("Ёлка"), LibrarySearch.apply(all, LibraryQuery("елка")).map { it.book.metadata.title })
        assertEquals(listOf("Café Society"), LibrarySearch.apply(all, LibraryQuery("cafe smi")).map { it.book.metadata.title })
        assertEquals(listOf("Ёлка"), LibrarySearch.apply(all, LibraryQuery("ИВАН")).map { it.book.metadata.title })
    }

    @Test
    fun `grouping by series puts standalone books first and orders by number`() {
        val all = listOf(
            entry("Book 10", "Amy", 4, null, series = "Saga", index = "10"),
            entry("Solo", "Bob", 1, null),
            entry("Book 2", "Amy", 5, null, series = "saga", index = "2"),
            entry("Other 1", "Cid", 6, null, series = "Other", index = "1"),
        )
        val groups = LibrarySearch.groupBySeries(all)
        assertEquals(listOf(null, "Saga", "Other"), groups.map { it.series })
        assertEquals(listOf("Solo"), groups[0].entries.map { it.book.metadata.title })
        assertEquals(listOf("Book 2", "Book 10"), groups[1].entries.map { it.book.metadata.title })
    }

    @Test
    fun `one series is found ignoring case and ordered by number`() {
        val all = listOf(
            entry("Book 10", "Amy", 4, null, series = "Saga", index = "10"),
            entry("Solo", "Bob", 1, null),
            entry("Book 2", "Amy", 5, null, series = "saga", index = "2"),
            entry("Other 1", "Cid", 6, null, series = "Other", index = "1"),
        )
        val saga = LibrarySearch.seriesGroup(all, "SAGA")!!
        assertEquals("Saga", saga.series)
        assertEquals(listOf("Book 2", "Book 10"), saga.entries.map { it.book.metadata.title })
        assertEquals(null, LibrarySearch.seriesGroup(all, "Missing"))
    }

    @Test
    fun `sorting and filters`() {
        val all = listOf(
            entry("B", "Zed", 1, 50, position = 10),
            entry("A", "Amy", 2, null),
            entry("C", "Amy", 3, 100, finished = true),
            entry("Book 10", "Amy", 4, null, series = "S", index = "10"),
            entry("Book 2", "Amy", 5, null, series = "S", index = "2"),
        )
        assertEquals(listOf("C", "B"), LibrarySearch.apply(all, LibraryQuery(sort = SortField.RECENT)).take(2).map { it.book.metadata.title })
        assertEquals(listOf("A", "B", "Book 2", "Book 10", "C"), LibrarySearch.apply(all, LibraryQuery(sort = SortField.TITLE)).map { it.book.metadata.title })
        assertEquals(listOf("Book 2", "Book 10"), LibrarySearch.apply(all, LibraryQuery(sort = SortField.SERIES)).take(2).map { it.book.metadata.title })
        assertEquals(listOf("B"), LibrarySearch.apply(all, LibraryQuery(filter = ProgressFilter.IN_PROGRESS)).map { it.book.metadata.title })
        assertEquals(listOf("C"), LibrarySearch.apply(all, LibraryQuery(filter = ProgressFilter.FINISHED)).map { it.book.metadata.title })
    }
}
