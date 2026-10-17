package com.zyagodin.booksound.watch

import android.app.Application
import android.util.Log
import com.zyagodin.booksound.watch.data.WatchLibrary
import com.zyagodin.booksound.watch.data.WatchSettings
import com.zyagodin.booksound.watch.playback.WatchPlayer
import com.zyagodin.booksound.watch.surfaces.WatchSurfaces
import com.zyagodin.booksound.watch.sync.BookReceiver
import com.zyagodin.booksound.watch.sync.PhoneRemote
import com.zyagodin.booksound.watch.sync.PositionSync
import com.zyagodin.booksound.watch.sync.WatchUpdates
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WatchApp : Application() {
    lateinit var container: WatchContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = WatchContainer(this)
        container.receiver.cleanUp()
        container.scope.launch {
            // A failure here (say, a full disk) must not keep the positions from being pulled.
            runCatching { container.library.recoverOrphans() }.onFailure { Log.w(TAG, "Recovering books failed", it) }
            // After the recovery, so recovered books get their positions too.
            container.positions.pullAll()
        }
        container.updates.publishVersion()
    }

    private companion object {
        const val TAG = "WatchApp"
    }
}

/** Manual dependency graph of the watch app. */
class WatchContainer(app: Application) {
    /**
     * Background work of the whole app. A failure in it (e.g. the library can't be saved on a full
     * disk) is logged instead of ending the app, which would otherwise crash again on every start.
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("WatchApp", "Background work failed", e) },
    )
    val settings = WatchSettings(app)
    val library = WatchLibrary(app) { settings.deviceId }
    val receiver = BookReceiver(app, scope, library)
    val positions = PositionSync(app, scope, library)
    val player = WatchPlayer(app)
    val updates = WatchUpdates(app, scope)
    val phone = PhoneRemote(app)
    val surfaces = WatchSurfaces(app, scope, library, settings)
}
