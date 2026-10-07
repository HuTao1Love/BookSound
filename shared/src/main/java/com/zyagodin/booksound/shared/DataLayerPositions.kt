package com.zyagodin.booksound.shared

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.zyagodin.booksound.core.sync.PlaybackRecord
import com.zyagodin.booksound.core.wear.WatchCodec
import com.zyagodin.booksound.core.wear.WatchPaths
import kotlinx.coroutines.tasks.await

/**
 * Listening positions shared between the phone and the watch as Data Layer items, one per book
 * and device. Wear OS delivers an item whenever the two are connected again, so a position saved
 * with the phone out of reach still arrives later.
 */
class DataLayerPositions(context: Context) {
    private val data = Wearable.getDataClient(context)
    private val nodes = Wearable.getNodeClient(context)
    @Volatile private var localNode: String? = null

    suspend fun localNodeId(): String = localNode ?: nodes.localNode.await().id.also { localNode = it }

    /** Publishes this device's position in a book. */
    suspend fun publish(record: PlaybackRecord) {
        val request = PutDataRequest.create(WatchPaths.POSITION_PREFIX + record.bookId.value)
            .setData(WatchCodec.encodePosition(record))
            .setUrgent()
        data.putDataItem(request).await()
    }

    /** Positions the other devices published. */
    suspend fun remote(): List<PlaybackRecord> {
        val local = localNodeId()
        // A URI without a host matches the items of every node.
        val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(WatchPaths.POSITION_PREFIX).build()
        val buffer = data.getDataItems(uri, DataClient.FILTER_PREFIX).await()
        return try {
            buffer.filter { it.uri.host != local }.mapNotNull(::decode)
        } finally {
            buffer.release()
        }
    }

    companion object {
        /** The position in [item], or null when it is something else or unreadable. */
        fun decode(item: DataItem): PlaybackRecord? {
            if (item.uri.path?.startsWith(WatchPaths.POSITION_PREFIX) != true) return null
            return item.data?.let { runCatching { WatchCodec.decodePosition(it) }.getOrNull() }
        }
    }
}
