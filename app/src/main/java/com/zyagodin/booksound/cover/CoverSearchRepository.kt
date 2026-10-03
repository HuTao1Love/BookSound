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
)

sealed interface CoverSearchResult {
    data class Found(val covers: List<OnlineCover>) : CoverSearchResult
    data object Offline : CoverSearchResult
    data object Failed : CoverSearchResult
}

/**
 * Searches public book catalogues. Each source is queried in parallel; a failing source does not
 * hide results from the others. When nothing is found for title + author (authors are often
 * spelled differently in catalogues), the title alone is tried.
 *
 * Google Books is only used with an API key: without one its quota is zero.
 */
class CoverSearchRepository(private val http: OkHttpClient, private val googleBooksApiKey: String = "") {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun search(title: String, author: String?): CoverSearchResult = withContext(Dispatchers.IO) {
        val cleanTitle = cleanQuery(title)
        val cleanAuthor = author?.let(::cleanQuery)?.takeIf { it.isNotEmpty() }
        if (cleanTitle.isEmpty()) return@withContext CoverSearchResult.Found(emptyList())
        val first = searchOnce(cleanTitle, cleanAuthor)
        if (first is CoverSearchResult.Found && first.covers.isEmpty() && cleanAuthor != null) {
            searchOnce(cleanTitle, null)
        } else {
            first
        }
    }

    private suspend fun searchOnce(title: String, author: String?): CoverSearchResult {
        val query = listOfNotNull(title, author).joinToString(" ")
        val country = Locale.getDefault().country.takeIf { it.length == 2 } ?: "US"
        val networkErrors = java.util.concurrent.atomic.AtomicInteger()
        val sources = buildList<suspend () -> List<OnlineCover>> {
            add { itunes(query, country, "audiobook") }
            if (country != "US") add { itunes(query, "US", "audiobook") }
            add { litres(query) }
            add { openLibrary(query) }
            // Apple sells e-books in many more countries than audiobooks; the covers are the same.
            add { itunes(query, country, "ebook") }
            if (googleBooksApiKey.isNotBlank()) add { googleBooks(title, author) }
        }
        val results = coroutineScope {
            sources.map { source ->
                async {
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
                }
            }.awaitAll()
        }
        // Interleave sources so the first screen shows variety, then drop duplicates.
        val merged = mutableListOf<OnlineCover>()
        val maxLen = results.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) for (list in results) list.getOrNull(i)?.let { merged += it }
        val unique = merged.distinctBy { it.fullUrl }
        return when {
            unique.isNotEmpty() -> CoverSearchResult.Found(unique.take(MAX_RESULTS))
            networkErrors.get() == sources.size -> CoverSearchResult.Offline
            else -> CoverSearchResult.Found(emptyList())
        }
    }

    /** Downloads and validates an image; returns null if it is not a usable picture. */
    suspend fun download(cover: OnlineCover): EmbeddedPicture? = withContext(Dispatchers.IO) {
        val bytes = get(cover.fullUrl)?.takeIf { it.size in 1..MAX_IMAGE_BYTES }
            ?: get(cover.thumbnailUrl)
            ?: return@withContext null
        CoverImages.normalize(bytes)?.takeIf { looksLikeRealCover(it) }
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

    private suspend fun get(url: String): ByteArray? {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        val call = http.newCall(request)
        val response = call.await()
        return response.use { if (it.isSuccessful) it.body.bytes() else null }
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
        /** Drops bracketed noise ("(Unabridged)", "[MP3]") that makes catalogue searches miss. */
        fun cleanQuery(text: String): String = text
            .replace(Regex("""[(\[{][^)\]}]*[)\]}]"""), " ")
            .replace(Regex("""[_|]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('-', '.', ',', ':', ' ')

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
        private const val MAX_RESULTS = 36
        private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
        private const val USER_AGENT = "BookSound/1.0 (Android audiobook player)"
    }
}
