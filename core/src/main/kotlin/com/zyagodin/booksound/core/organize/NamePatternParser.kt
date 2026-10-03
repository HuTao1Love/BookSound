package com.zyagodin.booksound.core.organize

import com.zyagodin.booksound.core.model.SeriesIndex

/** Metadata guessed from a folder or file name such as "Author - Series 03 - Title (read by X) [2014]". */
data class NameGuess(
    val title: String? = null,
    val author: String? = null,
    val narrator: String? = null,
    val series: String? = null,
    val seriesIndex: String? = null,
    val year: String? = null,
)

object NamePatternParser {

    private val SEPARATOR = Regex("""\s+[-–—]\s+""")
    private val YEAR = Regex("""[(\[{]\s*((?:19|20)\d{2})\s*[)\]}]""")

    /** Year inside a comma separated list such as "[2007, MP3, 128 kbps]" (common for torrents). */
    private val YEAR_IN_LIST = Regex("""[(\[{]([^)\]}]*,[^)\]}]*)[)\]}]""")
    private val YEAR_TOKEN = Regex("""(?<!\d)((?:19|20)\d{2})(?!\d)""")
    private val NARRATOR_BRACKET = Regex(
        """[(\[{]\s*(?:read by|narrated by|narrator|reader|читает|читают|чтец|исп\.?|исполнитель)\s*:?\s*([^)\]}]+)[)\]}]""",
        RegexOption.IGNORE_CASE,
    )
    private val NARRATOR_TEXT = Regex(
        """(?:read by|narrated by|narrator:|reader:|читает|читают|чтец:?|исполнитель:?)\s*:?\s*([^,;.\n()\[\]]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val NOISE_BRACKET = Regex(
        """[(\[{][^)\]}]*(?:kbps|kbit|mp3|m4b|aac|audiobook|аудиокнига|unabridged|abridged|\d{2,3}\s*k)[^)\]}]*[)\]}]""",
        RegexOption.IGNORE_CASE,
    )
    private val EMPTY_BRACKETS = Regex("""[(\[{]\s*[)\]}]""")

    /** "Series 03", "Series #3", "Series, Book 3", "Series Book 3", "Series, книга 3", "Series vol. 3" */
    private val SERIES_WITH_NUMBER = Regex(
        """^(.+?)[\s,]*(?:#|№|book|книга|кн\.|том|vol\.?|volume|part|часть)?\s*(\d{1,4}(?:[.,]\d{1,2})?)$""",
        RegexOption.IGNORE_CASE,
    )
    private val LEADING_NUMBER = Regex("""^(\d{1,4}(?:[.,]\d)?)\s*[.\-_)]?\s+(.+)$""")
    private val BRACKETED_SERIES = Regex("""^[\[(](.+?)\s*[#№]?\s*(\d{1,4}(?:[.,]\d{1,2})?)[\])]\s*(.+)$""")

    fun parse(rawName: String): NameGuess {
        var name = rawName.substringBeforeLast('.', rawName).takeIf { rawName.contains('.') && looksLikeExtension(rawName) } ?: rawName
        name = name.replace('_', ' ').replace(Regex("""\s+"""), " ").trim()

        val year = YEAR.find(name)?.groupValues?.get(1)
            ?: YEAR_IN_LIST.findAll(name).firstNotNullOfOrNull { list ->
                list.groupValues[1].split(',').firstNotNullOfOrNull { YEAR_TOKEN.matchEntire(it.trim())?.groupValues?.get(1) }
            }
        val narrator = NARRATOR_BRACKET.find(name)?.groupValues?.get(1)?.trim()
        name = NARRATOR_BRACKET.replace(name, " ")
        name = YEAR.replace(name, " ")
        name = NOISE_BRACKET.replace(name, " ")
        name = EMPTY_BRACKETS.replace(name, " ").replace(Regex("""\s+"""), " ").trim().trim('-', ' ')

        val parts = name.split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
        var guess = when (parts.size) {
            0 -> NameGuess()
            1 -> parseTitleOnly(parts[0])
            2 -> parseTwo(parts[0], parts[1])
            else -> {
                val seriesPart = parseSeries(parts.subList(1, parts.size - 1).joinToString(" - "))
                NameGuess(
                    author = parts[0],
                    title = parts.last(),
                    series = seriesPart?.first,
                    seriesIndex = seriesPart?.second,
                )
            }
        }
        guess = guess.copy(year = year, narrator = narrator)
        return guess
    }

    /** Extracts a narrator from free text such as a comment ("Read by John Smith"). */
    fun narratorFromText(text: String?): String? =
        text?.let { NARRATOR_TEXT.find(it)?.groupValues?.get(1)?.trim()?.takeIf { n -> n.length in 2..80 } }

    /** Parses "Series #3" / "Series, Book 3" style values; returns series name and normalized index. */
    fun parseSeries(text: String?): Pair<String, String?>? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val m = SERIES_WITH_NUMBER.find(value)
        if (m != null) {
            val series = m.groupValues[1].trim().trimEnd(',', '-', ':', ' ')
            val index = SeriesIndex.normalize(m.groupValues[2])
            if (series.isNotEmpty() && series.any { it.isLetter() }) return series to index
        }
        return value to null
    }

    private fun parseTwo(first: String, second: String): NameGuess {
        // "03 - Title" → numbered item without an author.
        if (first.all { it.isDigit() || it == '.' } && SeriesIndex.normalize(first) != null) {
            return NameGuess(title = second, seriesIndex = SeriesIndex.normalize(first))
        }
        val titleGuess = parseTitleOnly(second)
        return titleGuess.copy(author = first)
    }

    private fun parseTitleOnly(text: String): NameGuess {
        BRACKETED_SERIES.find(text)?.let { m ->
            return NameGuess(
                title = m.groupValues[3].trim(),
                series = m.groupValues[1].trim(),
                seriesIndex = SeriesIndex.normalize(m.groupValues[2]),
            )
        }
        LEADING_NUMBER.find(text)?.let { m ->
            if (m.groupValues[2].any { it.isLetter() }) {
                return NameGuess(title = m.groupValues[2].trim(), seriesIndex = SeriesIndex.normalize(m.groupValues[1]))
            }
        }
        return NameGuess(title = text)
    }

    private fun looksLikeExtension(name: String): Boolean {
        val ext = name.substringAfterLast('.')
        return ext.length in 2..4 && ext.all { it.isLetterOrDigit() } && ext.any { it.isLetter() }
    }
}
