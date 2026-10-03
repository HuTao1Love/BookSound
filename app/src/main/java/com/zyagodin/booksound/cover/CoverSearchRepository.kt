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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 * Searches public book catalogues that need no API key. Each source is queried in parallel;
 * a failing source does not hide results from the others.
 */
class CoverSearchRepository(private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun search(title: String, author: String?): CoverSearchResult = withContext(Dispatchers.IO) {
        val query = listOfNotNull(title.trim().takeIf { it.isNotEmpty() }, author?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" ")
        if (query.isBlank()) return@withContext CoverSearchResult.Found(emptyList())
        val networkErrors = java.util.concurrent.atomic.AtomicInteger()
        val sources: List<suspend () -> List<OnlineCover>> = listOf(
            { itunes(query, Locale.getDefault().country.ifBlank { "US" }) },
            { itunes(query, "US") },
            { openLibrary(title, author) },
            { googleBooks(title, author) },
        )
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
        when {
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

    private suspend fun itunes(query: String, country: String): List<OnlineCover> {
        val url = "https://itunes.apple.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("term", query)
            .addQueryParameter("media", "audiobook")
            .addQueryParameter("limit", "15")
            .addQueryParameter("country", country)
            .build()
        val root = getJson(url.toString()) ?: return emptyList()
        return root.obj("results")?.let { it as? JsonArray }.orEmpty().mapNotNull { r ->
            val o = r.jsonObject
            val art = o.str("artworkUrl100") ?: return@mapNotNull null
            OnlineCover(
                thumbnailUrl = art.replace("100x100bb", "300x300bb"),
                fullUrl = art.replace("100x100bb", "1000x1000bb"),
                source = "Apple Books",
                title = o.str("collectionName") ?: o.str("trackName"),
                author = o.str("artistName"),
            )
        }
    }

    private suspend fun openLibrary(title: String, author: String?): List<OnlineCover> {
        val url = "https://openlibrary.org/search.json".toHttpUrl().newBuilder()
            .addQueryParameter("title", title)
            .apply { if (!author.isNullOrBlank()) addQueryParameter("author", author) }
            .addQueryParameter("limit", "15")
            .addQueryParameter("fields", "title,author_name,cover_i")
            .build()
        val root = getJson(url.toString()) ?: return emptyList()
        return root.obj("docs")?.let { it as? JsonArray }.orEmpty().mapNotNull { d ->
            val o = d.jsonObject
            val id = o["cover_i"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            OnlineCover(
                thumbnailUrl = "https://covers.openlibrary.org/b/id/$id-M.jpg",
                fullUrl = "https://covers.openlibrary.org/b/id/$id-L.jpg",
                source = "Open Library",
                title = o.str("title"),
                author = (o["author_name"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    private suspend fun googleBooks(title: String, author: String?): List<OnlineCover> {
        val q = buildString {
            append("intitle:").append(title)
            if (!author.isNullOrBlank()) append(" inauthor:").append(author)
        }
        val url = "https://www.googleapis.com/books/v1/volumes".toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("maxResults", "15")
            .addQueryParameter("printType", "books")
            .build()
        val root = getJson(url.toString()) ?: return emptyList()
        return root.obj("items")?.let { it as? JsonArray }.orEmpty().mapNotNull { item ->
            val o = item.jsonObject
            val id = o.str("id") ?: return@mapNotNull null
            val info = o["volumeInfo"]?.jsonObject ?: return@mapNotNull null
            if (info["imageLinks"] == null) return@mapNotNull null
            val base = "https://books.google.com/books/content?id=$id&printsec=frontcover&img=1&zoom=1&source=gbs_api"
            OnlineCover(
                thumbnailUrl = base,
                fullUrl = "$base&fife=w1000",
                source = "Google Books",
                title = info.str("title"),
                author = (info["authors"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    /** Google returns a tiny "image not available" placeholder for some volumes. */
    private fun looksLikeRealCover(picture: EmbeddedPicture): Boolean = picture.bytes.size > 3_000

    private suspend fun getJson(url: String): JsonObject? {
        val body = get(url) ?: return null
        return json.parseToJsonElement(body.decodeToString()).jsonObject
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

    private fun JsonObject.str(key: String): String? = this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
    private fun JsonObject.obj(key: String): JsonElement? = this[key]

    companion object {
        private const val TAG = "CoverSearch"
        private const val MAX_RESULTS = 36
        private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
        private const val USER_AGENT = "BookSound/1.0 (Android audiobook player)"
    }
}
