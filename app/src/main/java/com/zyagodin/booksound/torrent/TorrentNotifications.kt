package com.zyagodin.booksound.torrent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import com.zyagodin.booksound.BookSoundApp
import com.zyagodin.booksound.MainActivity
import com.zyagodin.booksound.R
import com.zyagodin.booksound.ui.components.showsSeriesInTitle
import kotlin.math.roundToInt

/**
 * The ongoing "downloading audiobooks" notification, shared by [TorrentTransferJob] and the
 * [TorrentService] fallback so both look and behave the same.
 */
object TorrentNotifications {
    const val NOTIFICATION_ID = 42
    private const val CHANNEL = "torrent_download"

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notification_channel_torrent), NotificationManager.IMPORTANCE_LOW),
        )
    }

    /** One entry per running download: the books of one torrent share theirs. */
    private fun downloading(items: List<TorrentItem>) =
        items.filter { !it.record.paused && (it.record.phase == TorrentPhase.DOWNLOADING || it.record.phase == TorrentPhase.FETCHING_METADATA) }
            .distinctBy { it.record.downloadKey }

    /** The title with the series in front, as the library shows it ("Series #2. Book 2"). */
    fun displayTitle(context: Context, r: TorrentRecord): String {
        val series = r.series?.trim()
        if (series == null || !showsSeriesInTitle(r.title, series)) return r.title
        val prefix = r.seriesIndex?.takeIf { it.isNotBlank() }?.let { context.getString(R.string.series_in_title, series, it) } ?: series
        return "$prefix. ${r.title}"
    }

    private fun converting(items: List<TorrentItem>) =
        items.filter { it.record.phase == TorrentPhase.VERIFYING || it.record.phase == TorrentPhase.CONVERTING }

    /** Changes of this value (rounded) update the notification. */
    fun summary(items: List<TorrentItem>): List<Any?> = downloading(items).flatMap { item ->
        listOf(item.id, item.record.phase, item.live?.progress?.let { (it * 100).roundToInt() }, item.live?.downloadRate?.div(50_000), item.online)
    } + converting(items).map { it.id }

    fun build(context: Context, items: List<TorrentItem>): Notification {
        val active = downloading(items)
        val first = active.firstOrNull()
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openImports(context))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (first == null) {
            // Downloads are done; the book is being checked and converted (its own notification shows progress).
            val book = converting(items).firstOrNull()
            return builder.setContentTitle(book?.record?.let { displayTitle(context, it) } ?: context.getString(R.string.torrent_notification_title))
                .apply { if (book != null) setContentText(context.getString(R.string.import_stage_converting)) }
                .setProgress(0, 0, true)
                .build()
        }
        builder.addAction(0, context.getString(R.string.action_pause_all), pauseAll(context))
        val percent = first.live?.progress?.let { (it * 100).roundToInt() }
        val text = when {
            !first.online -> context.getString(R.string.torrent_waiting_network)
            first.record.phase == TorrentPhase.FETCHING_METADATA -> context.getString(R.string.torrent_fetching_metadata)
            else -> listOfNotNull(
                percent?.let { "$it%" },
                first.live?.downloadRate?.takeIf { it > 0 }?.let { context.getString(R.string.torrent_speed, Formatter.formatShortFileSize(context, it.toLong())) },
            ).joinToString(" · ")
        } + if (active.size > 1) " · " + context.resources.getQuantityString(R.plurals.torrent_more_downloading, active.size - 1, active.size - 1) else ""
        return builder.setContentTitle(if (first.record.group != null) first.record.name else displayTitle(context, first.record))
            .setContentText(text)
            .setProgress(100, percent ?: 0, percent == null)
            .build()
    }

    private fun openImports(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 2,
        Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_IMPORTS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun pauseAll(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 3,
        Intent(context, TorrentActionReceiver::class.java).setAction(TorrentActionReceiver.ACTION_PAUSE_ALL),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** Handles the notification's "Pause all" button, wherever the notification came from. */
class TorrentActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_PAUSE_ALL) return
        val torrents = (context.applicationContext as BookSoundApp).container.torrents
        torrents.items.value.filter { it.record.isActive && !it.record.paused }.forEach { torrents.pause(it.id) }
    }

    companion object {
        const val ACTION_PAUSE_ALL = "com.zyagodin.booksound.PAUSE_TORRENTS"
    }
}
