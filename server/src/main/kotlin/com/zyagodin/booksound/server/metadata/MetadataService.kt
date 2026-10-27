package com.zyagodin.booksound.server.metadata

import com.zyagodin.booksound.api.MatchVerdict
import com.zyagodin.booksound.api.MetadataCandidateDto
import com.zyagodin.booksound.api.MetadataDto
import com.zyagodin.booksound.api.MetadataMatchDto
import com.zyagodin.booksound.api.MetadataSearchRequest
import com.zyagodin.booksound.api.MetadataSearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Collections

/** Fetches a page or an image from a site. */
interface PageFetcher {
    suspend fun text(url: String): String
    suspend fun bytes(url: String, limit: Long): ByteArray

    /**
     * Downloads [url] into [target], refusing more than [limit] bytes. A [target] that already has bytes is
     * continued from where it stopped. [progress] gets the bytes so far and the total when known.
     */
    suspend fun download(url: String, target: File, limit: Long, progress: (Long, Long?) -> Unit) {
        target.writeBytes(bytes(url, limit))
        progress(target.length(), target.length())
    }
}

/**
 * Looks books up on audiobook sites ([MetadataProvider]s) and tells, for each result, whether it is the
 * book searched for ([MetadataMatcher]). Each site is searched once; only its likeliest results are opened,
 * since their pages say what lists leave out (the length, the narrator, the description).
 */
class MetadataService(
    val providers: List<MetadataProvider> = listOf(AknigaProvider(), KnigavuheProvider()),
    private val fetcher: PageFetcher = HttpPageFetcher(),
) {
    /** Cover addresses handed out by searches: the only ones [cover] fetches, so it can't be pointed elsewhere. */
    private val coversGiven: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(object : LinkedHashMap<String, Boolean>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > MAX_REMEMBERED_COVERS
        }),
    )

    suspend fun search(request: MetadataSearchRequest): MetadataSearchResponse = coroutineScope {
        val facts = BookFacts(request.title.trim(), request.author?.trim(), request.narrator?.trim(), request.durationMs)
        val query = request.query?.trim()?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(facts.title, facts.author?.let(::firstPerson)).joinToString(" ")
        val results = providers.map { provider ->
            async {
                provider to runCatching { withTimeoutOrNull(SOURCE_TIMEOUT_MS) { searchOne(provider, query, facts) } ?: throw IOException("Timed out") }
            }
        }.awaitAll()
        results.forEach { (provider, result) -> result.exceptionOrNull()?.let { log.warn("Metadata search on {} failed: {}", provider.id, it.toString()) } }
        val matches = results.flatMap { it.second.getOrNull().orEmpty() }
            .sortedWith(compareBy<MetadataMatchDto> { it.verdict.ordinal }.thenByDescending { it.score })
            .take(MAX_RESULTS)
        matches.mapNotNullTo(coversGiven) { it.candidate.coverUrl }
        MetadataSearchResponse(matches, results.filter { it.second.isFailure }.map { it.first.name })
    }

    /** A cover a search returned, or null for any other address. */
    suspend fun cover(url: String): ByteArray? {
        if (url !in coversGiven) return null
        return fetcher.bytes(url, MAX_COVER_BYTES)
    }

    private suspend fun searchOne(provider: MetadataProvider, query: String, facts: BookFacts): List<MetadataMatchDto> = coroutineScope {
        val searchUrl = provider.searchUrl(query)
        val found = provider.parseSearch(fetcher.text(searchUrl), searchUrl)
        val likeliest = found
            .map { it to MetadataMatcher.match(facts, it) }
            .filter { it.second.verdict != MatchVerdict.DIFFERENT }
            .sortedByDescending { it.second.score }
            .take(PAGES_PER_SOURCE)
        likeliest.map { (brief, _) ->
            async {
                val page = runCatching { provider.parseBook(fetcher.text(brief.url), brief.url) }
                    .onFailure { log.debug("Reading {} failed: {}", brief.url, it.toString()) }
                    .getOrNull()
                val book = page?.orElse(brief) ?: brief
                val match = MetadataMatcher.match(facts, book)
                if (match.verdict == MatchVerdict.DIFFERENT) null else book.toDto(provider, match)
            }
        }.awaitAll().filterNotNull()
    }

    private fun SiteBook.toDto(provider: MetadataProvider, match: MatchResult) = MetadataMatchDto(
        candidate = MetadataCandidateDto(
            source = provider.id,
            sourceName = provider.name,
            url = url,
            metadata = MetadataDto(
                title = title,
                author = author,
                narrator = narrator,
                series = series,
                seriesIndex = seriesIndex,
                year = year,
                genre = genre,
                description = description,
            ),
            durationMs = durationMs,
            coverUrl = coverUrl,
        ),
        verdict = match.verdict,
        score = match.score,
        title = match.title,
        author = match.author,
        narrator = match.narrator,
        duration = match.duration,
    )

    /** The first of several authors: searching for all of them finds less. */
    private fun firstPerson(people: String): String = people.split(',', ';', '&').first().trim()

    companion object {
        private val log = LoggerFactory.getLogger(MetadataService::class.java)
        private const val PAGES_PER_SOURCE = 4
        private const val MAX_RESULTS = 12
        private const val SOURCE_TIMEOUT_MS = 25_000L
        private const val MAX_REMEMBERED_COVERS = 500
        const val MAX_COVER_BYTES = 10L * 1024 * 1024
    }
}

