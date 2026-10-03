package com.zyagodin.booksound.core.organize

import com.zyagodin.booksound.core.model.SeriesIndex

/** Book fields a name template can fill. */
enum class NameField { AUTHOR, TITLE, SERIES, NUMBER, NARRATOR, YEAR, SKIP }

/**
 * User-defined pattern for reading book details out of a folder, file or torrent name, e.g.
 * `%author% - %series% - Том %number% - %title% [%narrator%]` for
 * "Рифудзин на Магонотэ - Реинкарнация безработного - Том 16 - Молодость - Бог человеческий [HEDGEHOG INC]".
 *
 * Text between placeholders must appear as written (case-insensitive; any dash matches any dash,
 * spacing is flexible). Placeholders take as little text as possible, except the last one, which
 * runs to the end. `%number%` and `%year%` only match numbers.
 */
object NameTemplate {

    /** Placeholder names, English and Russian. */
    val ALIASES: Map<String, NameField> = mapOf(
        "author" to NameField.AUTHOR, "автор" to NameField.AUTHOR,
        "title" to NameField.TITLE, "name" to NameField.TITLE, "название" to NameField.TITLE,
        "series" to NameField.SERIES, "book" to NameField.SERIES, "cycle" to NameField.SERIES,
        "серия" to NameField.SERIES, "цикл" to NameField.SERIES,
        "number" to NameField.NUMBER, "index" to NameField.NUMBER, "volume" to NameField.NUMBER,
        "номер" to NameField.NUMBER, "том" to NameField.NUMBER,
        "narrator" to NameField.NARRATOR, "reader" to NameField.NARRATOR, "чтец" to NameField.NARRATOR,
        "year" to NameField.YEAR, "год" to NameField.YEAR,
        "*" to NameField.SKIP, "skip" to NameField.SKIP, "any" to NameField.SKIP,
    )

    val DEFAULTS = listOf(
        "%author% - %title%",
        "%author% - %title% [%narrator%]",
        "%author% - %series% %number% - %title%",
        "%author% - %series% - Том %number% - %title% [%narrator%]",
    )

    sealed interface Problem {
        data object NoPlaceholders : Problem
        data class UnknownPlaceholder(val name: String) : Problem
        data class Duplicate(val field: NameField) : Problem

        /** Two text placeholders with nothing between them can't be told apart. */
        data object AdjacentPlaceholders : Problem
    }

    private val PLACEHOLDER = Regex("%([^%\\s]+)%")
    private val DASHES = "-–—"
    private val TRAILING_NOISE = Regex("""\s*(?:[\[(][^\])]*[\])]|~+[^~]*~+)\s*$""")

    private sealed interface Token {
        data class Text(val value: String) : Token
        data class Field(val field: NameField) : Token
    }

    fun validate(template: String): Problem? {
        val tokens = tokenize(template)
        tokens.firstNotNullOfOrNull { (it as? UnknownToken)?.name }?.let { return Problem.UnknownPlaceholder(it) }
        val fields = tokens.filterIsInstance<Token.Field>().map { it.field }
        if (fields.none { it != NameField.SKIP }) return Problem.NoPlaceholders
        fields.filter { it != NameField.SKIP }.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.let { return Problem.Duplicate(it.key) }
        val known = tokens.filterNot { it is UnknownToken }
        for (i in 0 until known.size - 1) {
            val a = known[i] as? Token.Field ?: continue
            val b = known[i + 1] as? Token.Field ?: continue
            if (!a.field.isNumeric() && !b.field.isNumeric()) return Problem.AdjacentPlaceholders
        }
        return null
    }

    /**
     * Reads [name] with [template]. Returns the filled fields (without [NameField.SKIP]), or null
     * when the template is invalid or the name doesn't have its shape. Trailing bracketed parts
     * such as "[MP3]" may be left out of the template.
     */
    fun parse(template: String, name: String): Map<NameField, String>? {
        if (validate(template) != null) return null
        val regex = toRegex(template)
        // The name without trailing "[…]"/"(…)" parts first, so they don't end up in the last
        // field; then with them, for templates that read them (e.g. "[%narrator%]").
        val candidates = generateSequence(name.trim()) { c -> TRAILING_NOISE.replace(c, "").trim().takeIf { it != c && it.isNotEmpty() } }
            .toList()
            .asReversed()
        return candidates.firstNotNullOfOrNull { regex.matchEntire(it) }?.let { values(template, it) }
    }

    private fun values(template: String, match: MatchResult): Map<NameField, String> {
        val fields = tokenize(template).filterIsInstance<Token.Field>().map { it.field }
        val out = LinkedHashMap<NameField, String>()
        fields.forEachIndexed { i, field ->
            if (field == NameField.SKIP) return@forEachIndexed
            val raw = match.groupValues[i + 1].replace(Regex("\\s+"), " ").trim().trim(' ', '-', '–', '—', '_', ',', '.')
            val value = if (field == NameField.NUMBER) SeriesIndex.normalize(raw) else raw
            if (!value.isNullOrEmpty()) out[field] = value
        }
        return out
    }

    private fun toRegex(template: String): Regex {
        val tokens = tokenize(template)
        val sb = StringBuilder()
        val lastField = tokens.indexOfLast { it is Token.Field }
        tokens.forEachIndexed { index, token ->
            when (token) {
                is Token.Field -> sb.append(
                    when (token.field) {
                        NameField.NUMBER -> "(\\d{1,4}(?:[.,]\\d{1,3})?)"
                        NameField.YEAR -> "((?:1[89]|20)\\d{2})"
                        else -> if (index == lastField) "(.+)" else "(.+?)"
                    },
                )
                is Token.Text -> sb.append(literal(token.value))
                is UnknownToken -> Unit
            }
        }
        return Regex(sb.toString(), setOf(RegexOption.IGNORE_CASE))
    }

    /** Escapes literal text; whitespace becomes flexible, any dash matches any dash. */
    private fun literal(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> {
                    var j = i
                    while (j < text.length && text[j].isWhitespace()) j++
                    // Around punctuation spacing is optional ("Author -Title"); between words it is required.
                    val before = text.getOrNull(i - 1)
                    val after = text.getOrNull(j)
                    val wordBoundary = (before == null || before.isLetterOrDigit()) && (after == null || after.isLetterOrDigit())
                    sb.append(if (wordBoundary) "\\s+" else "\\s*")
                    i = j
                    continue
                }
                c in DASHES -> sb.append("[").append(DASHES).append("]")
                else -> sb.append(Regex.escape(c.toString()))
            }
            i++
        }
        return sb.toString()
    }

    private data class UnknownToken(val name: String) : Token

    private fun tokenize(template: String): List<Token> {
        val out = mutableListOf<Token>()
        var pos = 0
        for (m in PLACEHOLDER.findAll(template)) {
            if (m.range.first > pos) out += Token.Text(template.substring(pos, m.range.first))
            val key = m.groupValues[1].lowercase()
            out += ALIASES[key]?.let { Token.Field(it) } ?: UnknownToken(m.groupValues[1])
            pos = m.range.last + 1
        }
        if (pos < template.length) out += Token.Text(template.substring(pos))
        return out
    }

    private fun NameField.isNumeric() = this == NameField.NUMBER || this == NameField.YEAR
}
