package com.zyagodin.booksound.core.library

import com.zyagodin.booksound.core.model.LibraryEntry
import com.zyagodin.booksound.core.model.SeriesIndex
import com.zyagodin.booksound.core.naming.NaturalOrder
import java.text.Normalizer
import java.util.Locale

enum class SortField { RECENT, ADDED, TITLE, AUTHOR, SERIES, DURATION, PROGRESS }

enum class ProgressFilter { ALL, NOT_STARTED, IN_PROGRESS, FINISHED }

data class LibraryQuery(
    val text: String = "",
    val sort: SortField = SortField.RECENT,
    val descending: Boolean = sort.defaultDescending,
    val filter: ProgressFilter = ProgressFilter.ALL,
)

/** Books of one series (or, with [series] = null, all books outside any series). */
data class SeriesGroup(val series: String?, val entries: List<LibraryEntry>) {
    val authors: List<String> get() = entries.mapNotNull { it.book.metadata.author }.distinct()
    val totalDurationMs: Long get() = entries.sumOf { it.book.durationMs }
    val finishedCount: Int get() = entries.count { it.finished }
    val progress: Float
        get() {
            val total = totalDurationMs
            if (total <= 0) return 0f
            return (entries.sumOf { if (it.finished) it.book.durationMs else it.positionMs.coerceAtMost(it.book.durationMs) }.toFloat() / total).coerceIn(0f, 1f)
        }
}

val SortField.defaultDescending: Boolean
    get() = this == SortField.RECENT || this == SortField.ADDED || this == SortField.PROGRESS

/** Search and sorting shared by the app (and any future server UI). */
object LibrarySearch {

    fun apply(entries: List<LibraryEntry>, query: LibraryQuery): List<LibraryEntry> {
        val tokens = normalize(query.text).split(' ').filter { it.isNotEmpty() }
        // A series with any book matching the progress filter shows up whole, e.g. "In progress"
        // while listening to book 2 still lists books 1 and 3.
        val matchingSeries = entries.filter { matchesFilter(it, query.filter) }
            .mapNotNullTo(HashSet()) { e -> e.book.metadata.series?.let { normalize(it) } }
        val filtered = entries.filter { entry ->
            val inMatchingSeries = entry.book.metadata.series?.let { normalize(it) in matchingSeries } ?: false
            (inMatchingSeries || matchesFilter(entry, query.filter)) && (tokens.isEmpty() || matches(entry, tokens))
        }
        val comparator = comparator(query.sort).let { if (query.descending) it.reversed() else it }
        return filtered.sortedWith(comparator.then(compareBy(NaturalOrder) { it.book.metadata.title }))
    }

    private fun matchesFilter(entry: LibraryEntry, filter: ProgressFilter): Boolean = when (filter) {
        ProgressFilter.ALL -> true
        ProgressFilter.NOT_STARTED -> !entry.finished && entry.positionMs <= 0
        ProgressFilter.IN_PROGRESS -> !entry.finished && entry.positionMs > 0
        ProgressFilter.FINISHED -> entry.finished
    }

    private fun matches(entry: LibraryEntry, tokens: List<String>): Boolean {
        val m = entry.book.metadata
        val haystack = normalize(listOfNotNull(m.title, m.author, m.narrator, m.series, m.year, m.genre).joinToString(" "))
        return tokens.all { haystack.contains(it) }
    }

    private fun comparator(field: SortField): Comparator<LibraryEntry> = when (field) {
        SortField.RECENT -> compareBy<LibraryEntry> { it.lastPlayedAt ?: 0L }.thenBy { it.book.addedAt }
        SortField.ADDED -> compareBy { it.book.addedAt }
        SortField.TITLE -> compareBy(NaturalOrder) { sortableTitle(it.book.metadata.title) }
        SortField.AUTHOR -> compareBy<LibraryEntry, String>(NaturalOrder) { it.book.metadata.author ?: "￿" }
            .thenBy(NaturalOrder) { it.book.metadata.series ?: "￿" }
            .thenBy { SeriesIndex.sortKey(it.book.metadata.seriesIndex) }
        SortField.SERIES -> compareBy<LibraryEntry, String>(NaturalOrder) { it.book.metadata.series ?: "￿" }
            .thenBy { SeriesIndex.sortKey(it.book.metadata.seriesIndex) }
        SortField.DURATION -> compareBy { it.book.durationMs }
        SortField.PROGRESS -> compareBy { it.progress }
    }

    /**
     * Groups already filtered/sorted entries by series. Books without a series come first as one
     * group (series = null); series follow in the order their first book appears in [entries], so
     * the user's sort choice decides which series is on top. Inside a series books are ordered by
     * their number.
     */
    fun groupBySeries(entries: List<LibraryEntry>): List<SeriesGroup> {
        val standalone = entries.filter { it.book.metadata.series == null }
        val bySeries = LinkedHashMap<String, MutableList<LibraryEntry>>()
        for (e in entries) {
            val series = e.book.metadata.series ?: continue
            bySeries.getOrPut(normalize(series)) { mutableListOf() } += e
        }
        val groups = mutableListOf<SeriesGroup>()
        if (standalone.isNotEmpty()) groups += SeriesGroup(null, standalone)
        for (books in bySeries.values) {
            val ordered = books.sortedWith(
                compareBy<LibraryEntry> { SeriesIndex.sortKey(it.book.metadata.seriesIndex) }.thenBy(NaturalOrder) { it.book.metadata.title },
            )
            // Display the most common spelling (first seen wins ties), e.g. "Saga" over "saga".
            val name = books.mapNotNull { it.book.metadata.series }.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
            groups += SeriesGroup(name, ordered)
        }
        return groups
    }

    /** All books of [series] (matched like [groupBySeries] does: ignoring case and accents), ordered by number; null if none. */
    fun seriesGroup(entries: List<LibraryEntry>, series: String): SeriesGroup? {
        val key = normalize(series)
        return groupBySeries(entries.filter { e -> e.book.metadata.series?.let { normalize(it) } == key })
            .firstOrNull { it.series != null }
    }

    /** Lower-case, accent-insensitive form used for matching ("Ёжик" matches "ежик", "Café" matches "cafe"). */
    fun normalize(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(Locale.ROOT).replace('ё', 'е').replace('й', 'и'), Normalizer.Form.NFD)
        return decomposed.replace(Regex("""\p{Mn}+"""), "").replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim()
    }

    /** Ignores leading English articles when sorting by title. */
    private fun sortableTitle(title: String): String =
        title.replace(Regex("""^(the|a|an)\s+""", RegexOption.IGNORE_CASE), "")
}
