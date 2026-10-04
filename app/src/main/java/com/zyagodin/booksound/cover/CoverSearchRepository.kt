package com.zyagodin.booksound.cover

import android.util.Log
import com.zyagodin.booksound.core.model.EmbeddedPicture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OnlineCover(
    val thumbnailUrl: String,
    val fullUrl: String,
    val source: String,
    val title: String?,
    val author: String?,
    /** Found by web image search (any website) rather than in a book catalogue. */
    val fromWeb: Boolean = false,
)

sealed interface CoverSearchResult {
    data class Found(val covers: List<OnlineCover>) : CoverSearchResult
    data object Offline : CoverSearchResult
    data object Failed : CoverSearchResult
}

/**
 * Finds cover images for a query. Web image search (Yandex, Bing, DuckDuckGo) comes first, like a
 * regular image search; book catalogues (Apple, LitRes, Open Library) follow. Each source is
 * queried in parallel and a failing source does not hide results from the others. A book in a
 * series is searched as "series title" first (a volume title like "Childhood — Home Tutor" means
 * little on its own), then by its title alone. When nothing is found with the author, the
 * queries are tried without it.
 *
 * Google Books is only used with an API key: without one its quota is zero.
 */
class CoverSearchRepository(private val http: OkHttpClient, private val googleBooksApiKey: String = "") {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun search(title: String, author: String?, series: String? = null): CoverSearchResult = withContext(Dispatchers.IO) {
        val cleanTitle = cleanQuery(title)
        val cleanAuthor = author?.let(::cleanQuery)?.takeIf { it.isNotEmpty() }
        val cleanSeries = series?.let(::cleanQuery)?.takeIf { withSeries(cleanTitle, it) != cleanTitle }
        if (cleanTitle.isEmpty()) return@withContext CoverSearchResult.Found(emptyList())
        val first = searchWithSeries(cleanTitle, cleanAuthor, cleanSeries)
        if (first is CoverSearchResult.Found && first.covers.isEmpty() && cleanAuthor != null) {
            searchWithSeries(cleanTitle, null, cleanSeries)
        } else {
            first
        }
    }

    /** For a book in a series: "series title" (+ author) first, then the title alone, side by side. */
    private suspend fun searchWithSeries(title: String, author: String?, series: String?): CoverSearchResult {
        if (series == null) return searchOnce(title, author)
        val (full, titleOnly) = coroutineScope {
            val f = async { searchOnce(withSeries(title, series), author) }
            val t = async { searchOnce(title, author) }
            f.await() to t.await()
        }
        if (full !is CoverSearchResult.Found && titleOnly !is CoverSearchResult.Found) return full
        val first = (full as? CoverSearchResult.Found)?.covers.orEmpty()
        val second = (titleOnly as? CoverSearchResult.Found)?.covers.orEmpty()
        // "Series title" matches lead; title-only matches come before their long tail.
        val head = first.take(MAX_RESULTS * 2 / 3)
        return CoverSearchResult.Found((head + second + first.drop(head.size)).distinctBy { it.fullUrl }.take(MAX_RESULTS))
    }

    private suspend fun searchOnce(title: String, author: String?): CoverSearchResult {
        val query = listOfNotNull(title, author).joinToString(" ")
        val country = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
        val networkErrors = java.util.concurrent.atomic.AtomicInteger()
        val russian = Locale.getDefault().language == "ru"
        val web = buildList<suspend () -> List<OnlineCover>> {
            // Yandex knows Russian books best; elsewhere it follows the others.
            if (russian) add { yandexImages(query, russian) }
            add { bingImages(query) }
            add { duckDuckGoImages(query) }
            if (!russian) add { yandexImages(query, russian) }
        }
        val catalogues = buildList<suspend () -> List<OnlineCover>> {
            add { itunes(query, country, "audiobook") }
            if (country != "US") add { itunes(query, "US", "audiobook") }
            add { litres(query) }
            add { openLibrary(query) }
            // Apple sells e-books in many more countries than audiobooks; the covers are the same.
            add { itunes(query, country, "ebook") }
            if (googleBooksApiKey.isNotBlank()) add { googleBooks(title, author) }
        }
        val (webResults, catalogueResults) = coroutineScope {
            val w = web.map { async { runSource(it, networkErrors) } }
            val c = catalogues.map { async { runSource(it, networkErrors) } }
            w.awaitAll() to c.awaitAll()
        }
        val unique = (interleave(webResults) + interleave(catalogueResults)).distinctBy { it.fullUrl }
        return when {
            unique.isNotEmpty() -> CoverSearchResult.Found(unique.take(MAX_RESULTS))
            networkErrors.get() == web.size + catalogues.size -> CoverSearchResult.Offline
            else -> CoverSearchResult.Found(emptyList())
        }
    }

