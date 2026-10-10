package com.zyagodin.booksound.wear

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.zyagodin.booksound.BookSoundApp
import com.zyagodin.booksound.core.wear.WatchPaths

/**
 * Wakes the app when the watch published a listening position, to merge it into the library,
 * or asked to play or pause the phone's book.
 */
class PhoneWearListenerService : WearableListenerService() {
    private val container get() = (application as BookSoundApp).container

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        container.sync.requestSync()
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path.startsWith(WatchPaths.PHONE_COMMAND_PREFIX)) {
            container.watchRemote.onCommand(event.path.removePrefix(WatchPaths.PHONE_COMMAND_PREFIX))
        }
    }
}
