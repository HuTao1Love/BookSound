package com.zyagodin.booksound.importer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
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
 * Foreground service that keeps the process alive while imports run, so conversion continues with
 * the UI closed. Shows progress with a Cancel action, and a result notification when the app is
 * in the background.
 */
class ImportService : Service() {

    private val container by lazy { (application as BookSoundApp).container }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private val notifiedResults = HashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, getString(R.string.notification_channel_import), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, getString(R.string.notification_channel_import_result), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_JOB_ID)?.takeIf { intent.action == ACTION_CANCEL }?.let {
            container.importManager.cancel(it)
        }
        if (!enterForeground()) {
            // The import still runs while the app process lives; only background survival is lost.
            stopSelf()
            return START_NOT_STICKY
        }
        if (observer == null) {
            observer = scope.launch {
                container.importManager.jobs
                    .map { jobs -> jobs.map { Triple(it.id, it.stage, it.progress?.let { p -> (p * 100).roundToInt() }) } to jobs }
                    .distinctUntilChanged { a, b -> a.first == b.first }
                    .collect { (_, jobs) -> onJobsChanged(jobs) }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Tries the most appropriate foreground service type first. Some Android versions restrict
     * individual types, so fall back instead of crashing the import.
     */
    private fun enterForeground(): Boolean {
        val notification = progressNotification(container.importManager.jobs.value)
        val types = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) add(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            add(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
        for (type in types) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
                Log.i(TAG, "Import service in foreground with type $type")
                return true
            } catch (e: RuntimeException) {
                Log.w(TAG, "Foreground type $type rejected: ${e.message}")
            }
        }
        return false
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+: the daily budget for this foreground service type is exhausted.
        container.importManager.failAll(ImportFailure.TimeLimit())
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun onJobsChanged(jobs: List<ImportJob>) {
        for (job in jobs) {
            if (!job.isActive && notifiedResults.add(job.id)) postResult(job)
        }
        if (jobs.none { it.isActive }) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else if (canNotify()) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, progressNotification(jobs))
        }
    }

    private fun progressNotification(jobs: List<ImportJob>): Notification {
        val active = jobs.firstOrNull { it.isActive && it.stage != ImportStage.QUEUED } ?: jobs.firstOrNull { it.isActive }
        // Up to three books import side by side; the rest wait.
        val othersRunning = jobs.count { it.isActive && it.stage != ImportStage.QUEUED && it.id != active?.id }
        val queued = jobs.count { it.stage == ImportStage.QUEUED && it.id != active?.id }
        val builder = NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openImports())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (active == null) {
            return builder.setContentTitle(getString(R.string.import_preparing)).setProgress(0, 0, true).build()
        }
        val percent = active.progress?.let { (it * 100).roundToInt() }
        builder.setContentTitle(active.title)
            .setContentText(
                getString(stageLabel(active.stage)) +
                    (percent?.let { " · $it%" } ?: "") +
                    (if (othersRunning > 0) " · " + resources.getQuantityString(R.plurals.import_more_running, othersRunning, othersRunning) else "") +
                    (if (queued > 0) " · " + resources.getQuantityString(R.plurals.import_more_queued, queued, queued) else ""),
            )
            .setProgress(100, percent ?: 0, percent == null)
            .addAction(0, getString(R.string.action_cancel), cancelIntent(active.id))
        return builder.build()
    }

    private fun postResult(job: ImportJob) {
        if (container.appVisible || !canNotify() || job.stage == ImportStage.CANCELLED) return
        val (title, text) = when (job.stage) {
            ImportStage.DONE -> getString(R.string.import_done_title) to job.title
            else -> getString(R.string.import_failed_title) to job.title
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openImports())
            .build()
        NotificationManagerCompat.from(this).notify(job.id.hashCode(), notification)
    }

    private fun canNotify(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openImports(): PendingIntent = PendingIntent.getActivity(
        this, 1,
        Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_IMPORTS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(jobId: String): PendingIntent = PendingIntent.getService(
        this, jobId.hashCode(),
        Intent(this, ImportService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_JOB_ID, jobId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        private const val TAG = "ImportService"
        private const val CHANNEL_PROGRESS = "import_progress"
        private const val CHANNEL_RESULT = "import_result"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_CANCEL = "com.zyagodin.booksound.CANCEL_IMPORT"
        private const val EXTRA_JOB_ID = "job_id"

        fun stageLabel(stage: ImportStage): Int = when (stage) {
            ImportStage.QUEUED -> R.string.import_stage_queued
            ImportStage.PREPARING -> R.string.import_stage_preparing
            ImportStage.CONVERTING -> R.string.import_stage_converting
            ImportStage.WRITING -> R.string.import_stage_writing
            ImportStage.VERIFYING -> R.string.import_stage_verifying
            ImportStage.FINISHING -> R.string.import_stage_finishing
            ImportStage.DONE -> R.string.import_stage_done
            ImportStage.FAILED -> R.string.import_stage_failed
            ImportStage.CANCELLED -> R.string.import_stage_cancelled
        }
    }
}
