package com.zyagodin.booksound.server.metadata

import com.zyagodin.booksound.api.MatchState
import com.zyagodin.booksound.api.MatchVerdict
import com.zyagodin.booksound.api.MetadataSearchRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MetadataMatcherTest {
    private val hours = 3_600_000L

    private val book = BookFacts("Мастер и Маргарита", "Михаил Булгаков", "Олег Басилашвили", 15 * hours + 40 * 60_000)

    private fun site(
        title: String = "Мастер и Маргарита",
        author: String? = "Булгаков Михаил",
        narrator: String? = null,
        durationMs: Long? = null,
    ) = SiteBook("https://example.org/b", title, author, narrator, durationMs = durationMs)

    @Test
    fun `the same narrator or length is the same recording`() {
        assertEquals(MatchVerdict.SAME_RECORDING, MetadataMatcher.match(book, site(narrator = "Басилашвили Олег")).verdict)
        // Rounded to the minute, and a little silence counted.
        val byLength = MetadataMatcher.match(book, site(durationMs = 15 * hours + 42 * 60_000))
        assertEquals(MatchVerdict.SAME_RECORDING, byLength.verdict)
        assertEquals(MatchState.MATCH, byLength.duration)
    }

    @Test
    fun `another narrator or length is the same book in another recording`() {
        val otherNarrator = MetadataMatcher.match(book, site(narrator = "Клюквин Александр"))
        assertEquals(MatchVerdict.SAME_BOOK, otherNarrator.verdict)
        assertEquals(MatchState.CONFLICT, otherNarrator.narrator)
        assertEquals(MatchVerdict.SAME_BOOK, MetadataMatcher.match(book, site(durationMs = 11 * hours)).verdict)
        // Nothing to tell recordings apart by.
        assertEquals(MatchVerdict.SAME_BOOK, MetadataMatcher.match(book, site()).verdict)
    }

    @Test
    fun `another author or title is another book`() {
        assertEquals(MatchVerdict.DIFFERENT, MetadataMatcher.match(book, site(author = "Ильф Илья, Петров Евгений")).verdict)
        assertEquals(MatchVerdict.DIFFERENT, MetadataMatcher.match(book, site(title = "Собачье сердце")).verdict)
    }

    @Test
    fun `a title without an author is doubtful`() {
        assertEquals(MatchVerdict.DOUBTFUL, MetadataMatcher.match(book, site(author = null)).verdict)
        assertEquals(MatchVerdict.DOUBTFUL, MetadataMatcher.match(book.copy(author = null), site()).verdict)
    }

    @Test
    fun `names written differently still match`() {
        assertEquals(MatchState.MATCH, Matching.samePeople("Дж. К. Роулинг", "Роулинг Джоан"))
        assertEquals(MatchState.MATCH, Matching.samePeople("Аркадий и Борис Стругацкие", "Стругацкие Аркадий и Борис"))
        assertEquals(MatchState.UNKNOWN, Matching.samePeople(null, "Роулинг"))
        assertTrue(Matching.titleSimilarity("Ёлка", "елка") > 0.99)
        assertTrue(Matching.titleSimilarity("Мастер и Маргарита", "Мастер и Маргарита (аудиоспектакль)") >= 0.75)
    }

    @Test
    fun `the service searches every site, opens the likeliest results and keeps only their covers`() = runBlocking {
        val provider = object : MetadataProvider {
            override val id = "test"
            override val name = "Test"
            override fun searchUrl(query: String) = "https://test.example/search?q=$query"
            override fun isBookUrl(url: String) = true
            override fun parseSearch(html: String, pageUrl: String) = listOf(
                SiteBook("https://test.example/a", "Мастер и Маргарита", "Булгаков Михаил"),
                SiteBook("https://test.example/b", "Собачье сердце", "Булгаков Михаил"),
            )
            override fun parseBook(html: String, pageUrl: String) = SiteBook(
                pageUrl, "Мастер и Маргарита", "Булгаков Михаил", "Басилашвили Олег",
                description = "Роман", coverUrl = "https://test.example/cover.jpg",
            )
        }
        val broken = object : MetadataProvider by provider {
            override val id = "broken"
            override val name = "Broken"
            override fun parseSearch(html: String, pageUrl: String): List<SiteBook> = throw PageFormatException("changed")
        }
        val fetched = mutableListOf<String>()
        val fetcher = object : PageFetcher {
            override suspend fun text(url: String): String = synchronized(fetched) { fetched += url; "" }
            override suspend fun bytes(url: String, limit: Long): ByteArray = if (url.endsWith(".jpg")) byteArrayOf(1) else throw IOException()
        }
        val service = MetadataService(listOf(provider, broken), fetcher)
        val response = service.search(MetadataSearchRequest("Мастер и Маргарита", "Михаил Булгаков", "Олег Басилашвили"))

        assertEquals(listOf("Broken"), response.failedSources)
        val match = response.matches.single()
        assertEquals(MatchVerdict.SAME_RECORDING, match.verdict)
        assertEquals("Роман", match.candidate.metadata.description)
        // The other book's page wasn't opened.
        assertTrue("https://test.example/b" !in fetched)
        assertNotNull(service.cover("https://test.example/cover.jpg"))
        assertNull(service.cover("http://169.254.169.254/latest/meta-data"))
    }
}
