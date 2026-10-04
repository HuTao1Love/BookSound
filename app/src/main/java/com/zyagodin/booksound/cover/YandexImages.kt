package com.zyagodin.booksound.cover

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Yandex image search results, read from its regular search page (there is no free API). The page
 * embeds its results as JSON in HTML attributes: `data-state` on current pages, `data-bem` on
 * older ones. Both are walked leniently: any object with an original image URL ("origUrl" or
 * "img_href") is one result, wherever it is nested.
 */
object YandexImages {
    private val parser = Json { ignoreUnknownKeys = true; isLenient = true }
    private val ATTRIBUTE = Regex("""\bdata-(?:state|bem)=(?:"([^"]*)"|'([^']*)')""")
    private val FULL_KEYS = listOf("origUrl", "img_href")
    private val THUMB_KEYS = listOf("image", "thumb", "preview", "thumbUrl")

    /** yandex.ru for Russian, yandex.com otherwise (same results, local interface). */
    fun searchUrl(query: String, russian: Boolean): String {
        val host = if (russian) "yandex.ru" else "yandex.com"
        return "https://$host/images/search?text=" + java.net.URLEncoder.encode(query, "UTF-8")
    }

    fun parse(html: String, limit: Int = 40): List<OnlineCover> {
        val found = LinkedHashMap<String, OnlineCover>()
        for (match in ATTRIBUTE.findAll(html)) {
            val raw = match.groupValues[1].ifEmpty { match.groupValues[2] }
            if (FULL_KEYS.none { it in raw }) continue
            val element = runCatching { parser.parseToJsonElement(unescapeHtml(raw)) }.getOrNull() ?: continue
            visit(element, found)
            if (found.size >= limit) break
        }
        return found.values.take(limit)
    }

    private fun visit(e: JsonElement, found: MutableMap<String, OnlineCover>) {
        when (e) {
            is JsonObject -> {
                val full = FULL_KEYS.firstNotNullOfOrNull { e.str(it) }?.let(::absolute)?.takeIf { it.startsWith("http") }
                if (full != null) {
                    // One result; its nested "dups" are the same picture in other sizes.
                    val thumb = THUMB_KEYS.firstNotNullOfOrNull { key -> thumbOf(e[key]) } ?: full
                    found.getOrPut(full) { OnlineCover(thumb, full, "Яндекс", titleOf(e), null, fromWeb = true) }
                } else {
                    e.values.forEach { visit(it, found) }
                }
            }
            is JsonArray -> e.forEach { visit(it, found) }
            else -> Unit
        }
    }

    private fun thumbOf(value: JsonElement?): String? = when (value) {
        is JsonPrimitive -> value.contentOrNull
        is JsonObject -> value.str("url") ?: value.str("src")
        else -> null
    }?.let(::absolute)?.takeIf { it.startsWith("http") }

    private fun titleOf(o: JsonObject): String? =
        o.str("alt") ?: (o["snippet"] as? JsonObject)?.str("title") ?: o.str("title")

    /** Yandex thumbnails are protocol-relative ("//avatars.mds.yandex.net/…"). */
    private fun absolute(url: String): String = if (url.startsWith("//")) "https:$url" else url

    private fun unescapeHtml(text: String): String = text
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