/** Fetches pages like a browser would (some sites answer bots differently), with a short in-memory cache. */
class HttpPageFetcher : PageFetcher {
    private val http: HttpClient = HttpClient.newBuilder()
        .proxy(ProxySelector.getDefault())
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    private val cache = object : LinkedHashMap<String, Pair<Long, String>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, String>>?) = size > CACHE_SIZE
    }

    override suspend fun text(url: String): String {
        val now = System.currentTimeMillis()
        synchronized(cache) { cache[url]?.takeIf { now - it.first < CACHE_MS }?.let { return it.second } }
        val response = send(url, "text/html,application/xhtml+xml")
        val text = String(response, Charsets.UTF_8)
        synchronized(cache) { cache[url] = now to text }
        return text
    }

    override suspend fun bytes(url: String, limit: Long): ByteArray {
        val bytes = send(url, "image/*")
        if (bytes.size > limit) throw IOException("Too large: $url")
        return bytes
    }

    override suspend fun download(url: String, target: File, limit: Long, progress: (Long, Long?) -> Unit) = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            try {
                downloadOnce(url, target, limit, progress)
                return@withContext
            } catch (e: IOException) {
                // A dropped connection continues where it stopped; a refusal or a full disk doesn't get better.
                if (++attempt >= DOWNLOAD_ATTEMPTS || e is RefusedException) throw e
                log.info("Download of {} interrupted ({}), continuing", url, e.toString())
                kotlinx.coroutines.delay(2_000L * attempt)
            }
        }
    }

    private suspend fun downloadOnce(url: String, target: File, limit: Long, progress: (Long, Long?) -> Unit) {
        val have = if (target.isFile) target.length() else 0L
        val builder = request(url, "*/*", Duration.ofMinutes(30))
        if (have > 0) builder.header("Range", "bytes=$have-")
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
        response.body().use { body ->
            val status = response.statusCode()
            // 416: asked to continue past the end, the file is whole.
            if (status == 416 && have > 0) return
            if (status !in 200..299) throw RefusedException("HTTP $status from $url")
            val resumed = status == 206
            val length = response.headers().firstValueAsLong("Content-Length").let { if (it.isPresent) it.asLong else -1L }
            val total = if (length >= 0) length + (if (resumed) have else 0) else null
            if (total != null && total > limit) throw RefusedException("Too large: $url")
            java.io.FileOutputStream(target, resumed).use { out ->
                var written = if (resumed) have else 0L
                val buffer = ByteArray(256 * 1024)
                val ctx = currentCoroutineContext()
                while (true) {
                    ctx.ensureActive()
                    val n = body.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    written += n
                    if (written > limit) throw RefusedException("Too large: $url")
                    progress(written, total)
                }
                if (total != null && written < total) throw IOException("Connection closed at $written of $total bytes")
            }
        }
    }

    private fun request(url: String, accept: String, timeout: Duration): HttpRequest.Builder {
        val uri = URI(url)
        if (uri.scheme != "https" && uri.scheme != "http") throw RefusedException("Not a web address: $url")
        return HttpRequest.newBuilder(uri)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.5")
            .timeout(timeout)
            .GET()
    }

    private suspend fun send(url: String, accept: String): ByteArray = withContext(Dispatchers.IO) {
        val response = http.send(request(url, accept, Duration.ofSeconds(15)).build(), HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) throw IOException("HTTP ${response.statusCode()} from $url")
        response.body()
    }

    /** A download that trying again won't help. */
    private class RefusedException(message: String) : IOException(message)

    private companion object {
        // The desktop layout: the parsers know it (the mobile host serves other markup).
        const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64; rv:135.0) Gecko/20100101 Firefox/135.0"
        const val CACHE_SIZE = 200
        const val CACHE_MS = 60 * 60 * 1000L
        const val DOWNLOAD_ATTEMPTS = 5
        val log = LoggerFactory.getLogger(HttpPageFetcher::class.java)!!
    }
}
