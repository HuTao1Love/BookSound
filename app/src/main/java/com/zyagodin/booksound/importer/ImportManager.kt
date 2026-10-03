package com.zyagodin.booksound.importer

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.zyagodin.booksound.cover.CoverStore
import com.zyagodin.booksound.storage.DocumentStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Queue of imports, processed one at a time in the application scope. The UI and the
 * [ImportService] observe [jobs]; the service only keeps the process in the foreground.
 */
class ImportManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pipeline: ImportPipeline,
    private val documents: DocumentStore,
    private val covers: CoverStore,
    /** Called after a book's file was rewritten so an active player can reopen it. */
    private val onBookFileChanged: (bookId: String) -> Unit,
) {
    private val _jobs = MutableStateFlow<List<ImportJob>>(emptyList())
    val jobs: StateFlow<List<ImportJob>> = _jobs

    private val queue = Channel<String>(Channel.UNLIMITED)
    private var running: Pair<String, Job>? = null

    init {
        scope.launch {
            for (id in queue) process(id)
        }
    }

    fun activeJobIds(): Set<String> = _jobs.value.filter { it.isActive }.mapTo(HashSet()) { it.id }

    fun enqueue(request: ImportRequest): String {
        val coverPath = request.cover?.let { covers.draftFile("job-${request.jobId}", it).absolutePath }
        _jobs.update { it + ImportJob(request.jobId, request, coverPath = coverPath) }
        queue.trySend(request.jobId)
        startService()
        return request.jobId
    }

    fun cancel(jobId: String) {
        val current = running
        if (current != null && current.first == jobId) {
            current.second.cancel()
        } else {
            setState(jobId) { if (it.stage == ImportStage.QUEUED) it.copy(stage = ImportStage.CANCELLED, progress = null) else it }
        }
    }

    /** Cancels everything, e.g. when Android ends the foreground service time budget. */
    fun failAll(failure: ImportFailure) {
        _jobs.update { list ->
            list.map { if (it.isActive && it.id != running?.first) it.copy(stage = ImportStage.FAILED, failure = failure) else it }
        }
        running?.let { (id, job) ->
            pendingFailure[id] = failure
            job.cancel()
        }
    }

    private val pendingFailure = mutableMapOf<String, ImportFailure>()

    fun retry(jobId: String) {
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return
        if (job.isActive) return
        val newId = UUID.randomUUID().toString()
        _jobs.update { list -> list.filterNot { it.id == jobId } }
        enqueue(job.request.copy(jobId = newId))
    }

    fun dismiss(jobId: String) {
        val job = _jobs.value.firstOrNull { it.id == jobId } ?: return
        if (job.isActive) return
        _jobs.update { list -> list.filterNot { it.id == jobId } }
        releaseSource(job)
        covers.clearDrafts("job-$jobId")
    }

    fun dismissFinished() {
        _jobs.value.filter { !it.isActive }.forEach { dismiss(it.id) }
    }

    private suspend fun process(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        if (job.stage != ImportStage.QUEUED) return
        val worker = scope.launch {
            try {
                val bookId = pipeline.run(job.request) { stage, progress ->
                    setState(id) { it.copy(stage = stage, progress = progress) }
                }
                setState(id) { it.copy(stage = ImportStage.DONE, progress = 1f, resultBookId = bookId) }
                releaseSource(job)
                if (job.request.isEdit || job.request.replacesBookId != null) {
                    onBookFileChanged(bookId)
                    job.request.replacesBookId?.takeIf { it != bookId }?.let(onBookFileChanged)
                }
            } catch (e: CancellationException) {
                val failure = pendingFailure.remove(id)
                setState(id) {
                    if (failure != null) it.copy(stage = ImportStage.FAILED, failure = failure, progress = null)
                    else it.copy(stage = ImportStage.CANCELLED, progress = null)
                }
            } catch (e: ImportFailure) {
                setState(id) { it.copy(stage = ImportStage.FAILED, failure = e, progress = null) }
            } catch (e: Throwable) {
                Log.e(TAG, "Unexpected import failure", e)
                setState(id) { it.copy(stage = ImportStage.FAILED, failure = ImportFailure.Unexpected(e.message, e), progress = null) }
            }
        }
        running = id to worker
        worker.join()
        running = null
    }

    private fun releaseSource(job: ImportJob) {
        // Keep grants another queued/failed job still needs (e.g. the same folder).
        val stillNeeded = _jobs.value.filter { it.id != job.id }.flatMapTo(HashSet()) { it.request.heldPermissions }
        job.request.heldPermissions.filterNot { it in stillNeeded }.forEach { documents.releasePersistablePermission(it) }
    }

    private fun setState(id: String, change: (ImportJob) -> ImportJob) {
        _jobs.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, ImportService::class.java))
        } catch (e: IllegalStateException) {
            // Background start not allowed; the job still runs while the app process lives.
            Log.w(TAG, "Could not start import service", e)
        }
    }

    companion object {
        private const val TAG = "ImportManager"
    }
}
