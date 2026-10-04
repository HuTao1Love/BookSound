package com.zyagodin.booksound.torrent

import com.zyagodin.booksound.core.torrent.AudiobookLayout
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The torrent index must survive app updates and restarts unchanged. */
class TorrentRecordTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private fun record() = TorrentRecord(
        id = "t1",
        name = "Author - Book [2020, MP3]",
        infoHash = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a",
        magnetUri = "magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a",
        addedAt = 1_700_000_000_000,
        phase = TorrentPhase.DOWNLOADING,
        dataDir = "/data/t1",
        reviewed = true,
        layout = AudiobookLayout.MP3_PARTS,
        files = listOf(StoredFile(0, "Book/01.mp3", 1000), StoredFile(1, "Book/cover.jpg", 10), StoredFile(2, "Book/info.nfo", 5)),
        wanted = setOf(0, 1),
        suggested = StoredMetadata(title = "Book", author = "Author", year = "2020"),
        edited = StoredMetadata(title = "Book", author = "The Author", year = "2020"),
        parts = listOf(StoredPart(0, "Book/01.mp3", "Intro", "01", 1000)),
        bookId = "b1",
        failure = TorrentFailure(TorrentFailureCode.FILES_MISSING, files = listOf("Book/01.mp3")),
        progress = 0.5f,
    )

    @Test
    fun `records round-trip through json`() {
        val original = listOf(record())
        val restored = json.decodeFromString<List<TorrentRecord>>(json.encodeToString(original))
        assertEquals(original, restored)
    }

    @Test
    fun `books of one torrent share its download`() {
        val book = record().copy(id = "t2", group = "g1", volume = "Книга 2", position = 1)
        assertEquals(book, json.decodeFromString<List<TorrentRecord>>(json.encodeToString(listOf(book))).single())
        assertEquals("g1", book.downloadKey)
        // Records stored before collections existed download on their own.
        assertEquals("t1", record().downloadKey)
    }

    @Test
    fun `unknown fields from newer versions are ignored`() {
        val text = json.encodeToString(listOf(record())).replaceFirst("{", "{\"future\":42,")
        assertEquals("t1", json.decodeFromString<List<TorrentRecord>>(text).single().id)
    }

    @Test
    fun `derived values`() {
        val r = record()
        assertEquals("Book", r.title)
        assertEquals("The Author", r.author)
        assertEquals(1010L, r.wantedBytes)
        assertTrue(r.needsEngine)
        assertFalse(r.copy(paused = true).needsEngine)
        assertTrue(r.failure!!.retryable)
        assertFalse(TorrentFailure(TorrentFailureCode.CONTENT_INVALID).retryable)

        val review = r.review()!!
        assertEquals("Author", review.suggested.author)
        assertEquals("The Author", review.edited.author)
        assertEquals("Intro", review.parts.single().title)
        assertEquals("01", review.parts.single().suggestedTitle)
        assertNull(r.copy(suggested = null).review())
    }
}
