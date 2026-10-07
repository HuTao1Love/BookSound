package com.zyagodin.booksound.wear

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.zyagodin.booksound.core.model.BookId
import com.zyagodin.booksound.core.model.DeviceId
import com.zyagodin.booksound.core.model.SyncStamp
import com.zyagodin.booksound.core.sync.PlaybackRecord
import com.zyagodin.booksound.core.wear.WatchBookHeader
import com.zyagodin.booksound.core.wear.WatchCodec
import com.zyagodin.booksound.core.wear.WatchPaths
import com.zyagodin.booksound.core.wear.WatchResult
import com.zyagodin.booksound.data.library.LibraryRepository
import com.zyagodin.booksound.data.library.metadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A book on its way to the watch. */
data class WatchTransfer(
    val bookId: String,
    val title: String,
    val sentBytes: Long = 0,
    val totalBytes: Long = 0,
    val stage: Stage = Stage.QUEUED,
) {
    enum class Stage { QUEUED, SENDING, SAVING, DONE, NO_WATCH, NO_SPACE, FAILED, CANCELLED }

    val isActive: Boolean get() = stage == Stage.QUEUED || stage == Stage.SENDING || stage == Stage.SAVING
    val progress: Float? get() = if (stage == Stage.SENDING && totalBytes > 0) sentBytes.toFloat() / totalBytes else null
}

/**
 * Sends books to the BookSound app on a Wear OS watch, one at a time: a header message with the
 * metadata, chapters, cover and listening position, then the audio file through a Data Layer
 * channel. A book counts as sent only when the watch confirms it saved the whole file.
 */
