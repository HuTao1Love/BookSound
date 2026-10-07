package com.zyagodin.booksound.watch.surfaces

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import com.zyagodin.booksound.watch.data.WatchLibrary
import com.zyagodin.booksound.watch.data.WatchSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** The book in the player, as the playback service last saw it. */
data class NowPlaying(val bookId: String, val playing: Boolean)

/** What the tile and the complication show: the book in the player, or the one listened to last. */
data class Shown(
    val bookId: String,
    val title: String,
    /** The current chapter's title, or the author. */
    val subtitle: String?,
    val percent: Int,
    val finished: Boolean,
    /** In the player: it can be paused and skipped without opening the app. */
    val loaded: Boolean,
    val playing: Boolean,
)

/** Keeps the tile and the complication up to date with playback and the library. */
class WatchSurfaces(
    private val context: Context,
    scope: CoroutineScope,
    private val library: WatchLibrary,
    private val settings: WatchSettings,
) {
    /** Set by the playback service; null while it is not running. */
    val nowPlaying = MutableStateFlow<NowPlaying?>(null)

    init {
        // Positions are saved every few seconds: only a change the user can see is pushed.
        scope.launch {
            combine(nowPlaying, library.books) { _, _ -> current() }
                .distinctUntilChanged()
                .collect { requestUpdates() }
        }
    }

    fun current(): Shown? {
        val playing = nowPlaying.value
        val book = playing?.bookId?.let(library::book)
            ?: settings.lastBookId?.let(library::book)
            ?: library.books.value.firstOrNull()
            ?: return null
        val chapter = book.chapters.lastOrNull { it.startMs <= book.positionMs }
        val loaded = playing?.bookId == book.id
        return Shown(
            bookId = book.id,
            title = book.metadata.title,
            subtitle = chapter?.title?.takeIf { it.isNotBlank() } ?: book.metadata.author,
            percent = (book.progress * 100).roundToInt(),
            finished = book.finished,
            loaded = loaded,
            playing = loaded && playing?.playing == true,
        )
    }

    private fun requestUpdates() {
        TileService.getUpdater(context).requestUpdate(BookTileService::class.java)
        ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, BookComplicationService::class.java)).requestUpdateAll()
    }
}
