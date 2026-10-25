package com.zyagodin.booksound.server.importer

import com.zyagodin.booksound.api.ErrorCodes
import com.zyagodin.booksound.api.ImportStage
import com.zyagodin.booksound.api.MetadataDto
import com.zyagodin.booksound.core.naming.FileNameSanitizer
import com.zyagodin.booksound.server.auth.Principal
import com.zyagodin.booksound.server.library.LibraryException
import com.zyagodin.booksound.server.metadata.KnigavuheAudio
import com.zyagodin.booksound.server.metadata.KnigavuheBook
import com.zyagodin.booksound.server.metadata.KnigavuheException
import com.zyagodin.booksound.server.metadata.KnigavuheProvider
import com.zyagodin.booksound.server.metadata.PageFetcher
import com.zyagodin.booksound.server.metadata.SiteBook
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

/** A book to download from an audiobook site and import; stored with the job, so a restart starts it over. */
@Serializable
@SerialName("site")
data class SiteImportSpec(
    /** The book's page. */
    val url: String,
    val addedBy: String? = null,
) : JobSpec

/**
 * Imports books from knigavuhe.org: the chapters are plain mp3 files on the site's servers, listed with their
 * lengths by the player's book data, so the whole book is downloaded, checked against those lengths and
 * imported like an upload. The details come from the book data and the book's page.
 */
class SiteImportService(
    private val imports: ImportService,
    private val fetcher: PageFetcher,
    private val workRoot: File,
) {
    private val log = LoggerFactory.getLogger(SiteImportService::class.java)
    private val pages = KnigavuheProvider()

    init {
        imports.siteDownloader = ::download
    }

    /**
     * Checks that [url] is a book the site has audio for and queues its download. Answers right away with what
     * is wrong (not a book page, LitRes only, the site unreachable) instead of failing later in the queue.
     */
    suspend fun add(principal: Principal, url: String): JobRecord {
        val page = KnigavuheAudio.bookPage(url) ?: throw LibraryException(ErrorCodes.BAD_REQUEST, "Not a book page on knigavuhe.org")
        val book = try {
            book(page).first
        } catch (e: KnigavuheException) {
            throw LibraryException(ErrorCodes.UNSUPPORTED, e.message ?: "Unsupported book")
        } catch (e: IOException) {
            throw LibraryException(ErrorCodes.NOT_FOUND, "knigavuhe.org can't be reached: ${e.message}")
        }
        return imports.enqueue(SiteImportSpec(page, principal.userId), book.title)
    }

    /** Downloads the book into [workDir] and describes the import of the downloaded files. */
    private suspend fun download(spec: SiteImportSpec, workDir: File, report: (ImportStage, Float?) -> Unit): ImportSpec {
        report(ImportStage.DOWNLOADING, null)
        val (book, details) = try {
            book(spec.url)
        } catch (e: KnigavuheException) {
            throw ImportFailure(e.message ?: "Unsupported book")
        }
        checkSpace(book)
        val dir = File(workDir, "download").apply { mkdirs() }
        val total = book.durationMs.coerceAtLeast(1)
        var done = 0L
        val width = book.tracks.size.toString().length.coerceAtLeast(2)
        val files = book.tracks.mapIndexed { i, track ->
            // Stored under its number, which keeps the site's order; the title names the chapter when the file has no tags.
            val number = (i + 1).toString().padStart(width, '0')
            val file = File(dir, "$number.mp3")
            val name = "$number${FileNameSanitizer.sanitize(track.title, 80)?.let { " - $it" }.orEmpty()}.mp3"
            fetcher.download(track.url, file, MAX_FILE_BYTES) { bytes, length ->
                val part = if (length != null && length > 0) bytes.toFloat() / length else 0f
                report(ImportStage.DOWNLOADING, ((done + track.durationMs * part) / total).coerceIn(0f, 1f))
            }
            done += track.durationMs
            SourceFileRef(file.absolutePath, name = name)
        }
        val cover = (details?.coverUrl ?: book.coverUrl)?.takeIf(KnigavuheAudio::isSiteFile)?.let { url ->
            runCatching { File(workDir, "cover.img").apply { writeBytes(fetcher.bytes(url, MAX_COVER_BYTES)) } }
                .onFailure { log.info("Cover of {} not downloaded: {}", spec.url, it.toString()) }
                .getOrNull()
        }
        return ImportSpec(
            files = files,
            sourceName = book.title,
            edited = MetadataDto(
                title = book.title,
                author = book.authors ?: details?.author,
                narrator = book.readers ?: details?.narrator,
                series = book.series ?: details?.series,
                seriesIndex = details?.seriesIndex?.takeIf { details.series != null },
                genre = details?.genre,
                description = details?.description,
            ),
            coverPath = cover?.absolutePath,
            // Downloads nobody has listened to: audio that decodes to another length than it claims fails.
            strict = true,
            addedBy = spec.addedBy,
        )
    }

    /** The book's player data and, when the page can be read, its page's details (description, series number). */
    private suspend fun book(page: String): Pair<KnigavuheBook, SiteBook?> {
        val html = fetcher.text(page)
        val id = KnigavuheAudio.idFromUrl(page) ?: KnigavuheAudio.idFromPage(html)
            ?: throw KnigavuheException("No book on this page")
        val book = KnigavuheAudio.parse(fetcher.text(KnigavuheAudio.bookDataUrl(id)), id)
        val details = runCatching { pages.parseBook(html, page) }.getOrNull()
        return book to details
    }

    /** Room for the download, the converted book and its copy on the way into the library. */
    private fun checkSpace(book: KnigavuheBook) {
        val estimate = book.durationMs / 1000 * BYTES_PER_SECOND
        val free = workRoot.usableSpace
        if (free in 1 until estimate * 3 + RESERVE_BYTES) {
            throw ImportFailure("Not enough disk space: need about ${(estimate * 3) shr 20} MiB, ${free shr 20} MiB free")
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 1L shl 30
        const val MAX_COVER_BYTES = 10L shl 20
        /** The site's mp3s are at most 128 kbps. */
        const val BYTES_PER_SECOND = 16_000L
        const val RESERVE_BYTES = 200L shl 20
    }
}