    private suspend fun runSource(source: suspend () -> List<OnlineCover>, networkErrors: java.util.concurrent.atomic.AtomicInteger): List<OnlineCover> =
        try {
            source()
        } catch (e: IOException) {
            networkErrors.incrementAndGet()
            Log.i(TAG, "Cover source failed: ${e.message}")
            emptyList()
        } catch (e: RuntimeException) {
            Log.w(TAG, "Cover source returned unexpected data", e)
            emptyList()
        }

    /** Takes results from each source in turn so the first screen shows variety. */
    private fun interleave(lists: List<List<OnlineCover>>): List<OnlineCover> {
        val merged = mutableListOf<OnlineCover>()
        val maxLen = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) for (list in lists) list.getOrNull(i)?.let { merged += it }
        return merged
    }

    /**
     * Downloads and validates an image; returns null if it is not a usable picture. Web results
     * often point to sites that refuse direct downloads or use plain http; the search engine's
     * own thumbnail is used then.
     */
    suspend fun download(cover: OnlineCover): EmbeddedPicture? = withContext(Dispatchers.IO) {
        val candidates = listOfNotNull(
            cover.fullUrl,
            cover.fullUrl.takeIf { it.startsWith("http://") }?.replaceFirst("http://", "https://"),
            cover.thumbnailUrl,
        ).distinct()
        for (url in candidates) {
            val bytes = try {
                get(url, browser = cover.fromWeb)
            } catch (e: IOException) {
                Log.i(TAG, "Image download failed: ${e.message}")
                null
            } ?: continue
            CoverImages.normalize(bytes)?.takeIf { looksLikeRealCover(it) }?.let { return@withContext it }
        }
        null
    }

    private suspend fun bingImages(query: String): List<OnlineCover> {
        val url = "https://www.bing.com/images/async".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("first", "0")
            .addQueryParameter("count", "35")
            .addQueryParameter("mmasync", "1")
            .addQueryParameter("adlt", "moderate")
            .build()
        val html = get(url.toString(), browser = true)?.decodeToString() ?: return emptyList()
        return parseBing(html)
    }

    private suspend fun yandexImages(query: String, russian: Boolean): List<OnlineCover> {
        val html = get(YandexImages.searchUrl(query, russian), browser = true)?.decodeToString() ?: return emptyList()
        return YandexImages.parse(html)
    }

    /** DuckDuckGo needs a per-query token ("vqd") from its search page before the image API answers. */
    private suspend fun duckDuckGoImages(query: String): List<OnlineCover> {
        val page = "https://duckduckgo.com/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("iax", "images")
            .addQueryParameter("ia", "images")
            .build()
        val html = get(page.toString(), browser = true)?.decodeToString() ?: return emptyList()
        val vqd = parseDuckDuckGoToken(html) ?: return emptyList()
        val api = "https://duckduckgo.com/i.js".toHttpUrl().newBuilder()
            .addQueryParameter("l", if (Locale.getDefault().language == "ru") "ru-ru" else "wt-wt")
            .addQueryParameter("o", "json")
            .addQueryParameter("q", query)
            .addQueryParameter("vqd", vqd)
            .addQueryParameter("f", ",,,,,")
            .addQueryParameter("p", "1")
            .build()
        val body = get(api.toString(), browser = true, referer = "https://duckduckgo.com/") ?: return emptyList()
        val root = json.parseToJsonElement(body.decodeToString()) as? JsonObject ?: return emptyList()
        return parseDuckDuckGo(root)
    }

    private suspend fun itunes(query: String, country: String, media: String): List<OnlineCover> {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", query)
            .addQueryParameter("media", media)
            .addQueryParameter("limit", "15")
            .addQueryParameter("country", country)
            .build()
        return getJson(url.toString())?.let(::parseItunes).orEmpty()
    }

    /** LitRes: the largest catalogue of Russian books and audiobooks. */
    private suspend fun litres(query: String): List<OnlineCover> {
        val url = "https://api.litres.ru/foundation/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "15")
            .addQueryParameter("types", "audiobook")
            .addQueryParameter("types", "text_book")
            .build()
        return getJson(url.toString())?.let(::parseLitres).orEmpty()
    }

    private suspend fun openLibrary(query: String): List<OnlineCover> {
        // Free text query: the picker passes "title author" as one string, a title= search would miss it.
        val url = "https://openlibrary.org/search.json".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", "15")
            .addQueryParameter("fields", "title,author_name,cover_i")
            .build()
        return getJson(url.toString())?.let(::parseOpenLibrary).orEmpty()
    }

    private suspend fun googleBooks(title: String, author: String?): List<OnlineCover> {
        val q = buildString {
            append(title)
            if (!author.isNullOrBlank()) append(" inauthor:").append(author)
        }
        val url = "https://www.googleapis.com/books/v1/volumes".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("maxResults", "15")
            .addQueryParameter("printType", "books")
            .addQueryParameter("key", googleBooksApiKey)
            .build()
        return getJson(url.toString())?.let(::parseGoogleBooks).orEmpty()
    }

    /** Google returns a tiny "image not available" placeholder for some volumes. */
    private fun looksLikeRealCover(picture: EmbeddedPicture): Boolean = picture.bytes.size > 3_000

    private suspend fun getJson(url: String): JsonObject? {
        val body = get(url) ?: return null
        return json.parseToJsonElement(body.decodeToString()) as? JsonObject
    }

    /** GETs [url]; null for an error status or a body larger than [MAX_IMAGE_BYTES]. */
    private suspend fun get(url: String, browser: Boolean = false, referer: String? = null): ByteArray? {
        val request = Request.Builder().url(url)
            .header("User-Agent", if (browser) BROWSER_USER_AGENT else USER_AGENT)
            .header("Accept-Language", Locale.getDefault().toLanguageTag() + ",en;q=0.8")
            .apply { if (referer != null) header("Referer", referer) }
            .build()
        val response = http.newCall(request).await()
        return response.use { r ->
            if (!r.isSuccessful) return@use null
            val body = r.body
            if (body.contentLength() > MAX_IMAGE_BYTES) return@use null
            val out = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_IMAGE_BYTES) return@use null
                }
            }
            out.toByteArray()
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    companion object {
        /**
         * "Series Title" for a volume of a series, so that "Childhood — Home Tutor" is searched
         * as "Mushoku Tensei Childhood — Home Tutor"; the title alone when it already names the
         * series or there is none.
         */
        fun withSeries(title: String, series: String?): String {
            val s = series?.trim().orEmpty()
            return if (s.isEmpty() || title.contains(s, ignoreCase = true)) title else "$s $title"
        }

        /** Drops bracketed noise ("(Unabridged)", "[MP3]") that makes catalogue searches miss. */
        fun cleanQuery(text: String): String = text
            .replace(Regex("""[(\[{][^)\]}]*[)\]}]"""), " ")
            .replace(Regex("""[_|]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('-', '.', ',', ':', ' ')

        /** Bing marks each result with an HTML-escaped JSON attribute: m="{&quot;murl&quot;:…}". */
        fun parseBing(html: String): List<OnlineCover> {
            val parser = Json { ignoreUnknownKeys = true; isLenient = true }
            return Regex("""\bm=(?:"(\{[^"]*\})"|'(\{[^']*\})')""").findAll(html).mapNotNull { match ->
                val raw = match.groupValues[1].ifEmpty { match.groupValues[2] }
                val o = runCatching { parser.parseToJsonElement(unescapeHtml(raw)) as? JsonObject }.getOrNull() ?: return@mapNotNull null
                val full = o.str("murl") ?: return@mapNotNull null
                val thumb = o.str("turl") ?: full
                OnlineCover(thumb, full, "Bing", o.str("t"), null, fromWeb = true)
            }.toList()
        }

        fun parseDuckDuckGoToken(html: String): String? =
            Regex("""vqd=["']?([0-9]+-[0-9a-zA-Z_-]+)""").find(html)?.groupValues?.get(1)

        fun parseDuckDuckGo(root: JsonObject): List<OnlineCover> = (root["results"] as? JsonArray).orEmpty().mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            val full = o.str("image") ?: return@mapNotNull null
            OnlineCover(o.str("thumbnail") ?: full, full, "DuckDuckGo", o.str("title"), null, fromWeb = true)
        }

        private fun unescapeHtml(text: String): String = text
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")

        fun parseItunes(root: JsonObject): List<OnlineCover> = (root["results"] as? JsonArray).orEmpty().mapNotNull { r ->
            val o = r as? JsonObject ?: return@mapNotNull null
            val art = o.str("artworkUrl100") ?: o.str("artworkUrl60") ?: return@mapNotNull null
            OnlineCover(
                thumbnailUrl = art.replace(ARTWORK_SIZE, "300x300bb"),
                fullUrl = art.replace(ARTWORK_SIZE, "1000x1000bb"),
                source = "Apple Books",
                title = o.str("collectionName") ?: o.str("trackName"),
                author = o.str("artistName"),
            )
        }

        fun parseOpenLibrary(root: JsonObject): List<OnlineCover> = (root["docs"] as? JsonArray).orEmpty().mapNotNull { d ->
            val o = d as? JsonObject ?: return@mapNotNull null
            val id = (o["cover_i"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            OnlineCover(
                thumbnailUrl = "https://covers.openlibrary.org/b/id/$id-M.jpg",
                fullUrl = "https://covers.openlibrary.org/b/id/$id-L.jpg",
                source = "Open Library",
                title = o.str("title"),
                author = (o["author_name"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull },
            )
        }

        fun parseGoogleBooks(root: JsonObject): List<OnlineCover> = (root["items"] as? JsonArray).orEmpty().mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val info = o["volumeInfo"] as? JsonObject ?: return@mapNotNull null
            if (info["imageLinks"] == null) return@mapNotNull null
            val base = "https://books.google.com/books/content?id=$id&printsec=frontcover&img=1&zoom=1&source=gbs_api"
            OnlineCover(
                thumbnailUrl = base,
                fullUrl = "$base&fife=w1000",
                source = "Google Books",
                title = info.str("title"),
                author = (info["authors"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull },
            )
        }

        /**
         * LitRes has no documented public API; the response is walked leniently so that any
         * object with a title and a cover URL counts as a book, wherever it is nested.
         */
        fun parseLitres(root: JsonElement): List<OnlineCover> {
            val out = mutableListOf<OnlineCover>()
            fun visit(e: JsonElement) {
                when (e) {
                    is JsonObject -> {
                        val cover = e.str("cover_url") ?: e.str("cover")
                        val title = e.str("title")
                        if (cover != null && title != null && out.size < 15) {
                            val thumb = if (cover.startsWith("/")) "https://www.litres.ru$cover" else cover
                            val author = (e["persons"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                                ?.firstOrNull { it.str("role") == "author" }?.str("full_name")
                            out += OnlineCover(thumb, thumb.replace(Regex("""/cover_\d+/"""), "/cover_max1500/"), "Литрес", title, author)
                        } else {
                            e.values.forEach(::visit)
                        }
                    }
                    is JsonArray -> e.forEach(::visit)
                    else -> Unit
                }
            }
            visit(root)
            return out.filter { it.thumbnailUrl.startsWith("https://") }
        }

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

        private const val ARTWORK_SIZE = "100x100bb"
        private const val TAG = "CoverSearch"
        private const val MAX_RESULTS = 60
        private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
        private const val USER_AGENT = "BookSound/1.0 (Android audiobook player)"

        /** Image search sites serve their regular pages only to browsers. */
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    }
}
