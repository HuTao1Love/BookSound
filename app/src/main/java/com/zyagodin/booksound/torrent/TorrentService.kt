package com.zyagodin.booksound.torrent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zyagodin.booksound.BookSoundApp
import com.zyagodin.booksound.MainActivity
import com.zyagodin.booksound.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Foreground service that keeps the process alive while torrents download, so downloads continue
 * with the app closed. Everything it shows comes from [TorrentManager]; if Android stops it, the
 * persisted state lets the downloads resume the next time the app starts.
 */
class TorrentService : Service() {

    private val container by lazy { (application as BookSoundApp).container }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel_torrent), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val torrents = container.torrents
        if (intent?.action == ACTION_PAUSE_ALL) {
            torrents.items.value.filter { it.record.isActive && !it.record.paused }.forEach { torrents.pause(it.id) }
        }
        if (!enterForeground(notification(torrents.items.value))) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (observer == null) {
            observer = scope.launch {
                combine(torrents.items, torrents.needsForeground) { items, needed -> items to needed }
                    .map { (items, needed) -> Triple(summary(items), needed, items) }
                    .distinctUntilChanged { a, b -> a.first == b.first && a.second == b.second }
                    .collect { (_, needed, items) ->
                        if (!needed) {
                            ServiceCompat.stopForeground(this@TorrentService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } else if (canNotify()) {
                            NotificationManagerCompat.from(this@TorrentService).notify(NOTIFICATION_ID, notification(items))
                        }
                    }
            }
        }
        return START_NOT_STICKY
    }

    private fun enterForeground(notification: Notification): Boolean = try {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "Could not enter the foreground: ${e.message}")
        false
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+: the daily data sync budget is used up. Downloads continue when the app is opened.
        container.torrents.saveState()
        stopSelf()
    }

    override fun onDestroy() {
        container.torrents.saveState()
        scope.cancel()
        super.onDestroy()
    }

    private fun downloading(items: List<TorrentItem>) =
        items.filter { !it.record.paused && (it.record.phase == TorrentPhase.DOWNLOADING || it.record.phase == TorrentPhase.FETCHING_METADATA) }

    /** Changes of this value (rounded) update the notification. */
    private fun summary(items: List<TorrentItem>): List<Any?> = downloading(items).flatMap { item ->
        listOf(item.id, item.record.phase, item.live?.progress?.let { (it * 100).roundToInt() }, item.live?.downloadRate?.div(50_000), item.online)
    }

    private fun notification(items: List<TorrentItem>): Notification {
        val active = downloading(items)
        val first = active.firstOrNull()
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openImports())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.action_pause_all), pauseAll())
        if (first == null) return builder.setContentTitle(getString(R.string.torrent_notification_title)).setProgress(0, 0, true).build()
        val percent = first.live?.progress?.let { (it * 100).roundToInt() }
        val text = when {
            !first.online -> getString(R.string.torrent_waiting_network)
            first.record.phase == TorrentPhase.FETCHING_METADATA -> getString(R.string.torrent_fetching_metadata)
            else -> listOfNotNull(
                percent?.let { "$it%" },
                first.live?.downloadRate?.takeIf { it > 0 }?.let { getString(R.string.torrent_speed, Formatter.formatShortFileSize(this, it.toLong())) },
            ).joinToString(" · ")
        } + if (active.size > 1) " · " + resources.getQuantityString(R.plurals.torrent_more_downloading, active.size - 1, active.size - 1) else ""
        return builder.setContentTitle(first.record.title)
            .setContentText(text)
            .setProgress(100, percent ?: 0, percent == null)
            .build()
    }

    private fun canNotify(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openImports(): PendingIntent = PendingIntent.getActivity(
        this, 2,
        Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_IMPORTS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun pauseAll(): PendingIntent = PendingIntent.getService(
        this, 3,
        Intent(this, TorrentService::class.java).setAction(ACTION_PAUSE_ALL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "TorrentService"
        private const val CHANNEL = "torrent_download"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_PAUSE_ALL = "com.zyagodin.booksound.PAUSE_TORRENTS"

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
