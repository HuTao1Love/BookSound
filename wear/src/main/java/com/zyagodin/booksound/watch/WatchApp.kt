package com.zyagodin.booksound.watch

import android.app.Application
import com.zyagodin.booksound.watch.data.WatchLibrary
import com.zyagodin.booksound.watch.data.WatchSettings
import com.zyagodin.booksound.watch.playback.WatchPlayer
import com.zyagodin.booksound.watch.sync.BookReceiver
import com.zyagodin.booksound.watch.sync.PositionSync
import com.zyagodin.booksound.watch.sync.WatchUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class WatchApp : Application() {
    lateinit var container: WatchContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = WatchContainer(this)
        container.receiver.cleanUp()
        container.positions.pullAll()
        container.updates.publishVersion()
    }
}

/** Manual dependency graph of the watch app. */
class WatchContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = WatchSettings(app)
    val library = WatchLibrary(app) { settings.deviceId }
    val receiver = BookReceiver(app, scope, library)
    val positions = PositionSync(app, scope, library)
    val player = WatchPlayer(app)
    val updates = WatchUpdates(app, scope)
}
