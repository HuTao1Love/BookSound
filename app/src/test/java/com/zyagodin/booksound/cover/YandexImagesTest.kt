package com.zyagodin.booksound.cover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YandexImagesTest {
    @Test
    fun `current data-state page`() {
        val state = """{"initialState":{"serpList":{"items":{"entities":{
            "a":{"origUrl":"https:\/\/example.org\/dune.jpg","image":"\/\/avatars.mds.yandex.net\/i?id=1&n=13","alt":"Дюна обложка",
                 "dups":[{"origUrl":"https:\/\/example.org\/dune-small.jpg"}]},
            "b":{"origUrl":"https:\/\/example.org\/dune2.jpg","snippet":{"title":"Dune 2"},"thumb":{"url":"\/\/avatars.mds.yandex.net\/i?id=2"}},
            "c":{"origUrl":"https:\/\/example.org\/dune.jpg"}}}}}}"""
        val escaped = state.replace("&", "&amp;").replace("\"", "&quot;")
        val html = """<html><div class="Root" id="ImagesApp" data-state="$escaped"></div><div data-state="{&quot;x&quot;:1}"></div></html>"""
        val covers = YandexImages.parse(html)
        assertEquals(listOf("https://example.org/dune.jpg", "https://example.org/dune2.jpg"), covers.map { it.fullUrl })
        assertEquals("https://avatars.mds.yandex.net/i?id=1&n=13", covers[0].thumbnailUrl)
        assertEquals("Дюна обложка", covers[0].title)
        assertEquals("https://avatars.mds.yandex.net/i?id=2", covers[1].thumbnailUrl)
        assertEquals("Dune 2", covers[1].title)
        assertTrue(covers.all { it.fromWeb && it.source == "Яндекс" })
    }

    @Test
    fun `older data-bem items`() {
        val html = """<div class="serp-item" data-bem='{"serp-item":{"img_href":"http://old.example/c.png","thumb":{"url":"//im0-tub-ru.yandex.net/i?id=3"},"snippet":{"title":"Old"}}}'></div>
            <div data-bem='{"other":{}}'></div>"""
        val covers = YandexImages.parse(html)
        assertEquals(1, covers.size)
        assertEquals("http://old.example/c.png", covers[0].fullUrl)
        assertEquals("https://im0-tub-ru.yandex.net/i?id=3", covers[0].thumbnailUrl)
    }

    @Test
    fun `captcha page and url`() {
        assertTrue(YandexImages.parse("<html>showcaptcha</html>").isEmpty())
        assertEquals("https://yandex.ru/images/search?text=%D0%94%D1%8E%D0%BD%D0%B0+%D0%93%D0%B5%D1%80%D0%B1%D0%B5%D1%80%D1%82", YandexImages.searchUrl("Дюна Герберт", true))
    }
}
