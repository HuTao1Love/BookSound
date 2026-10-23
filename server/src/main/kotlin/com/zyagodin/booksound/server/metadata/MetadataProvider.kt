package com.zyagodin.booksound.server.metadata

/** A book as a site describes it; whatever the page doesn't say is null. */
data class SiteBook(
    /** The book's own page. */
    val url: String,
    val title: String,
    val author: String? = null,
    val narrator: String? = null,
    val series: String? = null,
    val seriesIndex: String? = null,
    val year: String? = null,
    val genre: String? = null,
    val description: String? = null,
    val durationMs: Long? = null,
    val coverUrl: String? = null,
) {
    /** This book with the gaps filled from [other] (the same book from another page). */
    fun orElse(other: SiteBook) = SiteBook(
        url = url,
        title = title.ifBlank { other.title },
        author = author ?: other.author,
        narrator = narrator ?: other.narrator,
        series = series ?: other.series,
        seriesIndex = seriesIndex ?: other.seriesIndex,
        year = year ?: other.year,
        genre = genre ?: other.genre,
        description = description ?: other.description,
        durationMs = durationMs ?: other.durationMs,
        coverUrl = coverUrl ?: other.coverUrl,
    )
}

/** The site's page isn't what its parser knows: the site changed. */
class PageFormatException(message: String) : Exception(message)

/**
 * An audiobook site. Each one knows only its own pages: where to search and how to read a list of results
 * and a book's page. Fetching, deciding whether a result is the book and caching are shared
 * ([MetadataService]), so another site is just another implementation.
 */
interface MetadataProvider {
    /** Stable id ("akniga"). */
    val id: String
    /** Shown to people. */
    val name: String

    fun searchUrl(query: String): String

    /** Whether [url] is one of this site's book pages (only those are fetched for details). */
    fun isBookUrl(url: String): Boolean

    /**
     * The books on a search page, often with only some details. Empty when nothing was found; throws
     * [PageFormatException] when the page isn't a search page this parser knows.
     */
    fun parseSearch(html: String, pageUrl: String): List<SiteBook>

    /** The book on its own page, or null when the page has none. */
    fun parseBook(html: String, pageUrl: String): SiteBook?
}
