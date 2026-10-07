package com.zyagodin.booksound.watch.sync

import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.zyagodin.booksound.core.wear.WatchPaths
import com.zyagodin.booksound.shared.DataLayerPositions
import com.zyagodin.booksound.watch.WatchApp

/** Wakes the watch app for books and listening positions coming from the phone. */
class WatchListenerService : WearableListenerService() {
    private val container get() = (application as WatchApp).container

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path.startsWith(WatchPaths.HEADER_PREFIX)) container.receiver.onHeader(event.data, event.sourceNodeId)
    }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path.startsWith(WatchPaths.APK_PREFIX)) container.updates.onChannelOpened(channel)
        else container.receiver.onChannelOpened(channel)
    }

    override fun onInputClosed(channel: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
        if (channel.path.startsWith(WatchPaths.APK_PREFIX)) container.updates.onInputClosed(channel, closeReason)
        else container.receiver.onInputClosed(channel, closeReason)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        // The buffer is released after this call: decode now.
        val records = events.filter { it.type == DataEvent.TYPE_CHANGED }
            .mapNotNull { e -> DataLayerPositions.decode(e.dataItem)?.let { e.dataItem.uri.host to it } }
        if (records.isNotEmpty()) container.positions.receive(records)
    }
}
