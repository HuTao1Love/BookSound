package com.zyagodin.booksound.torrent

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.zyagodin.booksound.BookSoundApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Keeps torrent downloads (and the conversion that follows them) running with the app closed. */
object TorrentKeepAlive {
    fun start(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && TorrentTransferJob.schedule(context)) return
        TorrentService.start(context)
    }
}

/**
 * User-initiated data transfer job (Android 14+) that keeps the process alive while torrents
 * download. Unlike a "data sync" foreground service it has no 6-hour daily limit (Android 15+):
 * the system only stops it when the network is gone, the user stops it from the notification or
 * Task Manager, or the device is under pressure. It is rescheduled while work is left, and comes
 * back by itself when the connection returns.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class TorrentTransferJob : JobService() {

    private val container by lazy { (application as BookSoundApp).container }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val torrents = container.torrents
        TorrentNotifications.createChannel(this)
        // A user-initiated job must show its notification right away.
        notify(params, torrents.items.value)
        // The job takes over from the time-limited fallback service.
        stopService(Intent(this, TorrentService::class.java))
        observer?.cancel()
        observer = scope.launch {
            val updates = launch {
                torrents.items.distinctUntilChangedBy { TorrentNotifications.summary(it) }.collect { notify(params, it) }
            }
            torrents.needsForeground.first { !it }
            updates.cancel()
            torrents.saveState()
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        observer?.cancel()
        observer = null
        container.torrents.saveState()
        Log.i(TAG, "Stopped by the system (reason ${params.stopReason})")
        // Reschedule while there is work left: the job runs again, e.g. once the network is back.
        return container.torrents.needsForeground.value
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notify(params: JobParameters, items: List<TorrentItem>) {
        setNotification(params, TorrentNotifications.NOTIFICATION_ID, TorrentNotifications.build(this, items), JOB_END_NOTIFICATION_POLICY_REMOVE)
    }

    companion object {
        private const val TAG = "TorrentTransferJob"
        private const val JOB_ID = 4_201

        /**
         * Schedules the job unless it is already scheduled or running. Only allowed while the app
         * is visible; returns false when the system refuses, so the caller can fall back.
         */
        fun schedule(context: Context): Boolean {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            // Scheduling again would stop a running job and start it over.
            if (scheduler.getPendingJob(JOB_ID) != null) return true
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, TorrentTransferJob::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
            return try {
                scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
            } catch (e: RuntimeException) {
                // E.g. the app is in the background: user-initiated jobs need a visible app.
                Log.w(TAG, "Could not schedule the download job: ${e.message}")
                false
            }
        }
    }
}
