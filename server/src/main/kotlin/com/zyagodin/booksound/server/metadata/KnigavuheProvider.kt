package com.zyagodin.booksound.server.metadata

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * knigavuhe.org. Search results are `div.bookkitem` cards (`span.bookkitemm` on the mobile host) with the
 * author, the narrator ("-reader" block) and the length; a book's page has schema.org microdata (name,
 * author, description), the narrator as a `/reader/` link in the title, "Время звучания: 12:34:56", and
 * a "Все книги из цикла" block where the book itself is the `<strong>` item.
 */
class KnigavuheProvider : MetadataProvider {
    override val id = "knigavuhe"
    override val name = "Книга в ухе (knigavuhe.org)"

    override fun searchUrl(query: String) = "$SITE/search/?q=${URLEncoder.encode(query, Charsets.UTF_8)}"

    override fun isBookUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.host?.removePrefix("www.")?.removePrefix("m.") == HOST && uri.path.orEmpty().startsWith("/book/")
    }.getOrDefault(false)

    override fun parseSearch(html: String, pageUrl: String): List<SiteBook> {
        val doc = Jsoup.parse(html, pageUrl)
        val cards = doc.select("div.bookkitem, span.bookkitemm")
        if (cards.isEmpty() && doc.selectFirst("form[action*=search], input[name=q]") == null) {
            throw PageFormatException("No search results or search form on $pageUrl")
        }
        return cards.mapNotNull { card ->
            val link = card.selectFirst("a.bookkitem_name[href]") ?: card.selectFirst("a.bookkitem_cover[href]") ?: return@mapNotNull null
            val url = link.absUrl("href").takeIf(::isBookUrl) ?: return@mapNotNull null
            // A card that only leads to LitRes has no audio here: not the recording anyone has.
            if (card.selectFirst(".bookkitem_icon.-litres") != null) return@mapNotNull null
            SiteBook(
                url = url,
                title = PageText.clean(link.text()) ?: return@mapNotNull null,
                author = PageText.names(card, ".bookkitem_author a") ?: metaBlock(card, "-author"),
                narrator = metaBlock(card, "-reader"),
                genre = PageText.names(card, ".bookkitem_genre a", limit = 1),
                description = PageText.clean(card.selectFirst(".bookkitem_about")?.text()),
                durationMs = PageText.duration(card.selectFirst(".bookkitem_meta_time")?.text()),
                coverUrl = PageText.image(card.selectFirst("img.bookkitem_cover_img")),
            )
        }.distinctBy { it.url }
    }

    override fun parseBook(html: String, pageUrl: String): SiteBook? {
        val doc = Jsoup.parse(html, pageUrl)
        val heading = doc.selectFirst(".page_title h1") ?: doc.selectFirst("h1")
        val title = PageText.clean(heading?.selectFirst("[itemprop=name]")?.text())
            ?: PageText.clean(doc.selectFirst(".book_title_name")?.text())
            ?: return null
        val (series, seriesIndex) = series(doc)
        return SiteBook(
            url = pageUrl,
            title = title,
            author = PageText.names(doc, "[itemprop=author] a") ?: heading?.let { PageText.names(it, "a[href^=/author/]") },
            narrator = heading?.let { PageText.names(it, "a[href^=/reader/]") },
            series = series,
            seriesIndex = seriesIndex,
            genre = PageText.names(doc, ".book_genre_pretitle a", limit = 1),
            description = PageText.paragraphs(doc.selectFirst("[itemprop=description]") ?: doc.selectFirst(".book_description")),
            durationMs = PageText.duration(PageText.labelled(doc.selectFirst(".book_blue_block") ?: doc.body(), "Время звучания")),
            coverUrl = PageText.image(doc.selectFirst(".book_cover img")) ?: PageText.meta(doc, "og:image"),
        )
    }

    /**
     * The series and the book's number from the "Все книги из цикла …" block. The same class also marks
     * "Другие озвучки" (other narrations of this book), which has no series link.
     */
    private fun series(doc: Element): Pair<String?, String?> {
        val block = doc.select(".book_serie_block").firstOrNull { it.selectFirst(".book_serie_title a[href^=/series/]") != null }
            ?: return null to null
        val name = PageText.clean(block.selectFirst(".book_serie_title a[href^=/series/]")?.text())
        val items = block.select(".book_serie_item")
        val current = items.indexOfFirst { it.selectFirst("strong") != null }
        val printed = items.getOrNull(current)?.selectFirst(".book_serie_item_index")?.text()?.trim()?.removeSuffix(".")
        val index = printed?.takeIf { it.toDoubleOrNull() != null } ?: (current + 1).takeIf { current >= 0 }?.toString()
        return name to index
    }

    /** The links of the card's meta block marked with the [icon] modifier ("-reader", "-author"). */
    private fun metaBlock(card: Element, icon: String): String? =
        card.select(".bookkitem_meta_block").firstOrNull { it.selectFirst(".bookkitem_icon.$icon") != null }
            ?.let { PageText.names(it, "a") }

    private companion object {
        const val HOST = "knigavuhe.org"
        const val SITE = "https://knigavuhe.org"
    }
}