class WatchSender(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: LibraryRepository,
) {
    private val capabilities = Wearable.getCapabilityClient(context)
    private val messages = Wearable.getMessageClient(context)
    private val channels = Wearable.getChannelClient(context)

    private val _transfers = MutableStateFlow<List<WatchTransfer>>(emptyList())
    val transfers: StateFlow<List<WatchTransfer>> = _transfers

    /** Whether a watch with BookSound installed is connected; refreshed by [refreshWatch]. */
    private val _watchConnected = MutableStateFlow(false)
    val watchConnected: StateFlow<Boolean> = _watchConnected

    private var worker: Job? = null
    private var current: Job? = null
    private val results = ConcurrentHashMap<String, CompletableDeferred<WatchResult>>()
    private val resultListener = MessageClient.OnMessageReceivedListener { event ->
        val id = WatchPaths.bookId(event.path, WatchPaths.RESULT_PREFIX) ?: return@OnMessageReceivedListener
        results[id.value]?.complete(WatchResult.decode(event.data))
    }

    fun refreshWatch() {
        scope.launch { _watchConnected.value = watchNode() != null }
    }

    /** Queues [bookId]; a book already queued or being sent is not added twice. */
    fun send(bookId: String, title: String) {
        _transfers.update { list ->
            when {
                list.any { it.bookId == bookId && it.isActive } -> list
                // A new batch: the results of the previous one were already reported.
                list.none { it.isActive } -> listOf(WatchTransfer(bookId, title))
                else -> list.filterNot { it.bookId == bookId } + WatchTransfer(bookId, title)
            }
        }
        startWorker()
        WatchTransferService.start(context)
    }

    fun cancel(bookId: String) {
        val transfer = _transfers.value.firstOrNull { it.bookId == bookId && it.isActive } ?: return
        if (transfer.stage == WatchTransfer.Stage.QUEUED) setStage(bookId, WatchTransfer.Stage.CANCELLED)
        else current?.cancel()
    }

    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch(Dispatchers.IO) {
            messages.addListener(resultListener, Uri.parse("wear://*" + WatchPaths.RESULT_PREFIX), MessageClient.FILTER_PREFIX).await()
            try {
                while (true) {
                    val next = _transfers.value.firstOrNull { it.stage == WatchTransfer.Stage.QUEUED } ?: break
                    val job = launch { sendOne(next) }
                    current = job
                    job.join()
                    if (job.isCancelled) setStage(next.bookId, WatchTransfer.Stage.CANCELLED)
                }
            } finally {
                current = null
                messages.removeListener(resultListener)
            }
        }
    }

    private suspend fun sendOne(transfer: WatchTransfer) {
        val id = transfer.bookId
        val stage = try {
            send(id)
        } catch (e: IOException) {
            Log.w(TAG, "Sending $id failed", e)
            WatchTransfer.Stage.FAILED
        } catch (e: ApiException) {
            Log.w(TAG, "Sending $id failed", e)
            WatchTransfer.Stage.FAILED
        } finally {
            results.remove(id)
        }
        setStage(id, stage)
    }

    private suspend fun send(id: String): WatchTransfer.Stage {
        val book = library.book(id)?.takeIf { !it.deleted } ?: return WatchTransfer.Stage.FAILED
        val node = watchNode() ?: return WatchTransfer.Stage.NO_WATCH
        val result = CompletableDeferred<WatchResult>().also { results[id] = it }
        val header = WatchBookHeader(
            id = BookId(id),
            metadata = book.metadata(),
            durationMs = book.durationMs,
            chapters = library.chapters(id),
            fileSize = book.fileSize,
            fileRevision = book.fileRevision,
            playback = library.playbackState(id)?.let {
                PlaybackRecord(BookId(id), it.positionMs, it.speed, it.finished, it.lastPlayedAt, SyncStamp(it.revision, it.updatedAt, DeviceId(it.updatedBy), dirty = false))
            },
            cover = book.coverPath?.let(::smallCover),
        )
        messages.sendMessage(node.id, WatchPaths.HEADER_PREFIX + id, WatchCodec.encodeHeader(header)).await()
        _transfers.update { list -> list.map { if (it.bookId == id) it.copy(stage = WatchTransfer.Stage.SENDING, sentBytes = 0, totalBytes = book.fileSize) else it } }

        val channel = channels.openChannel(node.id, WatchPaths.BOOK_PREFIX + id).await()
        try {
            channels.getOutputStream(channel).await().use { output ->
                val input = context.contentResolver.openInputStream(Uri.parse(book.fileUri)) ?: throw IOException("Can't open the book file")
                input.use {
                    val buffer = ByteArray(BUFFER_SIZE)
                    var sent = 0L
                    var reported = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        // The watch may refuse the book (no space) while the file is still on its way.
                        if (result.isCompleted) break
                        val n = it.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        sent += n
                        if (sent - reported >= PROGRESS_STEP) {
                            reported = sent
                            _transfers.update { list -> list.map { t -> if (t.bookId == id) t.copy(sentBytes = sent) else t } }
                        }
                    }
                }
            }
            setStage(id, WatchTransfer.Stage.SAVING)
            val answer = withTimeoutOrNull(RESULT_TIMEOUT_MS) { result.await() }
            return when (answer) {
                WatchResult.Saved -> WatchTransfer.Stage.DONE
                WatchResult.NoSpace -> WatchTransfer.Stage.NO_SPACE
                is WatchResult.Failed, null -> {
                    Log.w(TAG, "Watch didn't save $id: $answer")
                    WatchTransfer.Stage.FAILED
                }
            }
        } finally {
            withContext(NonCancellable) { runCatching { channels.close(channel).await() } }
        }
    }

    /** The connected watch with BookSound installed, preferring one connected directly. */
    private suspend fun watchNode(): Node? = try {
        capabilities.getCapability(WatchPaths.WATCH_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
            .nodes.sortedByDescending { it.isNearby }.firstOrNull()
    } catch (e: ApiException) {
        // No Wear OS app on this phone, or Play services without the Wearable API.
        null
    }

    private fun setStage(id: String, stage: WatchTransfer.Stage) =
        _transfers.update { list -> list.map { if (it.bookId == id) it.copy(stage = stage) else it } }

    /** The cover as a small JPEG: a message is limited to 100 KB and the watch screen is tiny. */
    private fun smallCover(path: String): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= COVER_EDGE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
        bitmap?.let {
            val scale = COVER_EDGE.toFloat() / maxOf(it.width, it.height)
            val scaled = if (scale < 1f) Bitmap.createScaledBitmap(it, (it.width * scale).toInt(), (it.height * scale).toInt(), true) else it
            ByteArrayOutputStream().also { out -> scaled.compress(Bitmap.CompressFormat.JPEG, 85, out) }.toByteArray()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Cover $path not readable", e)
        null
    }

    companion object {
        private const val TAG = "WatchSender"
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP = 512 * 1024L
        private const val COVER_EDGE = 256
        /** The watch moves the file into its library and answers; a minute is plenty. */
        private const val RESULT_TIMEOUT_MS = 60_000L
    }
}
