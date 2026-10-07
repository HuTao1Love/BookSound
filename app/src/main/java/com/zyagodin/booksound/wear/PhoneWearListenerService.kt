package com.zyagodin.booksound.wear

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import com.zyagodin.booksound.BookSoundApp

/** Wakes the app when the watch published a listening position, to merge it into the library. */
class PhoneWearListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        (application as BookSoundApp).container.sync.requestSync()
    }
}
