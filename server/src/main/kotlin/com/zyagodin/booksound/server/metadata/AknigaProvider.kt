package com.zyagodin.booksound.server.metadata

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * akniga.org. A book's page is an `article[data-bid]` with the cover in `.book--cover`, the description in
 * `.description__article-main`, and the details as labelled blocks (`.caption__article--about-block`:
 * "Автор", "Исполнитель", "Длительность", "Год", "Цикл"); blocks marked `-samebook` describe other
 * recordings of the book and are skipped. The mobile layout has the same details as "Автор: …" lines in
 * `.book-info`.
 *
 * A book's address is just its slug at the root (`/shekli-robert-absolyutnoe-oruzhie`), the author and title
 * transliterated, so search results are read as the links to such pages; their details come from the pages.
 */
class AknigaProvider : MetadataProvider {
    override val id = "akniga"
    override val name = "Аудиокниги Клуб (akniga.org)"

    override fun searchUrl(query: String) = "$SITE/search/books?q=${URLEncoder.encode(query, Charsets.UTF_8)}"

    override fun isBookUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        if (uri.host?.removePrefix("www.")?.removePrefix("m.") != HOST) return false
        val path = uri.path.orEmpty().trim('/')
        // A single slug with a few words; site sections ("author/…", "search", "section/…") are not books.
        path.matches(BOOK_SLUG) && path.count { it == '-' } >= 2 && path.substringBefore('-') !in SECTIONS
    }.getOrDefault(false)

    override fun parseSearch(html: String, pageUrl: String): List<SiteBook> {
        val doc = Jsoup.parse(html, pageUrl)
        val content = doc.selectFirst(".content__main__articles, .content__main, main") ?: doc.body()
        val cards = content.select(".content__main__articles--item")
        val links = if (cards.isNotEmpty()) cards.mapNotNull { card -> card.select("a[href]").firstOrNull { isBookUrl(it.absUrl("href")) }?.let { it to card } }
        else content.select("a[href]").filter { isBookUrl(it.absUrl("href")) }.map { it to it }
        if (links.isEmpty() && doc.selectFirst("form[action*=search], input[name=q]") == null) {
            throw PageFormatException("No search results or search form on $pageUrl")
        }
        return links.groupBy { (link, _) -> link.absUrl("href").substringBefore('#').substringBefore('?') }.mapNotNull { (url, found) ->
            val card = found.first().second
            val title = PageText.clean(card.selectFirst(".caption__article-main, h2, h3")?.text())
                ?: found.firstNotNullOfOrNull { (link, _) -> PageText.clean(link.text()) }
                ?: return@mapNotNull null
            val author = PageText.names(card, "a[href*=/author/]")
            SiteBook(
                url = url,
                title = PageText.titleWithoutAuthor(title, author),
                author = author,
                narrator = PageText.names(card, "a[href*=/performer/]"),
                series = PageText.series(card.selectFirst("a[href*=/series/]")?.text())?.first,
                durationMs = PageText.duration(card.select(".link__action--label--time, [class*=time]").text()),
                coverUrl = PageText.image(card.selectFirst("img")),
            )
        }
    }

    override fun parseBook(html: String, pageUrl: String): SiteBook? {
        val doc = Jsoup.parse(html, pageUrl)
        val article = doc.selectFirst("article[data-bid]") ?: return null
        val details = details(doc, article)
        val title = PageText.clean(doc.selectFirst("h1[itemprop=name], .caption__article-main h1, h1.caption__article-main")?.text())
            ?: PageText.clean(doc.selectFirst("h1")?.text())
            ?: PageText.meta(doc, "og:title")?.let { it.substringBefore(" - слушать").substringBefore(" — слушать") }
            ?: return null
        val author = details["автор"] ?: PageText.names(article, "a[href*=/author/]")
        val seriesText = details["цикл"] ?: details["серия"] ?: article.selectFirst("a[href*=/series/]")?.text()
        val series = PageText.series(seriesText)
        return SiteBook(
            url = pageUrl,
            title = PageText.titleWithoutAuthor(title, author),
            author = author,
            narrator = details["исполнитель"] ?: details["читает"] ?: PageText.names(article, "a[href*=/performer/]"),
            series = series?.first,
            seriesIndex = series?.second,
            year = PageText.year(details["год"]),
            genre = genre(article),
            description = description(doc),
            durationMs = PageText.duration(details["длительность"] ?: details["время звучания"]),
            coverUrl = PageText.image(article.selectFirst(".book--cover img")) ?: PageText.meta(doc, "og:image"),
        )
    }

    /** The labelled details, by lower-case label: desktop blocks or the mobile "Label: value" lines. */
    private fun details(doc: Element, article: Element): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (block in doc.select(".caption__article--about-block:not(.caption__article--about-block-samebook)")) {
            val copy = block.clone()
            copy.select(".tooltip__main").remove()
            val label = PageText.clean(copy.selectFirst(".content__main__book--item--caption")?.text())?.removeSuffix(":")?.lowercase() ?: continue
            val value = PageText.clean(copy.selectFirst(".content__article--about-content")?.text()) ?: continue
            result.putIfAbsent(label, value)
        }
        for (line in article.select(".book-info .item-info")) {
            val copy = line.clone()
            copy.select(".fa").remove()
            val text = PageText.clean(copy.text()) ?: continue
            val label = text.substringBefore(':', "").trim().lowercase().takeIf { it.isNotEmpty() } ?: continue
            PageText.clean(text.substringAfter(':'))?.let { result.putIfAbsent(label, it) }
        }
        return result
    }

    private fun description(doc: Element): String? {
        val element = doc.selectFirst("article .description__article-main:not(.caption__article--about-block-samebook)")
            ?: doc.selectFirst(".topic-content")
            ?: return null
        val copy = element.clone()
        // Nested blocks are the site's additions (sidebars, sharing), not the book's description.
        copy.select("div, .topic-content-right, .ya-share2").remove()
        return PageText.paragraphs(copy)
    }

    /** "Жанры: Фантастика, Юмор" among the classifiers: the first genre. */
    private fun genre(article: Element): String? =
        article.select(".classifiers__article-main > div").firstOrNull { it.ownText().trim().startsWith("Жанр") }
            ?.let { PageText.names(it, "a", limit = 1) }

    private companion object {
        const val HOST = "akniga.org"
        const val SITE = "https://akniga.org"
        val BOOK_SLUG = Regex("""[a-z0-9]+(?:-[a-z0-9]+)+""")
        val SECTIONS = setOf("author", "performer", "series", "section", "search", "genre", "tag", "blog", "profile", "update", "page")
    }
}
