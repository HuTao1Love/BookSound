package com.zyagodin.booksound.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.zyagodin.booksound.core.model.BookId
import com.zyagodin.booksound.core.wear.PhoneBook
import com.zyagodin.booksound.core.wear.WatchCodec
import com.zyagodin.booksound.core.wear.WatchPaths
import com.zyagodin.booksound.data.library.LibraryRepository
import com.zyagodin.booksound.data.library.metadata
import com.zyagodin.booksound.data.settings.SettingsRepository
import com.zyagodin.booksound.playback.PlayerConnection
import com.zyagodin.booksound.playback.PlayerUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Lets the watch play the phone's book: tells the watch which book the phone plays or would
 * resume (see [WatchPaths.PHONE_BOOK_PATH]) and carries out its play and pause commands.
 */
class WatchRemote(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
    private val settings: SettingsRepository,
    private val player: PlayerConnection,
) {
    private data class State(val bookId: String, val playing: Boolean, val positionMs: Long)

    private val state = MutableStateFlow<State?>(null)

    init {
        scope.launch {
            state.filterNotNull().distinctUntilChanged().collectLatest { publish(it) }
        }
    }

    /** The playback service's book changed, started or stopped. */
    fun update(bookId: String?, playing: Boolean, positionMs: Long) {
        if (bookId != null) state.value = State(bookId, playing, positionMs)
    }

    /** At app start, before the player said anything: the book a play command would resume. */
    fun restore() {
        scope.launch {
            val id = settings.current().lastBookId ?: return@launch
            val position = library.playbackState(id)?.positionMs ?: 0L
            state.compareAndSet(null, State(id, playing = false, positionMs = position))
        }
    }

    /** A command from the watch; the phone may be locked and the app in the background. */
    fun onCommand(command: String) {
        scope.launch(Dispatchers.Main) {
            when (command) {
                WatchPaths.COMMAND_PLAY -> {
                    val bookId = player.state.value.bookId ?: settings.current().lastBookId ?: return@launch
                    controlled(until = { it.isPlaying || it.problem != null }) { player.play(bookId) }
                }
                WatchPaths.COMMAND_PAUSE -> controlled(until = { it.connected && !it.playWhenReady }) { player.pause() }
                else -> Log.w(TAG, "Unknown command $command")
            }
        }
    }

    /**
     * Holds the connection to the playback service until the command took effect: released
     * earlier, the service could stop before it starts playing in the foreground.
     */
    private suspend fun controlled(until: (PlayerUiState) -> Boolean, command: () -> Unit) {
        player.connect()
        try {
            command()
            withTimeoutOrNull(COMMAND_TIMEOUT_MS) { player.state.first(until) }
        } finally {
            player.disconnect()
        }
    }

    private suspend fun publish(state: State) {
        val book = withContext(Dispatchers.IO) { library.book(state.bookId) }?.takeIf { !it.deleted } ?: return
        val metadata = book.metadata()
        val phoneBook = PhoneBook(BookId(book.id), metadata.title, metadata.author, state.playing, state.positionMs, book.durationMs)
        try {
            val request = PutDataRequest.create(WatchPaths.PHONE_BOOK_PATH).setData(WatchCodec.encodePhoneBook(phoneBook)).setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // No Wear OS app on this phone, or no Play services: nobody to tell.
            Log.d(TAG, "Can't publish the book for the watch", e)
        }
    }

    private companion object {
        const val TAG = "WatchRemote"
        const val COMMAND_TIMEOUT_MS = 15_000L
    }
}
