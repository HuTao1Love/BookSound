package com.zyagodin.booksound.watch.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.zyagodin.booksound.core.wear.WatchBookHeader
import com.zyagodin.booksound.core.wear.WatchCodec
import com.zyagodin.booksound.core.wear.WatchPaths
import com.zyagodin.booksound.core.wear.WatchResult
import com.zyagodin.booksound.watch.data.WatchLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.io.File

/** A book on its way from the phone. */
data class IncomingBook(val id: String, val title: String, val receivedBytes: Long, val totalBytes: Long) {
    val progress: Float get() = if (totalBytes > 0) (receivedBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/**
 * Receives books sent by the phone app. The header message and the audio channel can arrive in
 * either order, and the app may be restarted in between, so everything is kept as files in
 * `incoming/`: `<id>.header`, `<id>.part` (written by Play services, even with the app asleep)
 * and `<id>.done` once the phone closed the stream normally. The book joins the library when the
 * header and a complete file are both there.
 */
class BookReceiver(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: WatchLibrary,
) {
    private val dir = File(context.filesDir, "incoming").apply { mkdirs() }
    private val channels = Wearable.getChannelClient(context)
    private val messages = Wearable.getMessageClient(context)
    private val mutex = Mutex()

    private fun header(id: String) = File(dir, "$id.header")
    private fun part(id: String) = File(dir, "$id.part")
    private fun done(id: String) = File(dir, "$id.done")

    /** Books being received, for the UI to show their progress. */
    fun incoming(): List<IncomingBook> = dir.listFiles { f -> f.name.endsWith(".header") }.orEmpty().mapNotNull { file ->
        val header = runCatching { WatchCodec.decodeHeader(file.readBytes()) }.getOrNull() ?: return@mapNotNull null
        val id = header.id.value
        IncomingBook(id, header.metadata.title, part(id).length(), header.fileSize)
    }

    fun onHeader(bytes: ByteArray, sourceNode: String) {
        val header = try {
            WatchCodec.decodeHeader(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable header", e)
            return
        }
        val id = header.id.value
        val oldFile = library.audioFile(id).takeIf { it.exists() }?.length() ?: 0L
        if (library.freeBytes() + oldFile < header.fileSize + RESERVE_BYTES) {
            Log.w(TAG, "No space for $id: ${header.fileSize} bytes")
            reply(sourceNode, id, WatchResult.NoSpace)
            return
        }
        scope.launch {
            mutex.withLock { header(id).writeBytes(bytes) }
            finishIfComplete(id, sourceNode)
        }
    }

    fun onChannelOpened(channel: ChannelClient.Channel) {
        val id = WatchPaths.bookId(channel.path, WatchPaths.BOOK_PREFIX)?.value ?: return
        scope.launch {
            mutex.withLock {
                done(id).delete()
                part(id).delete()
            }
            try {
                // Play services writes the stream into the file, also while this app is asleep.
                channels.receiveFile(channel, Uri.fromFile(part(id)), false).await()
            } catch (e: Exception) {
                Log.w(TAG, "Can't receive $id", e)
                runCatching { channels.close(channel) }
            }
        }
    }

    fun onInputClosed(channel: ChannelClient.Channel, closeReason: Int) {
        val id = WatchPaths.bookId(channel.path, WatchPaths.BOOK_PREFIX)?.value ?: return
        scope.launch {
            if (closeReason == ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL) {
                mutex.withLock { done(id).createNewFile() }
                finishIfComplete(id, channel.nodeId)
            } else {
                Log.w(TAG, "Transfer of $id broke off: $closeReason")
                discard(id)
            }
        }
    }

    /** Clears what transfers that never finished left behind (the phone gave up long ago). */
    fun cleanUp() {
        scope.launch {
            mutex.withLock {
                val stale = System.currentTimeMillis() - STALE_MS
                dir.listFiles().orEmpty().filter { it.lastModified() < stale }.forEach { it.delete() }
            }
        }
    }

    private suspend fun finishIfComplete(id: String, node: String) {
        val result = mutex.withLock {
            if (!header(id).exists() || !done(id).exists()) return
            val header: WatchBookHeader = WatchCodec.decodeHeader(header(id).readBytes())
            val size = part(id).length()
            if (size != header.fileSize) {
                Log.w(TAG, "$id: got $size bytes of ${header.fileSize}")
                WatchResult.Failed("size")
            } else try {
                library.add(header, part(id))
                WatchResult.Saved
            } catch (e: Exception) {
                Log.e(TAG, "Can't keep $id", e)
                WatchResult.Failed(e.javaClass.simpleName)
            }.also {
                header(id).delete()
                done(id).delete()
                part(id).delete()
            }
        }
        Log.i(TAG, "$id: $result")
        reply(node, id, result)
    }

    private suspend fun discard(id: String) = mutex.withLock {
        header(id).delete()
        done(id).delete()
        part(id).delete()
    }

    private fun reply(node: String, id: String, result: WatchResult) {
        scope.launch {
            runCatching { messages.sendMessage(node, WatchPaths.RESULT_PREFIX + id, result.encode()).await() }
                .onFailure { Log.w(TAG, "Can't answer the phone about $id", it) }
        }
    }

    companion object {
        private const val TAG = "BookReceiver"
        /** Room left for the system and other apps. */
        private const val RESERVE_BYTES = 200L * 1024 * 1024
        private const val STALE_MS = 24 * 60 * 60 * 1000L
    }
}
