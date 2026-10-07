package com.zyagodin.booksound.wear

import android.content.Context
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.zyagodin.booksound.core.model.BookId
import com.zyagodin.booksound.core.model.DeviceId
import com.zyagodin.booksound.core.sync.BackendStatus
import com.zyagodin.booksound.core.sync.ChangeSet
import com.zyagodin.booksound.core.sync.FileSink
import com.zyagodin.booksound.core.sync.PullResult
import com.zyagodin.booksound.core.sync.PushResult
import com.zyagodin.booksound.core.sync.SyncBackend
import com.zyagodin.booksound.core.sync.SyncCursor
import com.zyagodin.booksound.core.sync.SyncLocalStore
import com.zyagodin.booksound.core.wear.WatchPaths
import com.zyagodin.booksound.shared.DataLayerPositions
import kotlinx.coroutines.tasks.await
import java.io.IOException
import java.io.InputStream

/**
 * The watch as a sync backend: listening positions travel as Data Layer items (see
 * [DataLayerPositions]). Books go to the watch separately, by hand, through [WatchSender].
 */
class WatchSyncBackend(context: Context) : SyncBackend {
    private val positions = DataLayerPositions(context)
    private val capabilities = Wearable.getCapabilityClient(context)

    /** Available while a watch with BookSound is paired, even out of reach: items wait for it. */
    override suspend fun status(): BackendStatus = try {
        val watches = capabilities.getCapability(WatchPaths.WATCH_CAPABILITY, CapabilityClient.FILTER_ALL).await().nodes
        if (watches.isEmpty()) BackendStatus.NotConfigured else BackendStatus.Available("wear")
    } catch (e: Exception) {
        // No Wear OS app on this phone, or no Play services.
        BackendStatus.NotConfigured
    }

    override suspend fun pull(since: SyncCursor?): PullResult =
        PullResult(ChangeSet(playback = dataLayer { positions.remote() }), SyncCursor(""), hasMore = false)

    override suspend fun push(changes: ChangeSet, device: DeviceId): PushResult {
        dataLayer { changes.playback.forEach { positions.publish(it) } }
        return PushResult(changes.playback.associate { it.bookId to it.stamp.revision }, emptyList())
    }

    override suspend fun downloadFile(bookId: BookId, revision: Int, sink: FileSink, onProgress: (Long, Long) -> Unit) =
        throw UnsupportedOperationException("The watch doesn't serve files")

    override suspend fun uploadFile(bookId: BookId, revision: Int, sizeBytes: Long, open: () -> InputStream, onProgress: (Long, Long) -> Unit) =
        throw UnsupportedOperationException("Books are sent with WatchSender")

    override suspend fun downloadCover(bookId: BookId): ByteArray? = null

    override suspend fun uploadCover(bookId: BookId, bytes: ByteArray, mimeType: String) = Unit

    /** The sync engine treats IOExceptions as "try again later". */
    private inline fun <T> dataLayer(block: () -> T): T = try {
        block()
    } catch (e: com.google.android.gms.common.api.ApiException) {
        throw IOException("Data Layer: ${e.statusCode}", e)
    }
}

/**
 * Only listening positions go to the watch. Book records keep their dirty flag for a future
 * server, which would otherwise never learn about them.
 */
class PlaybackOnlyStore(private val delegate: SyncLocalStore) : SyncLocalStore by delegate {
    override suspend fun dirtyChanges(): ChangeSet = ChangeSet(playback = delegate.dirtyChanges().playback)
}
