package com.zyagodin.booksound.server

import com.zyagodin.booksound.api.ImportStage
import com.zyagodin.booksound.api.MetadataDto
import com.zyagodin.booksound.api.WatchProfileDto
import com.zyagodin.booksound.core.io.ByteArraySource
import com.zyagodin.booksound.core.metadata.AudioProbe
import com.zyagodin.booksound.server.importer.Ffmpeg
import com.zyagodin.booksound.server.importer.ImportSpec
import com.zyagodin.booksound.server.importer.JobRecord
import com.zyagodin.booksound.server.importer.SiteImportService
import com.zyagodin.booksound.server.importer.SourceFileRef
import com.zyagodin.booksound.server.auth.Principal
import com.zyagodin.booksound.server.metadata.PageFetcher
import com.zyagodin.booksound.server.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Real conversions with ffmpeg; skipped where it is not installed. */
class ImportTest {
    private lateinit var dataDir: File
    private lateinit var ctx: ServerContext

    @Before
    fun setUp() {
        assumeTrue("ffmpeg not installed", Ffmpeg("ffmpeg").available)
        dataDir = Files.createTempDirectory("booksound-import").toFile()
        val config = ServerConfig.fromEnvironment(mapOf("BOOKSOUND_DATA" to dataDir.absolutePath, "BOOKSOUND_TORRENTS" to "off"))
        config.createDirectories()
        ctx = ServerContext(config)
        ctx.imports.start()
        ctx.watch.start()
    }

    @After
    fun tearDown() {
        if (::ctx.isInitialized) ctx.close()
        if (::dataDir.isInitialized) dataDir.deleteRecursively()
    }

    private fun tone(file: File, seconds: Int, title: String) {
        val p = ProcessBuilder(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=$seconds",
            "-metadata", "title=$title", "-metadata", "artist=Tester", "-metadata", "album=Tone Book", "-c:a", "libmp3lame", "-b:a", "64k", file.absolutePath,
        ).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        check(p.waitFor() == 0)
    }

    private fun await(job: JobRecord): JobRecord = runBlocking {
        withTimeout(120_000) {
            var current = ctx.imports.get(job.id)!!
            while (current.isActive) {
                delay(200)
                current = ctx.imports.get(job.id)!!
            }
            current
        }
    }

    @Test
    fun `mp3 parts become one m4b with a chapter per part`() {
        val src = File(dataDir, "src").apply { mkdirs() }
        tone(File(src, "01.mp3"), 3, "Part one")
        tone(File(src, "02.mp3"), 4, "Part two")
        val job = ctx.imports.enqueue(
            ImportSpec(listOf(SourceFileRef(File(src, "01.mp3").path), SourceFileRef(File(src, "02.mp3").path)), "Tone Book", edited = MetadataDto("Tone Book", series = "Tones", seriesIndex = "1")),
            "Tone Book",
        )
        val done = await(job)
        assertEquals(done.error, ImportStage.DONE, done.stage)
        val book = ctx.library.repo.book(done.bookId!!)!!
        assertEquals("Tester/Tones/01 - Tone Book.m4b", book.relativePath)
        val parsed = AudioProbe.probe(ByteArraySource(File(dataDir, "library/" + book.relativePath).readBytes()), "x.m4b")
        assertEquals(book.id, parsed.bookId)
        assertEquals("aac", parsed.stream?.codec)
        assertEquals(listOf("Part one", "Part two"), ctx.library.repo.chapters(book.id).map { it.title })
        assertTrue(parsed.durationMs!! in 6_500..7_500)

        // A watch copy keeps the id and the chapters.
        val profile: WatchProfileDto = SettingsStore.DEFAULT_PROFILE
        ctx.settings.watchProfiles()
        val watchJob = ctx.watch.request(book, profile)!!
        assertEquals(ImportStage.DONE, await(watchJob).stage)
        val copy = ctx.watch.ready(book, profile)!!
        val parsedCopy = AudioProbe.probe(ByteArraySource(copy.readBytes()), copy.name)
        assertEquals(book.id, parsedCopy.bookId)
        assertEquals(1, parsedCopy.stream?.channels)
        assertEquals(2, parsedCopy.chapters.size)
    }

    @Test
    fun `a book from knigavuhe is downloaded, checked and imported`() {
        val src = File(dataDir, "site").apply { mkdirs() }
        tone(File(src, "a.mp3"), 3, "Глава 1")
        tone(File(src, "b.mp3"), 4, "Глава 2")
        val page = """
            <html><body><div class="page_title"><h1><span itemprop="name">Тоны</span>
              <span itemprop="author"><a href="/author/tester/">Тестер</a></span> <a href="/reader/chtec/">Чтец</a></h1></div>
            <div itemprop="description">Две главы тона.</div>
            <div class="book_serie_block"><div class="book_serie_title">Все книги из цикла <a href="/series/tony/">Тоны</a>:</div>
              <div class="book_serie_item"><span class="book_serie_item_index">2.</span> <strong>Тоны</strong></div></div>
            <script>new BookPlayer(77, {});</script></body></html>
        """.trimIndent()
        val data = """
            [null, {"result": {"init_data": {"id": 77, "book": {"id": 77, "name": "Тоны"}, "playlist": [
              {"title": "Глава 1", "url": "https://s1.knigavuhe.org/a.mp3", "error": 0, "duration_float": 3.0,
               "player_data": {"authors": "Тестер", "readers": "Чтец", "series": "Тоны"}},
              {"title": "Глава 2", "url": "https://s1.knigavuhe.org/b.mp3", "error": 0, "duration_float": 4.0}]}}}]
        """.trimIndent()
        val fetcher = object : PageFetcher {
            override suspend fun text(url: String) = when (url) {
                "https://knigavuhe.org/book/tony/" -> page
                "https://knigavuhe.org/ajax/book_data/77/" -> data
                else -> throw java.io.IOException("No page $url")
            }
            override suspend fun bytes(url: String, limit: Long): ByteArray = throw java.io.IOException("No cover")
            override suspend fun download(url: String, target: File, limit: Long, progress: (Long, Long?) -> Unit) {
                File(src, url.substringAfterLast('/')).copyTo(target, overwrite = true)
                progress(target.length(), target.length())
            }
        }
        val sites = SiteImportService(ctx.imports, fetcher, dataDir)
        val user = Principal("u1", "User", isAdmin = false, deviceId = "d1", viaCookie = true)
        val job = runBlocking { sites.add(user, "https://m.knigavuhe.org/book/tony") }
        assertEquals("Тоны", job.title)
        val done = await(job)
        assertEquals(done.error, ImportStage.DONE, done.stage)
        val book = ctx.library.repo.book(done.bookId!!)!!
        assertEquals("Тестер", book.metadata.author)
        assertEquals("Чтец", book.metadata.narrator)
        assertEquals("Тоны", book.metadata.series)
        assertEquals("2", book.metadata.seriesIndex)
        assertEquals("Две главы тона.", book.metadata.description)
        assertEquals(listOf("Глава 1", "Глава 2"), ctx.library.repo.chapters(book.id).map { it.title })

        // A page that isn't a book is refused straight away.
        assertTrue(runCatching { runBlocking { sites.add(user, "https://knigavuhe.org/reader/chtec/") } }.isFailure)
    }
}
