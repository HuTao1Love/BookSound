package com.zyagodin.booksound.server

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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

/** Keeps the process alive while books move between the phone and the server, with a progress notification. */
class ServerTransferService : Service() {

    private val server by lazy { (application as BookSoundApp).container.server }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.server_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(server.transfers.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not enter the foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (observer == null) {
            observer = scope.launch {
                server.transfers
                    .map { list -> list.map { Triple(it.bookId, it.stage, it.progress?.let { p -> (p * 100).roundToInt() }) } to list }
                    .distinctUntilChanged { a, b -> a.first == b.first }
                    .collect { (_, list) -> onChanged(list) }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        server.transfers.value.filter { it.isActive }.forEach { server.cancel(it.bookId, it.direction) }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun onChanged(transfers: List<ServerTransfer>) {
        if (transfers.none { it.isActive }) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification(transfers))
        }
    }

    private fun notification(transfers: List<ServerTransfer>): Notification {
        val active = transfers.firstOrNull { it.stage == ServerTransfer.Stage.RUNNING } ?: transfers.firstOrNull { it.isActive }
        val title = when (active?.direction) {
            ServerTransfer.Direction.UPLOAD -> getString(R.string.server_uploading)
            else -> getString(R.string.server_downloading)
        }
        val percent = active?.progress?.let { (it * 100).roundToInt() }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentTitle(title)
            .setContentText(listOfNotNull(active?.title, percent?.let { "$it%" }).joinToString(" · "))
            .setProgress(100, percent ?: 0, percent == null)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
            .build()
    }

    companion object {
        private const val TAG = "ServerTransferService"
        private const val CHANNEL = "server_transfers"
        private const val NOTIFICATION_ID = 4410
    }
}
