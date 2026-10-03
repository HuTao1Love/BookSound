package com.zyagodin.booksound.torrent

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zyagodin.booksound.BookSoundApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Fallback that keeps the process alive while torrents download when [TorrentTransferJob] can't
 * be used (Android 13, or the job could not be scheduled). As a "data sync" foreground service it
 * is limited by Android 15+ to 6 hours a day; when that runs out the downloads pause until the
 * app is opened again. Everything it shows comes from [TorrentManager]; if Android stops it, the
 * persisted state lets the downloads resume the next time the app starts.
 */
class TorrentService : Service() {

    private val container by lazy { (application as BookSoundApp).container }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TorrentNotifications.createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val torrents = container.torrents
        if (!enterForeground(TorrentNotifications.build(this, torrents.items.value))) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (observer == null) {
            observer = scope.launch {
                combine(torrents.items, torrents.needsForeground) { items, needed -> items to needed }
                    .map { (items, needed) -> Triple(TorrentNotifications.summary(items), needed, items) }
                    .distinctUntilChanged { a, b -> a.first == b.first && a.second == b.second }
                    .collect { (_, needed, items) ->
                        if (!needed) {
                            ServiceCompat.stopForeground(this@TorrentService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else if (canNotify()) {
                            NotificationManagerCompat.from(this@TorrentService).notify(TorrentNotifications.NOTIFICATION_ID, TorrentNotifications.build(this@TorrentService, items))
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(notification: Notification): Boolean = try {
        ServiceCompat.startForeground(this, TorrentNotifications.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "Could not enter the foreground: ${e.message}")
        false
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+: the daily 6-hour data sync budget is used up. Downloads continue when the app is opened.
        container.torrents.saveState()
        stopSelf()
    }

    override fun onDestroy() {
        container.torrents.saveState()
        scope.cancel()
        super.onDestroy()
    }

    private fun canNotify(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "TorrentService"

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TorrentService::class.java))
            } catch (e: IllegalStateException) {
                // Background start not allowed; downloads run while the process lives and resume on next start.
                Log.w(TAG, "Could not start torrent service", e)
            }
        }
    }
}
