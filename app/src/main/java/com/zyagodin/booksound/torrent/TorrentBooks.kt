package com.zyagodin.booksound.torrent

import com.zyagodin.booksound.core.torrent.TorrentContentCheck
import com.zyagodin.booksound.core.torrent.TorrentSuggestions
import java.util.UUID

/**
 * Fills this record with the torrent's content. A torrent of several books gives one record per
 * book: this record becomes the first one (an open editor keeps showing it), the others are new.
 */
internal fun TorrentRecord.withContent(meta: TorrentMeta, content: TorrentContentCheck, chapterName: (Int) -> String): List<TorrentRecord> = when (content) {
    is TorrentContentCheck.Valid -> listOf(withBook(this, meta, content, 0, chapterName))
    is TorrentContentCheck.Collection -> {
        val group = UUID.randomUUID().toString()
        content.books.mapIndexed { i, book ->
            val base = if (i == 0) this else copy(id = UUID.randomUUID().toString(), bookId = UUID.randomUUID().toString())
            withBook(base, meta, book, i, chapterName).copy(group = group, volume = book.volume, position = i)
        }
    }
    is TorrentContentCheck.Invalid -> throw IllegalArgumentException("Invalid content: ${content.problem}")
}

private fun withBook(record: TorrentRecord, meta: TorrentMeta, content: TorrentContentCheck.Valid, position: Int, chapterName: (Int) -> String): TorrentRecord {
    val suggestion = TorrentSuggestions.build(meta.name, content, position, chapterName)
    return record.copy(
        name = meta.name,
        layout = content.layout,
        files = meta.files.map { StoredFile(it.index, it.path, it.sizeBytes, it.isPadding) },
        wanted = content.wantedIndices,
        suggested = StoredMetadata.of(suggestion.metadata),
        parts = suggestion.parts.map { StoredPart(it.fileIndex, it.path, it.title, it.title, it.sizeBytes) },
    )
}
