package com.zyagodin.booksound.watch.sync

import android.content.Context
import android.util.Log
import com.zyagodin.booksound.core.sync.PlaybackRecord
import com.zyagodin.booksound.shared.DataLayerPositions
import com.zyagodin.booksound.watch.data.WatchLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Shares listening positions with the phone (see [DataLayerPositions]). */
class PositionSync(context: Context, private val scope: CoroutineScope, private val library: WatchLibrary) {
    private val positions = DataLayerPositions(context)

    /** Publishes a position saved here; Wear OS delivers it whenever the phone is reachable. */
    fun publish(record: PlaybackRecord) {
        scope.launch {
            runCatching { positions.publish(record) }.onFailure { Log.w(TAG, "Can't publish ${record.bookId}", it) }
        }
    }

    /** Positions that arrived from the phone, as (source node, record). */
    fun receive(records: List<Pair<String?, PlaybackRecord>>) {
        scope.launch {
            val local = runCatching { positions.localNodeId() }.getOrNull()
            library.mergeRemote(records.filter { it.first != local }.map { it.second })
        }
    }

    /** Catches up with everything the phone published, e.g. after the app was installed. */
    fun pullAll() {
        scope.launch {
            runCatching { library.mergeRemote(positions.remote()) }.onFailure { Log.w(TAG, "Can't read positions", it) }
        }
    }

    private companion object {
        const val TAG = "PositionSync"
    }
}
