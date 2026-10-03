package com.zyagodin.booksound.cover

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverSearchParsingTest {

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `queries lose bracketed noise`() {
        assertEquals("Dune", CoverSearchRepository.cleanQuery("Dune (Unabridged)"))
        assertEquals("Ночной дозор", CoverSearchRepository.cleanQuery("Ночной дозор [2014, MP3, 128 kbps]"))
        assertEquals("Book 3 - Title", CoverSearchRepository.cleanQuery("  Book_3 - Title  "))
    }

    @Test
    fun `a volume of a series is searched with the series name`() {
        assertEquals(
            "Реинкарнация безработного Детство — домашний учитель",
            CoverSearchRepository.withSeries("Детство — домашний учитель", "Реинкарнация безработного"),
        )
        // Already in the title, or no series: the title as it is.
        assertEquals("Дюна. Мессия Дюны", CoverSearchRepository.withSeries("Дюна. Мессия Дюны", "дюна"))
        assertEquals("Dune", CoverSearchRepository.withSeries("Dune", null))
        assertEquals("Dune", CoverSearchRepository.withSeries("Dune", "  "))
    }

    @Test
    fun `itunes results`() {
        val covers = CoverSearchRepository.parseItunes(
            parse(
                """{"resultCount":2,"results":[
                {"wrapperType":"audiobook","collectionName":"Dune","artistName":"Frank Herbert",
                 "artworkUrl100":"https://is1-ssl.mzstatic.com/image/thumb/Music/v4/aa/source/100x100bb.jpg"},
                {"kind":"ebook","trackName":"Dune Messiah","artistName":"Frank Herbert",
                 "artworkUrl100":"https://is2-ssl.mzstatic.com/image/thumb/Publication/x/100x100bb.jpg"},
                {"collectionName":"No artwork"}]}""",
            ),
        )
        assertEquals(2, covers.size)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music/v4/aa/source/1000x1000bb.jpg", covers[0].fullUrl)
        assertEquals("https://is1-ssl.mzstatic.com/image/thumb/Music/v4/aa/source/300x300bb.jpg", covers[0].thumbnailUrl)
        assertEquals("Dune Messiah", covers[1].title)
    }

    @Test
    fun `open library results`() {
        val covers = CoverSearchRepository.parseOpenLibrary(
            parse("""{"numFound":2,"docs":[{"title":"Dune","author_name":["Frank Herbert"],"cover_i":11481354},{"title":"No cover"}]}"""),
        )
        assertEquals(1, covers.size)
        assertEquals("https://covers.openlibrary.org/b/id/11481354-L.jpg", covers[0].fullUrl)
        assertEquals("Frank Herbert", covers[0].author)
    }

    @Test
    fun `google books results`() {
        val covers = CoverSearchRepository.parseGoogleBooks(
            parse("""{"items":[{"id":"B1XX","volumeInfo":{"title":"Dune","authors":["Frank Herbert"],"imageLinks":{"thumbnail":"x"}}},{"id":"B2","volumeInfo":{"title":"No image"}}]}"""),
        )
        assertEquals(listOf("Dune"), covers.map { it.title })
        assertTrue(covers[0].fullUrl.contains("id=B1XX"))
    }

    @Test
    fun `litres results are found wherever they are nested`() {
        val covers = CoverSearchRepository.parseLitres(
            parse(
                """{"status":200,"payload":{"data":[
                {"type":"audiobook","instance":{"id":123,"title":"Ночной дозор","cover_url":"/pub/c/cover_415/123.jpg",
                  "persons":[{"full_name":"Олег Булыгин","role":"reader"},{"full_name":"Сергей Лукьяненко","role":"author"}]}},
                {"type":"text_book","instance":{"id":5,"title":"Без обложки"}}]}}""",
            ),
        )
        assertEquals(1, covers.size)
        assertEquals("https://www.litres.ru/pub/c/cover_415/123.jpg", covers[0].thumbnailUrl)
        assertEquals("https://www.litres.ru/pub/c/cover_max1500/123.jpg", covers[0].fullUrl)
        assertEquals("Сергей Лукьяненко", covers[0].author)
    }

    @Test
    fun `bing image results`() {
        val html = """
            <ul><li><div class="imgpt"><a class="iusc" style="height:180px" m="{&quot;cid&quot;:&quot;x1&quot;,&quot;purl&quot;:&quot;https://hedgehog.example/book&quot;,&quot;murl&quot;:&quot;https://hedgehog.example/img/cover.jpg?a=1&amp;b=2&quot;,&quot;turl&quot;:&quot;https://tse2.mm.bing.net/th?id=OIP.abc&amp;pid=Api&quot;,&quot;t&quot;:&quot;Детство - Домашний учитель&quot;}" href="/images/search?view=detailV2"></a></div></li>
            <li><a class="iusc" m='{"murl":"https://ruli.example/2.webp","turl":"https://tse1.mm.bing.net/th?id=OIP.def"}'></a></li>
            <li><a class="iusc" m="{&quot;purl&quot;:&quot;https://no.image/&quot;}"></a></li></ul>
        """
        val covers = CoverSearchRepository.parseBing(html)
        assertEquals(2, covers.size)
        assertEquals("https://hedgehog.example/img/cover.jpg?a=1&b=2", covers[0].fullUrl)
        assertEquals("https://tse2.mm.bing.net/th?id=OIP.abc&pid=Api", covers[0].thumbnailUrl)
        assertEquals("Детство - Домашний учитель", covers[0].title)
        assertTrue(covers.all { it.fromWeb })
        assertEquals("https://ruli.example/2.webp", covers[1].fullUrl)
    }

    @Test
    fun `duckduckgo token and results`() {
        assertEquals("4-123456789012345678901234567890", CoverSearchRepository.parseDuckDuckGoToken("""<script>vqd="4-123456789012345678901234567890";</script>"""))
        assertEquals("4-98765_abc", CoverSearchRepository.parseDuckDuckGoToken("""nrj('/d.js?q=x&vqd=4-98765_abc&p=1')"""))
        val covers = CoverSearchRepository.parseDuckDuckGo(
            parse("""{"results":[{"image":"https://site.example/full.png","thumbnail":"https://tse4.mm.bing.net/th?id=1","title":"Cover"},{"title":"no image"}]}"""),
        )
        assertEquals(1, covers.size)
        assertEquals("https://tse4.mm.bing.net/th?id=1", covers[0].thumbnailUrl)
        assertEquals("DuckDuckGo", covers[0].source)
    }
}
