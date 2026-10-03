package com.zyagodin.booksound.core

import com.zyagodin.booksound.core.metadata.AudioContainer
import com.zyagodin.booksound.core.metadata.AudioStreamInfo
import com.zyagodin.booksound.core.metadata.AudioTags
import com.zyagodin.booksound.core.metadata.ParsedAudioFile
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.organize.DraftPart
import com.zyagodin.booksound.core.organize.ImportSourceFile
import com.zyagodin.booksound.core.torrent.AudiobookIntegrity
import com.zyagodin.booksound.core.torrent.AudiobookLayout
import com.zyagodin.booksound.core.torrent.IntegrityIssue
import com.zyagodin.booksound.core.torrent.MergedReview
import com.zyagodin.booksound.core.torrent.ReviewedPart
import com.zyagodin.booksound.core.torrent.TorrentContentCheck
import com.zyagodin.booksound.core.torrent.TorrentContentProblem
import com.zyagodin.booksound.core.torrent.TorrentContentValidator
import com.zyagodin.booksound.core.torrent.TorrentFile
import com.zyagodin.booksound.core.torrent.TorrentLink
import com.zyagodin.booksound.core.torrent.TorrentLinks
import com.zyagodin.booksound.core.torrent.TorrentReview
import com.zyagodin.booksound.core.torrent.TorrentReviewMerger
import com.zyagodin.booksound.core.torrent.TorrentSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentTest {

    private fun files(vararg entries: Pair<String, Long>) = entries.mapIndexed { i, (path, size) -> TorrentFile(i, path, size) }

    private fun valid(check: TorrentContentCheck) = check as? TorrentContentCheck.Valid ?: error("Expected valid, got $check")
    private fun collection(check: TorrentContentCheck) = check as? TorrentContentCheck.Collection ?: error("Expected a collection, got $check")
    private fun problem(check: TorrentContentCheck) = (check as? TorrentContentCheck.Invalid ?: error("Expected invalid, got $check")).problem

    // ---------------------------------------------------------------- content validation

    @Test
    fun `folder of mp3 files with cover and extras is valid`() {
        val check = valid(
            TorrentContentValidator.validate(
                files(
                    "Book/10 - End.mp3" to 1000,
                    "Book/02 - Two.mp3" to 1000,
                    "Book/01 - One.mp3" to 1000,
                    "Book/cover.jpg" to 500,
                    "Book/info.nfo" to 10,
                    "Book/playlist.m3u" to 10,
                    "Book/._01 - One.mp3" to 4096, // AppleDouble metadata, not audio
                    "Book/Thumbs.db" to 100,
                ),
            ),
        )
        assertEquals(AudiobookLayout.MP3_PARTS, check.layout)
        assertEquals(listOf("Book/01 - One.mp3", "Book/02 - Two.mp3", "Book/10 - End.mp3"), check.audio.map { it.path })
        assertEquals(listOf("Book/cover.jpg"), check.images.map { it.path })
        assertEquals(setOf(0, 1, 2, 3), check.wantedIndices)
        assertEquals(4, check.skipped.size)
    }

    @Test
    fun `mp3 parts in cd sub-folders are one book`() {
        val check = valid(TorrentContentValidator.validate(files("B/CD2/01.mp3" to 5, "B/CD1/01.mp3" to 5, "B/CD10/01.mp3" to 5)))
        assertEquals(listOf("B/CD1/01.mp3", "B/CD2/01.mp3", "B/CD10/01.mp3"), check.audio.map { it.path })
    }

    @Test
    fun `sub-folders named like discs or parts keep the torrent one book`() {
        for (names in listOf(listOf("CD1", "CD2"), listOf("Disc 1 (01-10)", "Disc 2"), listOf("Диск 1", "Диск 2"), listOf("Часть 1", "Часть 2"), listOf("01", "02"), listOf("CD1", "Bonus"))) {
            val check = TorrentContentValidator.validate(files("B/${names[0]}/01.mp3" to 5, "B/${names[1]}/01.mp3" to 5))
            assertTrue(names.toString(), check is TorrentContentCheck.Valid)
        }
        // Parts next to a sub-folder are one book too.
        valid(TorrentContentValidator.validate(files("B/01.mp3" to 5, "B/Extra/01.mp3" to 5)))
    }

    @Test
    fun `book sub-folders make a collection`() {
        val check = collection(
            TorrentContentValidator.validate(
                files(
                    "Series/Книга 2/01.mp3" to 5,
                    "Series/Книга 1/02.mp3" to 5,
                    "Series/Книга 1/01.mp3" to 5,
                    "Series/Книга 1/cover.jpg" to 5,
                    "Series/Книга 2/CD1/02.mp3" to 5,
                    "Series/folder.jpg" to 5,
                    "Series/info.txt" to 5,
                ),
            ),
        )
        assertEquals(listOf("Книга 1", "Книга 2"), check.books.map { it.volume })
        assertEquals(listOf("Series/Книга 1/01.mp3", "Series/Книга 1/02.mp3"), check.books[0].audio.map { it.path })
        assertEquals(listOf("Series/Книга 2/01.mp3", "Series/Книга 2/CD1/02.mp3"), check.books[1].audio.map { it.path })
        // A book's own cover is only its own; one outside every book folder is shared.
        assertEquals(listOf("Series/Книга 1/cover.jpg", "Series/folder.jpg"), check.books[0].images.map { it.path })
        assertEquals(listOf("Series/folder.jpg"), check.books[1].images.map { it.path })
        assertEquals(listOf("Series/info.txt"), check.skipped.map { it.path })
        assertTrue(check.books.all { it.layout == AudiobookLayout.MP3_PARTS })
    }

    @Test
    fun `several m4b files are several books`() {
        val check = collection(TorrentContentValidator.validate(files("s/2 - Second.m4b" to 5, "s/1 - First.m4b" to 5, "s/1 - First.jpg" to 5, "s/all.png" to 5)))
        assertEquals(listOf("1 - First", "2 - Second"), check.books.map { it.volume })
        assertTrue(check.books.all { it.layout == AudiobookLayout.SINGLE_M4B && it.audio.size == 1 })
        assertEquals(listOf("s/1 - First.jpg", "s/all.png"), check.books[0].images.map { it.path })
        assertEquals(listOf("s/all.png"), check.books[1].images.map { it.path })
        // In book folders as well, also mixed with folders of MP3 files.
        val mixed = collection(TorrentContentValidator.validate(files("s/Book 1/a.m4b" to 5, "s/Book 2/01.mp3" to 5, "s/Book 2/02.mp3" to 5)))
        assertEquals(listOf(AudiobookLayout.SINGLE_M4B, AudiobookLayout.MP3_PARTS), mixed.books.map { it.layout })
    }

    @Test
    fun `single m4b with image is valid`() {
        val check = valid(TorrentContentValidator.validate(files("Dune.m4b" to 10_000)))
        assertEquals(AudiobookLayout.SINGLE_M4B, check.layout)
        val withCover = valid(TorrentContentValidator.validate(files("Dune/Dune.m4b" to 10_000, "Dune/folder.png" to 50)))
        assertEquals(1, withCover.images.size)
    }

    @Test
    fun `padding files are ignored`() {
        val list = listOf(TorrentFile(0, "B/01.mp3", 10), TorrentFile(1, "B/.pad/1234", 3, isPadding = true), TorrentFile(2, ".pad/99", 1))
        val check = valid(TorrentContentValidator.validate(list))
        assertEquals(setOf(0), check.wantedIndices)
    }

    @Test
    fun `invalid layouts are rejected with a reason`() {
        assertEquals(TorrentContentProblem.EMPTY, problem(TorrentContentValidator.validate(emptyList())))
        assertEquals(TorrentContentProblem.NO_AUDIO, problem(TorrentContentValidator.validate(files("a/readme.txt" to 1, "a/cover.jpg" to 5))))
        assertEquals(TorrentContentProblem.MIXED_FORMATS, problem(TorrentContentValidator.validate(files("a/1.mp3" to 1, "a/book.m4b" to 5))))
        assertEquals(TorrentContentProblem.MULTIPLE_M4B, problem(TorrentContentValidator.validate(files("s/CD1/a.m4b" to 1, "s/CD2/b.m4b" to 5))))
        assertEquals(
            TorrentContentProblem.MIXED_FORMATS,
            problem(TorrentContentValidator.validate(files("s/Book 1/1.mp3" to 1, "s/Book 2/1.mp3" to 1, "s/Book 2/b.m4b" to 5))),
        )
        assertEquals(TorrentContentProblem.EMPTY_AUDIO_FILE, problem(TorrentContentValidator.validate(files("a/1.mp3" to 0, "a/2.mp3" to 5))))
        assertEquals(TorrentContentProblem.UNSAFE_PATH, problem(TorrentContentValidator.validate(files("a/../../evil.mp3" to 5))))
    }

    @Test
    fun `unexpected files reject the whole torrent`() {
        for (foreign in listOf("a/setup.exe", "a/bonus.flac", "a/parts.zip", "a/movie.mkv", "a/part.m4a", "a/unknown.xyz")) {
            val check = TorrentContentValidator.validate(files("a/01.mp3" to 10, foreign to 10))
            assertEquals(foreign, TorrentContentProblem.UNSUPPORTED_FILES, problem(check))
            assertEquals(listOf(foreign), (check as TorrentContentCheck.Invalid).files)
        }
    }

    @Test
    fun `oversized images are not downloaded`() {
        val check = valid(TorrentContentValidator.validate(files("a/01.mp3" to 10, "a/scan.jpg" to TorrentContentValidator.MAX_IMAGE_BYTES + 1)))
        assertTrue(check.images.isEmpty())
        assertEquals(listOf("a/scan.jpg"), check.skipped.map { it.path })
    }

    // ---------------------------------------------------------------- links

    @Test
    fun `magnet links are parsed`() {
        val hex = "c12fe1c06bba254a9dc9f519b335aa7c1367a88a"
        val m = TorrentLinks.parse("  magnet:?xt=urn:btih:${hex.uppercase()}&dn=Frank+Herbert+-+Dune&tr=udp%3A%2F%2Ftracker.example%3A80  ") as TorrentLink.Magnet
        assertEquals(hex, m.infoHash)
        assertEquals("Frank Herbert - Dune", m.displayName)

        // Base32 form of the same hash.
        val b32 = TorrentLinks.parse("magnet:?xt=urn:btih:YEX6DQDLXISUVHOJ6UM3GNNKPQJWPKEK") as TorrentLink.Magnet
        assertEquals(hex, b32.infoHash)

        val bare = TorrentLinks.parse(hex) as TorrentLink.Magnet
        assertEquals(hex, bare.infoHash)

        val v2 = TorrentLinks.parse("magnet:?xt=urn:btmh:1220" + "ab".repeat(32)) as TorrentLink.Magnet
        assertEquals("btmh:1220" + "ab".repeat(32), v2.infoHash)
    }

    @Test
    fun `web links and garbage`() {
        assertEquals(TorrentLink.Web("https://example.org/get?id=5"), TorrentLinks.parse("https://example.org/get?id=5"))
        assertNull(TorrentLinks.parse("magnet:?dn=no-hash"))
        assertNull(TorrentLinks.parse("ftp://example.org/x.torrent"))
        assertNull(TorrentLinks.parse("just some words"))
        assertNull(TorrentLinks.parse(""))
    }

    @Test
    fun `pasted text is split into one link per line`() {
        val a = "magnet:?xt=urn:btih:" + "a".repeat(40)
        val b = "https://example.org/get?id=5"
        assertEquals(listOf(a, b), TorrentLinks.splitLines("  $a \r\n\n\t\n$b\n$a\n"))
        assertEquals(emptyList<String>(), TorrentLinks.splitLines(" \n \n"))
        assertEquals(listOf(a), TorrentLinks.splitLines(a))
    }

    // ---------------------------------------------------------------- suggestions & merge

    @Test
    fun `suggestions come from the torrent name and file names`() {
        val content = valid(TorrentContentValidator.validate(files("x/Dune - 01 Prologue.mp3" to 5, "x/Dune - 02 Arrakis.mp3" to 5)))
        val s = TorrentSuggestions.build("Frank Herbert - Dune (read by Simon Vance) [2007, MP3, 128 kbps]", content)
        assertEquals("Dune", s.metadata.title)
        assertEquals("Frank Herbert", s.metadata.author)
        assertEquals("Simon Vance", s.metadata.narrator)
        assertEquals("2007", s.metadata.year)
        assertEquals(listOf("01 Prologue", "02 Arrakis"), s.parts.map { it.title })
    }

    @Test
    fun `books of a collection take their title from the folder and the series from the torrent`() {
        val check = collection(
            TorrentContentValidator.validate(
                files(
                    "Гарри Поттер/Книга 1. Философский камень/01.mp3" to 5,
                    "Гарри Поттер/Книга 2/01.mp3" to 5,
                    "Гарри Поттер/03 - Узник Азкабана/01.mp3" to 5,
                    "Гарри Поттер/Бонус/01.mp3" to 5,
                ),
            ),
        )
        val name = "Роулинг Джоан - Гарри Поттер (читает Иванов) [2015, MP3]"
        val books = check.books.mapIndexed { i, book -> TorrentSuggestions.build(name, book, i).metadata }
        assertEquals(listOf("Узник Азкабана", "Бонус", "Философский камень", "Книга 2"), books.map { it.title })
        assertEquals(listOf("3", "2", "1", "2"), books.map { it.seriesIndex })
        assertTrue(books.all { it.series == "Гарри Поттер" && it.author == "Роулинг Джоан" && it.narrator == "Иванов" && it.year == "2015" })
    }

    @Test
    fun `user edits win, untouched fields take the tags`() {
        val suggested = BookMetadata(title = "Dune", author = "Herbert", year = "2007")
        val edited = suggested.copy(author = "Frank Herbert", series = "Dune Chronicles", seriesIndex = "1")
        val analyzed = BookMetadata(title = "Dune (Unabridged)", author = "F. Herbert", narrator = "Simon Vance", year = "2005", genre = "SF")
        val merged = TorrentReviewMerger.mergeMetadata(suggested, edited, analyzed)
        assertEquals("Dune (Unabridged)", merged.title) // untouched → tags
        assertEquals("Frank Herbert", merged.author) // edited → user
        assertEquals("Simon Vance", merged.narrator) // empty in both → tags
        assertEquals("Dune Chronicles", merged.series)
        assertEquals("1", merged.seriesIndex)
        assertEquals("2005", merged.year)
        assertEquals("SF", merged.genre)

        // Clearing a suggested value is an edit too.
        val cleared = TorrentReviewMerger.mergeMetadata(suggested, suggested.copy(year = ""), analyzed)
        assertNull(cleared.year)
    }

    @Test
    fun `merge applies order, removals and renamed parts`() {
        val review = TorrentReview(
            suggested = BookMetadata("T"),
            edited = BookMetadata("T"),
            parts = listOf(
                ReviewedPart("b/2.mp3", "Second", "Second"),
                ReviewedPart("b/1.mp3", "My first", "First"),
            ),
        )
        val analyzed = listOf(
            DraftPart("file:///d/b/1.mp3", "1.mp3", "Tag title 1", 1000, emptyList()),
            DraftPart("file:///d/b/2.mp3", "2.mp3", "Tag title 2", 2000, emptyList()),
            DraftPart("file:///d/b/3.mp3", "3.mp3", "Tag title 3", 3000, emptyList()),
        )
        val merged = TorrentReviewMerger.merge(review, BookMetadata("T"), analyzed) { it.removePrefix("file:///d/") } as MergedReview.Ready
        assertEquals(listOf("2.mp3", "1.mp3"), merged.parts.map { it.displayName })
        assertEquals(listOf("Tag title 2", "My first"), merged.parts.map { it.title })

        val missing = TorrentReviewMerger.merge(review, BookMetadata("T"), analyzed.drop(1)) { it.removePrefix("file:///d/") }
        assertEquals(MergedReview.MissingParts(listOf("b/1.mp3")), missing)
    }

    // ---------------------------------------------------------------- integrity

    private fun audio(name: String, container: AudioContainer, durationMs: Long?, size: Long, stream: Boolean = true) = ImportSourceFile(
        id = name, displayName = name, relativeDir = emptyList(), sizeBytes = size,
        parsed = ParsedAudioFile(container, if (stream) AudioStreamInfo("mp3", 44100, 2, 128_000) else null, durationMs, AudioTags(), emptyList(), null),
    )

    @Test
    fun `integrity accepts sane files and names broken ones`() {
        val oneMinute128k = 60_000L * 128 / 8
        assertTrue(AudiobookIntegrity.check(AudiobookLayout.MP3_PARTS, listOf(audio("1.mp3", AudioContainer.MP3, 60_000, oneMinute128k))).isEmpty())

        val problems = AudiobookIntegrity.check(
            AudiobookLayout.MP3_PARTS,
            listOf(
                audio("fake.mp3", AudioContainer.MP4, 60_000, oneMinute128k),
                audio("silent.mp3", AudioContainer.MP3, 0, oneMinute128k),
                audio("nostream.mp3", AudioContainer.MP3, 60_000, oneMinute128k, stream = false),
                audio("huge.mp3", AudioContainer.MP3, 1_000, 50_000_000),
            ),
        ).associate { it.fileName to it.issue }
        assertEquals(IntegrityIssue.WRONG_FORMAT, problems["fake.mp3"])
        assertEquals(IntegrityIssue.NO_DURATION, problems["silent.mp3"])
        assertEquals(IntegrityIssue.NO_AUDIO_STREAM, problems["nostream.mp3"])
        assertEquals(IntegrityIssue.IMPLAUSIBLE_BITRATE, problems["huge.mp3"])

        val m4b = AudiobookIntegrity.check(AudiobookLayout.SINGLE_M4B, listOf(audio("b.m4b", AudioContainer.MP3, 60_000, oneMinute128k)))
        assertEquals(IntegrityIssue.WRONG_FORMAT, m4b.single().issue)
    }
}
