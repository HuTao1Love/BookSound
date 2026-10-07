package com.zyagodin.booksound.wear

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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Keeps the process alive while books are sent to the watch (over Bluetooth that can take a
 * while), with a progress notification and the result when the app is in the background.
 */
class WatchTransferService : Service() {

    private val container by lazy { (application as BookSoundApp).container }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, getString(R.string.notification_channel_watch), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, getString(R.string.notification_channel_watch_result), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_BOOK_ID)?.let { container.watchSender.cancel(it) }
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, progressNotification(container.watchSender.transfers.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: RuntimeException) {
            // The transfer still runs while the process lives; only background survival is lost.
            Log.w(TAG, "Could not enter the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (observer == null) {
            observer = scope.launch {
                container.watchSender.transfers
                    .map { list -> list.map { Triple(it.bookId, it.stage, it.progress?.let { p -> (p * 100).roundToInt() }) } to list }
                    .distinctUntilChanged { a, b -> a.first == b.first }
                    .collect { (_, list) -> onTransfersChanged(list) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+: the daily data sync budget is used up.
        container.watchSender.transfers.value.filter { it.isActive }.forEach { container.watchSender.cancel(it.bookId) }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun onTransfersChanged(transfers: List<WatchTransfer>) {
        if (transfers.none { it.isActive }) {
            postResult(transfers)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (canNotify()) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, progressNotification(transfers))
        }
    }

    private fun progressNotification(transfers: List<WatchTransfer>): Notification {
        val active = transfers.firstOrNull { it.stage == WatchTransfer.Stage.SENDING || it.stage == WatchTransfer.Stage.SAVING }
            ?: transfers.firstOrNull { it.isActive }
        val queued = transfers.count { it.stage == WatchTransfer.Stage.QUEUED && it.bookId != active?.bookId }
        val builder = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentTitle(getString(R.string.watch_sending_title))
        if (active == null) return builder.setProgress(0, 0, true).build()
        val percent = active.progress?.let { (it * 100).roundToInt() }
        val stage = when (active.stage) {
            WatchTransfer.Stage.SAVING -> getString(R.string.watch_stage_saving)
            WatchTransfer.Stage.QUEUED -> getString(R.string.watch_stage_connecting)
            else -> percent?.let { "$it%" }
        }
        return builder
            .setContentText(listOfNotNull(active.title, stage, queued.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.watch_more_queued, it, it) }).joinToString(" · "))
            .setProgress(100, percent ?: 0, percent == null)
            .addAction(0, getString(R.string.action_cancel), cancelIntent(active.bookId))
            .build()
    }

    private fun postResult(transfers: List<WatchTransfer>) {
        if (container.appVisible || !canNotify()) return
        val shown = transfers.filter { it.stage != WatchTransfer.Stage.CANCELLED }
        if (shown.isEmpty()) return
        val failed = shown.filter { it.stage != WatchTransfer.Stage.DONE }
        val title = if (failed.isEmpty()) getString(R.string.watch_sent_title) else getString(R.string.watch_failed_title)
        val notification = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText((failed.ifEmpty { shown }).joinToString(", ") { it.title })
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        NotificationManagerCompat.from(this).notify(RESULT_NOTIFICATION_ID, notification)
    }

    private fun canNotify(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 2,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(bookId: String): PendingIntent = PendingIntent.getService(
        this, bookId.hashCode(),
        Intent(this, WatchTransferService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_BOOK_ID, bookId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "WatchTransferService"
        private const val CHANNEL_PROGRESS = "watch_progress"
        private const val CHANNEL_RESULT = "watch_result"
        private const val NOTIFICATION_ID = 43
        private const val RESULT_NOTIFICATION_ID = 44
        private const val ACTION_CANCEL = "com.zyagodin.booksound.CANCEL_WATCH_TRANSFER"
        private const val EXTRA_BOOK_ID = "book_id"

        /** A plain start: books are sent from the visible app, see ImportManager.startService(). */
        fun start(context: Context) {
            try {
                context.startService(Intent(context, WatchTransferService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Could not start the watch transfer service", e)
            }
        }
    }
}
