package com.zyagodin.booksound.server.metadata

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Reading the details sites show in similar ways: lengths, series numbers, labelled values, people's names. */
internal object PageText {

    private val clock = Regex("""(?<![\d:])(\d{1,3}):([0-5]\d):([0-5]\d)(?![\d:])""")
    private val hoursMinutes = Regex("""(\d+)\s*(?:час\p{L}*|ч)\.?(?:\s*(\d+)\s*(?:мин\p{L}*|м)\.?)?(?!\p{L})""", RegexOption.IGNORE_CASE)
    private val minutesOnly = Regex("""(\d+)\s*мин\p{L}*""", RegexOption.IGNORE_CASE)
    private val seriesNumber = Regex("""^(.*?)[\s,]*(?:[(\[#№]|книга|кн\.|часть|том)\s*(\d{1,4}(?:[.,]\d{1,2})?)\s*[)\]]?\s*$""", RegexOption.IGNORE_CASE)
    private val yearPattern = Regex("""(?<!\d)(1[5-9]\d\d|20\d\d)(?!\d)""")

    /** A length written as "12:30:05", "12 часов 30 минут", "12 ч. 30 мин." or "45 минут". */
    fun duration(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        clock.find(text)?.let { m ->
            val (h, min, s) = m.destructured
            return ((h.toLong() * 60 + min.toLong()) * 60 + s.toLong()) * 1000
        }
        hoursMinutes.find(text)?.let { m ->
            val hours = m.groupValues[1].toLong()
            val minutes = m.groupValues[2].toLongOrNull() ?: 0
            return (hours * 60 + minutes) * 60_000
        }
        return minutesOnly.find(text)?.let { it.groupValues[1].toLong() * 60_000 }
    }

    /** "Тёмная башня (3)", "Тёмная башня #3", "Тёмная башня, книга 3" → name and number; no number → the name alone. */
    fun series(text: String?): Pair<String, String?>? {
        val clean = text?.let(::clean) ?: return null
        val m = seriesNumber.find(clean) ?: return clean to null
        val name = m.groupValues[1].trim().trimEnd(',', '-', '–', ':').trim()
        return if (name.isEmpty()) clean to null else name to m.groupValues[2].replace(',', '.')
    }

    fun year(text: String?): String? = text?.let { yearPattern.find(it)?.value }

    /** Whitespace collapsed; null when nothing is left. */
    fun clean(text: String?): String? = text?.replace(' ', ' ')?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotEmpty() }

    /** A description's paragraphs kept, other whitespace collapsed. */
    fun paragraphs(element: Element?): String? {
        element ?: return null
        val html = element.html().replace(Regex("""(?i)<br\s*/?>|</p>|</div>"""), "\n")
        return Jsoup.parseBodyFragment(html).body().wholeText()
            .lines().mapNotNull(::clean).joinToString("\n").takeIf { it.isNotEmpty() }?.take(MAX_DESCRIPTION)
    }

    /** The distinct texts of the elements [query] finds in [scope], as one comma-separated list of names. */
    fun names(scope: Element, query: String, limit: Int = 3): String? =
        scope.select(query).mapNotNull { clean(it.text()) }.distinct().take(limit).joinToString(", ").takeIf { it.isNotEmpty() }

    /**
     * The value after a label: for "<span>Год:</span> 2015" or "<li>Год издания: 2015</li>", the "2015".
     * The label's own element is found by its text, the value is what follows it in the parent.
     */
    fun labelled(scope: Element, vararg labels: String): String? {
        for (label in labels) {
            val owner = scope.select("*").firstOrNull { el ->
                // "Год:" but not "Годы жизни".
                val text = el.ownText().trim()
                text.startsWith(label, ignoreCase = true) && (text.length == label.length || !text[label.length].isLetter())
            } ?: continue
            val own = owner.ownText().trim().substring(label.length).trimStart(':', ' ', ' ')
            if (own.isNotBlank()) return clean(own)
            val parentText = clean(owner.parent()?.text()) ?: continue
            val after = parentText.substringAfter(clean(owner.text()) ?: "", "").trimStart(':', ' ')
            if (after.isNotBlank()) return after
        }
        return null
    }

    fun meta(doc: Document, property: String): String? =
        clean(doc.selectFirst("meta[property=$property], meta[name=$property]")?.attr("content"))

    /** An image's address, lazy-loaded ones included. */
    fun image(element: Element?): String? {
        element ?: return null
        for (attr in listOf("data-src", "data-lazy-src", "data-original", "src")) {
            val url = element.absUrl(attr)
            if (url.startsWith("http") && !url.endsWith(".svg") && !url.contains("data:")) return url
        }
        return null
    }

    /**
     * The title without the author in front: lists often show "Булгаков Михаил – Мастер и Маргарита".
     * Only taken off when the part before the dash really is the author.
     */
    fun titleWithoutAuthor(title: String, author: String?): String {
        if (author == null) return title
        val parts = title.split(Regex("""\s+[-–—]\s+"""), limit = 2)
        if (parts.size < 2) return title
        val authorWords = Matching.words(author)
        val firstWords = Matching.words(parts[0])
        return if (firstWords.isNotEmpty() && authorWords.containsAll(firstWords)) parts[1].trim() else title
    }

    private const val MAX_DESCRIPTION = 6000
}
