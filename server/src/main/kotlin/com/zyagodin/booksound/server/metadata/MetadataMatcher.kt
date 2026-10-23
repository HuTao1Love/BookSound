package com.zyagodin.booksound.server.metadata

import com.zyagodin.booksound.api.MatchState
import com.zyagodin.booksound.api.MatchVerdict
import com.zyagodin.booksound.core.library.LibrarySearch
import kotlin.math.abs
import kotlin.math.max

/** Comparing names that sites and files write differently: case, "ё", word order, initials, punctuation. */
internal object Matching {
    /** The meaningful words of [text], normalized ("Булгаков, Михаил А." → булгаков, михаил). */
    fun words(text: String?): Set<String> =
        LibrarySearch.normalize(text.orEmpty()).split(' ').filter { it.length > 1 || it.firstOrNull()?.isDigit() == true }.toSet()

    /** How much two titles share, 0..1: the share of the shorter one's words found in the other. */
    fun titleSimilarity(a: String, b: String): Double {
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val common = x.count { it in y }.toDouble()
        val containment = common / minOf(x.size, y.size)
        val dice = 2 * common / (x.size + y.size)
        // "Мастер и Маргарита" inside "Мастер и Маргарита (спектакль)" is a close match, not a perfect one.
        return max(dice, containment * 0.9)
    }

    /**
     * Whether two lists of people name the same person at least once: a shared surname is enough
     * ("Булгаков Михаил" and "М. А. Булгаков"), which is how sites and tags differ.
     */
    fun samePeople(a: String?, b: String?): MatchState {
        val x = words(a).filter { it.length > 2 }
        val y = words(b).filter { it.length > 2 }.toSet()
        if (x.isEmpty() || y.isEmpty()) return MatchState.UNKNOWN
        return if (x.any { it in y }) MatchState.MATCH else MatchState.CONFLICT
    }
}

/** What the book searched for is known to be. */
data class BookFacts(
    val title: String,
    val author: String? = null,
    val narrator: String? = null,
    val durationMs: Long? = null,
)

data class MatchResult(
    val verdict: MatchVerdict,
    val score: Double,
    val title: MatchState,
    val author: MatchState,
    val narrator: MatchState,
    val duration: MatchState,
)

/**
 * Tells whether a site's book is the one searched for. A title alone proves little (there are many
 * "Пикник на обочине"s and many recordings of each), so the author has to agree for the same book, and the
 * narrator or the length (a recording's fingerprint: sites round it to the minute) for the same recording.
 */
object MetadataMatcher {

    fun match(book: BookFacts, site: SiteBook): MatchResult {
        val titleSimilarity = Matching.titleSimilarity(book.title, site.title)
        val title = when {
            titleSimilarity >= TITLE_MATCH -> MatchState.MATCH
            titleSimilarity < TITLE_CONFLICT -> MatchState.CONFLICT
            else -> MatchState.UNKNOWN
        }
        val author = Matching.samePeople(book.author, site.author)
        val narrator = Matching.samePeople(book.narrator, site.narrator)
        val duration = duration(book.durationMs, site.durationMs)

        val verdict = when {
            title == MatchState.CONFLICT || author == MatchState.CONFLICT -> MatchVerdict.DIFFERENT
            title == MatchState.MATCH && author == MatchState.MATCH ->
                if (narrator != MatchState.CONFLICT && duration != MatchState.CONFLICT && (narrator == MatchState.MATCH || duration == MatchState.MATCH)) {
                    MatchVerdict.SAME_RECORDING
                } else {
                    MatchVerdict.SAME_BOOK
                }
            else -> MatchVerdict.DOUBTFUL
        }

        // For ordering only: how much agrees, out of what could be compared.
        var score = titleSimilarity * WEIGHT_TITLE
        var possible = WEIGHT_TITLE
        fun add(state: MatchState, weight: Double) {
            if (state == MatchState.UNKNOWN) return
            possible += weight
            if (state == MatchState.MATCH) score += weight
        }
        add(author, WEIGHT_AUTHOR)
        add(narrator, WEIGHT_NARRATOR)
        add(duration, WEIGHT_DURATION)
        return MatchResult(verdict, score / possible, title, author, narrator, duration)
    }

    private fun duration(a: Long?, b: Long?): MatchState {
        if (a == null || b == null || a <= 0 || b <= 0) return MatchState.UNKNOWN
        val difference = abs(a - b).toDouble() / max(a, b)
        // Sites round to the minute and some count the silence between files: a few minutes either way is the same.
        return when {
            difference <= DURATION_MATCH || abs(a - b) <= DURATION_SLACK_MS -> MatchState.MATCH
            difference >= DURATION_CONFLICT -> MatchState.CONFLICT
            else -> MatchState.UNKNOWN
        }
    }

    private const val TITLE_MATCH = 0.75
    private const val TITLE_CONFLICT = 0.34
    private const val DURATION_MATCH = 0.02
    private const val DURATION_SLACK_MS = 3 * 60_000L
    private const val DURATION_CONFLICT = 0.08
    private const val WEIGHT_TITLE = 0.35
    private const val WEIGHT_AUTHOR = 0.25
    private const val WEIGHT_NARRATOR = 0.2
    private const val WEIGHT_DURATION = 0.2
}
