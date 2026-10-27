package com.zyagodin.booksound.server.metadata

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URI

/** A knigavuhe book's details and chapters as `/ajax/book_data/<id>/` gives them. */
data class KnigavuheBook(
    val id: Int,
    val title: String,
    val authors: String?,
    val readers: String?,
    val series: String?,
    val coverUrl: String?,
    val tracks: List<Track>,
) {
    /** One chapter: an mp3 on the site's servers. */
    data class Track(val title: String, val url: String, val durationMs: Long)

    val durationMs: Long get() = tracks.sumOf { it.durationMs }
}

/** Why a knigavuhe book can't be taken, worded for people. */
class KnigavuheException(message: String) : Exception(message)

/**
 * knigavuhe.org's player data. `GET /ajax/book_data/<id>/` needs no account and answers with a two-element
 * array, `[error, data]`: an error comes in the first element, not as an HTTP status. In `data.result.init_data`:
 * `book` (id, name, cover), `playlist` (a chapter each: title, a direct mp3 `url`, `duration_float`, `error`)
 * and `covers` (full size). The people come as strings in a chapter's `player_data` (the book's own `authors`
 * is an object, or an empty array when there are none). A book whose rights went to LitRes has a single
 * zero-length chapter linking to their shop instead of audio.
 *
 * A book's numeric id is in most addresses (`/book/5230-garri-potter/`); slug-only ones need the page, where it
 * is in the player's set-up script.
 */
object KnigavuheAudio {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val idInUrl = Regex("""/book/(\d+)-""")
    private val idInPage = listOf(Regex("""new BookPlayer\((\d+)"""), Regex("""cur\.book\s*=\s*\{"id":(\d+)"""))

    fun bookDataUrl(id: Int) = "https://knigavuhe.org/ajax/book_data/$id/"

    fun idFromUrl(url: String): Int? = idInUrl.find(url)?.groupValues?.get(1)?.toIntOrNull()

    fun idFromPage(html: String): Int? = idInPage.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1)?.toIntOrNull() }

    /** The book page for an address someone pasted (mobile and www hosts too), or null when it isn't one. */
    fun bookPage(input: String): String? {
        val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "https" && uri.scheme != "http") return null
        if (uri.host?.removePrefix("www.")?.removePrefix("m.") != "knigavuhe.org") return null
        val path = uri.rawPath.orEmpty()
        if (!path.matches(Regex("""/book/[\w%.-]+/?"""))) return null
        return "https://knigavuhe.org" + path.trimEnd('/') + "/"
    }

    /** Only the site's own servers are fetched from: an mp3 or a cover anywhere else is not downloaded. */
    fun isSiteFile(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host.orEmpty()
        uri.scheme == "https" && (host == "knigavuhe.org" || host.endsWith(".knigavuhe.org"))
    }.getOrDefault(false)

    fun parse(text: String, id: Int): KnigavuheBook {
        val array = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonArray
            ?: throw KnigavuheException("The site answered with something other than book data")
        val error = array.getOrNull(0)
        if (error != null && error != JsonNull) throw KnigavuheException("The site has no such book (${error.toString().take(200)})")
        val init = (array.getOrNull(1) as? JsonObject)?.obj("result")?.obj("init_data")
            ?: throw KnigavuheException("The site's book data has no player data")
        val book = init.obj("book") ?: throw KnigavuheException("The site's book data has no book")
        val playlist = (init["playlist"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val tracks = playlist.mapNotNull { t ->
            val url = t.str("url") ?: return@mapNotNull null
            if ((t.int("error") ?: 0) != 0 || !isSiteFile(url)) return@mapNotNull null
            val seconds = t.double("duration_float") ?: t.double("duration") ?: 0.0
            KnigavuheBook.Track(t.str("title").orEmpty(), url, (seconds * 1000).toLong())
        }
        if (tracks.isEmpty()) {
            val litres = playlist.any { it.str("url")?.contains("litres.ru", ignoreCase = true) == true }
            throw KnigavuheException(if (litres) "The book is not on the site, only on LitRes" else "The book has no chapters to download")
        }
        val people = playlist.firstNotNullOfOrNull { it.obj("player_data") }
        val covers = (init["covers"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val cover = covers.firstNotNullOfOrNull { it.str("src") } ?: book.str("cover") ?: people?.str("cover")
        return KnigavuheBook(
            id = init.int("id")?.takeIf { it != 0 } ?: id,
            title = book.str("name") ?: people?.str("title") ?: throw KnigavuheException("The book has no title"),
            authors = people?.str("authors"),
            readers = people?.str("readers"),
            series = people?.str("series"),
            coverUrl = cover?.takeIf(::isSiteFile),
            tracks = tracks,
        )
    }

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.prim(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it != JsonNull }
    private fun JsonObject.str(key: String): String? = prim(key)?.content?.trim()?.takeIf { it.isNotEmpty() }
    private fun JsonObject.int(key: String): Int? = prim(key)?.intOrNull
    private fun JsonObject.double(key: String): Double? = prim(key)?.doubleOrNull
    private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
