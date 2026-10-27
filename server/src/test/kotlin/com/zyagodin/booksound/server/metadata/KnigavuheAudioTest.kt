package com.zyagodin.booksound.server.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Book data in the shape the site's player gets it (trimmed to the fields that are read). */
class KnigavuheAudioTest {

    private val bookData = """
        [null, {"result": {"init_data": {
          "id": 5230,
          "book": {"id": 5230, "name": "Гарри Поттер и философский камень", "url": "/book/5230-garri-potter-i-filosofskijj-kamen/",
                   "authors": {"295": {"id": 295, "name": "Дж. К. Роулинг"}}, "readers": [], "cover": "https://s5.knigavuhe.org/1/covers/5230/3-1.jpg?v=2"},
          "files_host": "s8.knigavuhe.org",
          "playlist": [
            {"id": 1, "title": "01-01", "url": "https://s8.knigavuhe.org/1/audio/5230/abc/01-01.mp3?1", "error": 0, "duration": 302, "duration_float": 302.06,
             "player_data": {"title": "Гарри Поттер и философский камень", "authors": "Дж. К. Роулинг", "readers": "Александр Клюквин", "series": "Гарри Поттер"}},
            {"id": 2, "title": "01-02", "url": "https://s8.knigavuhe.org/1/audio/5230/abc/01-02.mp3?1", "error": 0, "duration": 298, "duration_float": 298.5},
            {"id": 3, "title": "broken", "url": "https://s8.knigavuhe.org/1/audio/5230/abc/01-03.mp3?1", "error": 1, "duration": 10}
          ],
          "covers": [{"src": "https://s5.knigavuhe.org/1/covers/5230/3.jpg?v=2", "w": 780, "h": 1200}]
        }, "player_data": {"speed_levels": [1]}}}]
    """.trimIndent()

    @Test
    fun `book data gives the chapters, people and cover`() {
        val book = KnigavuheAudio.parse(bookData, 5230)
        assertEquals("Гарри Поттер и философский камень", book.title)
        assertEquals("Дж. К. Роулинг", book.authors)
        assertEquals("Александр Клюквин", book.readers)
        assertEquals("Гарри Поттер", book.series)
        assertEquals("https://s5.knigavuhe.org/1/covers/5230/3.jpg?v=2", book.coverUrl)
        assertEquals(listOf("01-01", "01-02"), book.tracks.map { it.title })
        assertEquals(302_060L + 298_500L, book.durationMs)
    }

    @Test
    fun `a book only on LitRes and an error are refused with a reason`() {
        val litres = """
            [null, {"result": {"init_data": {"book": {"id": 16572, "name": "Война и мир", "has_litres": true},
              "playlist": [{"id": 200016572, "title": "Война и мир", "error": 0, "duration": 0, "url": "https://www.litres.ru/audiotrial/?art=23188614"}]}}}]
        """.trimIndent()
        val reason = runCatching { KnigavuheAudio.parse(litres, 16572) }.exceptionOrNull()
        assertTrue(reason is KnigavuheException && reason.message!!.contains("LitRes"))

        val missing = runCatching { KnigavuheAudio.parse("""[{"code":404,"message":"Route not found"}, null]""", 1) }.exceptionOrNull()
        assertTrue(missing is KnigavuheException)
    }

    @Test
    fun `book addresses and ids`() {
        assertEquals("https://knigavuhe.org/book/5230-garri-potter/", KnigavuheAudio.bookPage("https://m.knigavuhe.org/book/5230-garri-potter"))
        assertEquals("https://knigavuhe.org/book/vojjna-i-mir-2/", KnigavuheAudio.bookPage("https://www.knigavuhe.org/book/vojjna-i-mir-2/?ref=1"))
        assertNull(KnigavuheAudio.bookPage("https://knigavuhe.org/reader/kljukvin-aleksandr/"))
        assertNull(KnigavuheAudio.bookPage("https://example.org/book/5230-garri-potter/"))
        assertEquals(5230, KnigavuheAudio.idFromUrl("https://knigavuhe.org/book/5230-garri-potter/"))
        assertNull(KnigavuheAudio.idFromUrl("https://knigavuhe.org/book/vojjna-i-mir-2/"))
        assertEquals(16572, KnigavuheAudio.idFromPage("<script>var player = new BookPlayer(16572, {});</script>"))
        assertTrue(KnigavuheAudio.isSiteFile("https://s8.knigavuhe.org/1/audio/1.mp3"))
        assertFalse(KnigavuheAudio.isSiteFile("https://knigavuhe.org.evil.example/1.mp3"))
        assertFalse(KnigavuheAudio.isSiteFile("http://169.254.169.254/latest"))
    }
}
