package com.zyagodin.booksound.server.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pages here are trimmed to the parts the parsers read, in the sites' markup as other open-source clients
 * of them record it (the sites themselves can't be reached from the build).
 */
class MetadataParsingTest {

    @Test
    fun `lengths in the ways sites write them`() {
        assertEquals((11 * 60 + 22) * 60_000L, PageText.duration("11 часов 22 минуты"))
        assertEquals(3 * 3_600_000L, PageText.duration("3 часа"))
        assertEquals(24 * 60_000L, PageText.duration("24 минуты"))
        assertEquals((12 * 3600 + 30 * 60 + 5) * 1000L, PageText.duration("Время звучания: 12:30:05 Добавлена: 01.02.2020"))
        assertEquals((5 * 60 + 7) * 60_000L, PageText.duration("5 ч. 7 мин."))
        assertNull(PageText.duration("Рейтинг 5"))
    }

    @Test
    fun `series names and numbers`() {
        assertEquals("Тёмная башня" to "3", PageText.series("Тёмная башня (3)"))
        assertEquals("Ведьмак" to "2.5", PageText.series("Ведьмак #2,5"))
        assertEquals("Гарри Поттер" to "4", PageText.series("Гарри Поттер, книга 4"))
        assertEquals("Дюна" to null, PageText.series("Дюна"))
    }

    @Test
    fun `the author is taken off the front of a title`() {
        assertEquals("Абсолютное оружие", PageText.titleWithoutAuthor("Шекли Роберт – Абсолютное оружие", "Шекли Роберт"))
        assertEquals("Пикник на обочине - повесть", PageText.titleWithoutAuthor("Пикник на обочине - повесть", "Стругацкие"))
    }

    // ------------------------------------------------------------------------------------------ knigavuhe

    private val knigavuhe = KnigavuheProvider()

    @Test
    fun `knigavuhe search cards, desktop and mobile`() {
        val html = """
            <html><body><form action="/search/"><input name="q"></form>
            <div class="bookkitem">
              <a class="bookkitem_cover" href="/book/546-uznik/"><img class="bookkitem_cover_img" src="https://s5.knigavuhe.org/1/covers/546/2-1.jpg"></a>
              <div class="bookkitem_right">
                <div class="bookkitem_name">
                  <a href="/book/546-uznik/" class="bookkitem_name">Узник Азкабана</a>
                  <span class="bookkitem_author"><span class="bookkitem_author_label">автор</span> <a href="/author/dzhoan-rouling/">Дж. К. Роулинг</a></span>
                </div>
                <div class="bookkitem_genre"><a href="/genre/fantastika/">Фантастика и фэнтези</a></div>
                <div class="bookkitem_about">Третий год обучения.</div>
                <div class="bookkitem_meta">
                  <div class="bookkitem_meta_block"><span class="bookkitem_icon -reader"></span><span class="bookkitem_meta_label">Читает</span> <a href="/reader/kljukvin-aleksandr/">Александр Клюквин</a></div>
                  <div class="bookkitem_meta_block"><span class="bookkitem_icon -time"></span><span class="bookkitem_meta_time">11 часов 22 минуты</span></div>
                </div>
              </div>
            </div>
            <span class="bookkitemm" id="book1127">
              <a class="bookkitem_cover" href="/book/warhammer-40000-kozyr/"><img class="bookkitem_cover_img" src="https://s5.knigavuhe.org/1/covers/1127/2-1.jpg"></a>
              <span class="bookkitem_right">
                <div class="bookkitem_name"><a href="/book/warhammer-40000-kozyr/" class="bookkitem_name">Warhammer 40000. Козырь</a></div>
                <div class="bookkitem_meta_block"><span class="bookkitem_icon -author"></span><a href="/author/sendi-mitchell/">Сэнди Митчелл</a></div>
                <div class="bookkitem_meta_block"><span class="bookkitem_icon -reader"></span><a href="/reader/ravenhan/">ravenhan</a></div>
                <div class="bookkitem_meta_block"><span class="bookkitem_icon -time"></span><span class="bookkitem_meta_time">3 часа 19 минут</span></div>
              </span>
            </span>
            <span class="bookkitemm" id="book16572">
              <div class="bookkitem_name"><a href="/book/vojjna-i-mir-2/" class="bookkitem_name">Война и мир</a></div>
              <div class="bookkitem_meta_block -litres"><span class="bookkitem_icon -litres"></span></div>
            </span>
            </body></html>
        """.trimIndent()
        val books = knigavuhe.parseSearch(html, "https://knigavuhe.org/search/?q=x")
        assertEquals(2, books.size)
        val first = books[0]
        assertEquals("https://knigavuhe.org/book/546-uznik/", first.url)
        assertEquals("Узник Азкабана", first.title)
        assertEquals("Дж. К. Роулинг", first.author)
        assertEquals("Александр Клюквин", first.narrator)
        assertEquals((11 * 60 + 22) * 60_000L, first.durationMs)
        assertEquals("https://s5.knigavuhe.org/1/covers/546/2-1.jpg", first.coverUrl)
        assertEquals("Сэнди Митчелл", books[1].author)
        assertEquals("ravenhan", books[1].narrator)
    }

    @Test
    fun `knigavuhe book page`() {
        val html = """
            <html><head><meta property="og:image" content="https://s5.knigavuhe.org/1/covers/5230/3.jpg"></head><body>
            <div class="book_left_blocks">
              <div class="book_genre_pretitle"><a href="/genre/fantastika/">Фантастика и фэнтези</a></div>
              <div class="page_title"><h1>
                <span class="book_title_elem book_title_name" itemprop="name">Гарри Поттер и философский камень</span>
                <span class="book_title_elem">
                  <span itemprop="author"><a href="/author/dzhoan-rouling/">Дж. К. Роулинг</a></span>
                  читает <a href="/reader/kljukvin-aleksandr/">Александр Клюквин</a>
                </span>
              </h1></div>
              <div class="book_cover_wrap"><div class="book_cover"><img src="https://s5.knigavuhe.org/1/covers/5230/3-1.jpg"></div></div>
              <div class="book_blue_block">
                <div><span class="book_info_label -not_last">Время звучания:</span> 08:52:41</div>
                <div><span class="book_info_label">Добавлена:</span> 1 марта 2017</div>
              </div>
              <div class="book_description" itemprop="description">Мальчик узнаёт, что он волшебник.<br>Первый курс в Хогвартсе.</div>
              <div class="info_block book_serie_block">
                <div class="book_serie_title"><b>Другие озвучки</b></div>
                <div class="book_serie_item"><a href="/book/13026-garri-potter-1/">Гарри Поттер и философский камень</a> <span>в исполнении</span> <a href="/reader/kulagin-leonid/">Леонида Кулагина</a></div>
              </div>
              <div class="info_block book_serie_block">
                <div class="book_serie_title">Все книги из цикла <a href="/series/garri-potter/">Гарри Поттер</a>:</div>
                <div class="book_serie_item"><span class="book_serie_item_index">1.</span> <strong>Гарри Поттер и философский камень</strong></div>
                <div class="book_serie_item"><span class="book_serie_item_index">2.</span> <a href="/book/645-garri-potter-i-tajjnaja-komnata/">Гарри Поттер и Тайная комната</a></div>
              </div>
            </div></body></html>
        """.trimIndent()
        val book = knigavuhe.parseBook(html, "https://knigavuhe.org/book/5230-garri-potter-i-filosofskijj-kamen/")!!
        assertEquals("Гарри Поттер и философский камень", book.title)
        assertEquals("Дж. К. Роулинг", book.author)
        assertEquals("Александр Клюквин", book.narrator)
        assertEquals("Гарри Поттер", book.series)
        assertEquals("1", book.seriesIndex)
        assertEquals("Фантастика и фэнтези", book.genre)
        assertEquals("Мальчик узнаёт, что он волшебник.\nПервый курс в Хогвартсе.", book.description)
        assertEquals((8 * 3600 + 52 * 60 + 41) * 1000L, book.durationMs)
        assertEquals("https://s5.knigavuhe.org/1/covers/5230/3-1.jpg", book.coverUrl)
    }

    @Test
    fun `knigavuhe book addresses`() {
        assertTrue(knigavuhe.isBookUrl("https://knigavuhe.org/book/546-uznik/"))
        assertTrue(knigavuhe.isBookUrl("https://m.knigavuhe.org/book/vojjna-i-mir-2/"))
        assertFalse(knigavuhe.isBookUrl("https://knigavuhe.org/reader/kljukvin-aleksandr/"))
        assertFalse(knigavuhe.isBookUrl("https://example.org/book/546-uznik/"))
    }

    // ------------------------------------------------------------------------------------------ akniga

    private val akniga = AknigaProvider()

    @Test
    fun `akniga book page, desktop layout`() {
        val html = """
            <html><head><meta property="og:title" content="Шекли Роберт - Абсолютное оружие - слушать онлайн"></head><body>
            <div class="caption__article-main"><h1>Шекли Роберт - Абсолютное оружие</h1></div>
            <article data-bid="50768">
              <div class="book--cover"><img src="https://akniga.org/uploads/media/topic/2019/06/28/11/preview/821258151e502731e8c6_400x.jpg"></div>
              <div class="description__article-main">На пустынном Марсе три человека нашли арсенал с оружием…<div>Not part of the description</div></div>
              <div class="caption__article--about-block"><div class="content__main__book--item--caption">Автор</div><span class="content__article--about-content">Шекли Роберт<span class="tooltip__main">Tooltip</span></span></div>
              <div class="caption__article--about-block"><div class="content__main__book--item--caption">Исполнитель</div><span class="content__article--about-content">Карлов Александр</span></div>
              <div class="caption__article--about-block"><div class="content__main__book--item--caption">Длительность</div><span class="content__article--about-content">24 минуты</span></div>
              <div class="caption__article--about-block"><div class="content__main__book--item--caption">Год</div><span class="content__article--about-content">2015</span></div>
              <div class="caption__article--about-block caption__article--about-block-samebook"><div class="content__main__book--item--caption">Исполнитель</div><span class="content__article--about-content">Другой чтец</span></div>
              <div class="classifiers__article-main"><div>Жанры: <a>Фантастика</a></div><div>Место действия: <a>Марс</a></div></div>
            </article></body></html>
        """.trimIndent()
        val book = akniga.parseBook(html, "https://akniga.org/shekli-robert-absolyutnoe-oruzhie")!!
        assertEquals("Абсолютное оружие", book.title)
        assertEquals("Шекли Роберт", book.author)
        assertEquals("Карлов Александр", book.narrator)
        assertEquals(24 * 60_000L, book.durationMs)
        assertEquals("2015", book.year)
        assertEquals("Фантастика", book.genre)
        assertEquals("На пустынном Марсе три человека нашли арсенал с оружием…", book.description)
        assertEquals("https://akniga.org/uploads/media/topic/2019/06/28/11/preview/821258151e502731e8c6_400x.jpg", book.coverUrl)
    }

    @Test
    fun `akniga book page, mobile layout`() {
        val html = """
            <html><body class="layout--template-acl10-mobile"><h1>Абсолютное оружие</h1>
            <article data-bid="50768">
              <div class="topic-content">На пустынном Марсе…<div class="topic-content-right">Excluded sidebar</div></div>
              <div class="book-info">
                <div class="item-info"><span class="fa">Icon</span>Автор: Шекли Роберт</div>
                <div class="item-info">Исполнитель: Карлов Александр</div>
                <div class="item-info">Длительность: 24 минуты</div>
              </div>
            </article></body></html>
        """.trimIndent()
        val book = akniga.parseBook(html, "https://akniga.org/shekli-robert-absolyutnoe-oruzhie")!!
        assertEquals("Абсолютное оружие", book.title)
        assertEquals("Шекли Роберт", book.author)
        assertEquals("Карлов Александр", book.narrator)
        assertEquals(24 * 60_000L, book.durationMs)
        assertEquals("На пустынном Марсе…", book.description)
    }

    @Test
    fun `akniga search results are the links to book pages`() {
        val html = """
            <html><body><form action="/search/books"><input name="q"></form>
            <div class="content__main__articles">
              <div class="content__main__articles--item">
                <a href="https://akniga.org/shekli-robert-absolyutnoe-oruzhie" class="content__article-main-link"><h2 class="caption__article-main">Шекли Роберт – Абсолютное оружие</h2></a>
                <a href="https://akniga.org/author/Шекли Роберт/">Шекли Роберт</a>
                <a href="https://akniga.org/performer/Карлов Александр/">Карлов Александр</a>
                <img src="https://akniga.org/uploads/cover.jpg">
              </div>
              <a href="https://akniga.org/section/fantasy/">Фантастика</a>
            </div></body></html>
        """.trimIndent()
        val books = akniga.parseSearch(html, "https://akniga.org/search/books?q=x")
        assertEquals(1, books.size)
        assertEquals("https://akniga.org/shekli-robert-absolyutnoe-oruzhie", books[0].url)
        assertEquals("Абсолютное оружие", books[0].title)
        assertEquals("Шекли Роберт", books[0].author)
        assertEquals("Карлов Александр", books[0].narrator)
    }

    @Test(expected = PageFormatException::class)
    fun `a page that isn't a search page is reported`() {
        akniga.parseSearch("<html><body><p>Технические работы</p></body></html>", "https://akniga.org/search/books?q=x")
    }
}
