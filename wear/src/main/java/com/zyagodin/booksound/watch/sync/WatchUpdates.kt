package com.zyagodin.booksound.watch.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.zyagodin.booksound.core.update.AppVersion
import com.zyagodin.booksound.core.wear.WatchPaths
import com.zyagodin.booksound.core.wear.WatchResult
import com.zyagodin.booksound.shared.ApkInstaller
import com.zyagodin.booksound.watch.R
import com.zyagodin.booksound.watch.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * Updates of the watch app, which the phone downloads from GitHub and sends here. The watch
 * publishes its version so the phone knows when it is outdated, keeps a received APK and lets the
 * user install it from the library screen or a notification.
 */
class WatchUpdates(private val context: Context, private val scope: CoroutineScope) {
    private val dir = File(context.filesDir, "update").apply { mkdirs() }
    private val apk = File(dir, "BookSound-Watch.apk")
    private val channels = Wearable.getChannelClient(context)
    private val messages = Wearable.getMessageClient(context)

    val installedVersion: String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    private val _pending = MutableStateFlow<String?>(null)
    /** Version of a received update waiting to be installed. */
    val pending: StateFlow<String?> = _pending

    init {
        scope.launch { _pending.value = check() }
    }

    /** Tells the phone which version is installed here. */
    fun publishVersion() {
        scope.launch {
            runCatching {
                val request = PutDataRequest.create(WatchPaths.WATCH_INFO_PATH).setData(installedVersion.toByteArray(Charsets.UTF_8))
                Wearable.getDataClient(context).putDataItem(request).await()
            }.onFailure { Log.w(TAG, "Can't publish the version", it) }
        }
    }

    fun onChannelOpened(channel: ChannelClient.Channel) {
        scope.launch {
            File(dir, PART).delete()
            runCatching { channels.receiveFile(channel, Uri.fromFile(File(dir, PART)), false).await() }
                .onFailure {
                    Log.w(TAG, "Can't receive the update", it)
                    runCatching { channels.close(channel) }
                }
        }
    }

    fun onInputClosed(channel: ChannelClient.Channel, closeReason: Int) {
        val version = channel.path.removePrefix(WatchPaths.APK_PREFIX)
        scope.launch {
            val part = File(dir, PART)
            val result = when {
                closeReason != ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL -> WatchResult.Failed("closed $closeReason")
                ApkInstaller.versionOf(context, part) == null -> WatchResult.Failed("not an update")
                !part.renameTo(apk) -> WatchResult.Failed("can't save")
                else -> WatchResult.Saved
            }
            part.delete()
            Log.i(TAG, "Update $version: $result")
            if (result == WatchResult.Saved) {
                _pending.value = check()
                notifyReady()
            }
            runCatching { messages.sendMessage(channel.nodeId, WatchPaths.APK_RESULT_PREFIX + version, result.encode()).await() }
        }
    }

    /** Opens the system installer; the user confirms on the watch. */
    fun install() {
        if (apk.exists()) runCatching { ApkInstaller.install(context, apk) }.onFailure { Log.w(TAG, "Install failed", it) }
    }

    /** The waiting update's version, or null (and the file removed) when it is no newer than this app. */
    private fun check(): String? {
        if (!apk.exists()) return null
        val version = ApkInstaller.versionOf(context, apk)
        val newer = AppVersion.parse(version)?.let { v -> AppVersion.parse(installedVersion)?.let { v > it } ?: true } ?: false
        if (!newer) apk.delete()
        return version.takeIf { newer }
    }

    private fun notifyReady() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_INSTALL_UPDATE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.update_ready, _pending.value.orEmpty()))
            .setContentText(context.getString(R.string.update_tap_to_install))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val TAG = "WatchUpdates"
        const val PART = "incoming.apk.part"
        const val CHANNEL = "updates"
        const val NOTIFICATION_ID = 7
    }
}
