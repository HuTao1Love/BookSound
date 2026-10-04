package com.zyagodin.booksound.importer

import android.content.Context
import android.content.Intent
import android.util.Log
import com.zyagodin.booksound.cover.CoverStore
import com.zyagodin.booksound.storage.DocumentStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Queue of imports, processed in the application scope, up to [parallelImports] books at a time (a
 * setting; [ImportPipeline] shares the codecs between them and writes into the library one book at
 * a time). The UI and the [ImportService] observe [jobs]; the service only keeps the process in the
 * foreground.
 */
class ImportManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val pipeline: ImportPipeline,
    private val documents: DocumentStore,
    private val covers: CoverStore,
    /** Books converted at the same time; changes apply to the books started next. */
    parallelImports: StateFlow<Int>,
    /** Called after a book's file was rewritten so an active player can reopen it. */
    private val onBookFileChanged: (bookId: String) -> Unit,
) {
    private val _jobs = MutableStateFlow<List<ImportJob>>(emptyList())
    val jobs: StateFlow<List<ImportJob>> = _jobs

    private val queue = Channel<String>(Channel.UNLIMITED)
    private val running = ConcurrentHashMap<String, Job>()
    private val slots = AdjustableGate(parallelImports)

    init {
        scope.launch {
            for (id in queue) {
                slots.acquire()
                scope.launch {
                    try {
                        process(id)
                    } finally {
                        slots.release()
                    }
                }
            }
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
        // A job not claimed yet is cancelled here and [process] skips it; a claimed one is running
        // and its worker is already registered (see [process]).
        setState(jobId) { if (it.stage == ImportStage.QUEUED) it.copy(stage = ImportStage.CANCELLED, progress = null) else it }
        running[jobId]?.cancel()
    }

    /** Cancels everything, e.g. when Android ends the foreground service time budget. */
    fun failAll(failure: ImportFailure) {
        _jobs.update { list ->
            list.map { if (it.stage == ImportStage.QUEUED) it.copy(stage = ImportStage.FAILED, failure = failure) else it }
        }
        // Every job claimed before the update above is registered by now.
        running.forEach { (id, job) ->
            pendingFailure[id] = failure
            job.cancel()
        }
    }

    private val pendingFailure = ConcurrentHashMap<String, ImportFailure>()

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
        if (_jobs.value.none { it.id == id && it.stage == ImportStage.QUEUED }) return
        // Registered before the job is claimed, so a cancel that finds the job claimed also finds
        // the worker. The worker claims the job itself: a cancel that came first wins.
        val worker = scope.launch(start = CoroutineStart.LAZY) {
            val job = claim(id) ?: return@launch
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
        running[id] = worker
        try {
            worker.start()
            worker.join()
        } finally {
            running.remove(id)
            // Left by failAll for a worker that never claimed its job.
            pendingFailure.remove(id)
        }
    }

    /** Moves a queued job to PREPARING; null if it was cancelled or failed in the meantime. */
    private fun claim(id: String): ImportJob? {
        var claimed: ImportJob? = null
        _jobs.update { list ->
            claimed = null
            list.map { job ->
                if (job.id == id && job.stage == ImportStage.QUEUED) job.copy(stage = ImportStage.PREPARING).also { claimed = it } else job
            }
        }
        return claimed
    }

    private fun releaseSource(job: ImportJob) {
        // Keep grants another queued/failed job still needs (e.g. the same folder).
        val stillNeeded = _jobs.value.filter { it.id != job.id }.flatMapTo(HashSet()) { it.request.heldPermissions }
        job.request.heldPermissions.filterNot { it in stillNeeded }.forEach { documents.releasePersistablePermission(it) }
    }

    private fun setState(id: String, change: (ImportJob) -> ImportJob) {
        _jobs.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    /**
     * A plain start, not startForegroundService(): that one obliges the service to enter the
     * foreground within seconds or the app crashes, which it can't when Android refuses (the
     * daily foreground time is used up). Imports start while the app is visible, where a plain
     * start is allowed; in the background (torrents finishing) the start is refused, and the
     * torrent download job keeps the process alive instead.
     */
    private fun startService() {
        try {
            context.startService(Intent(context, ImportService::class.java))
        } catch (e: IllegalStateException) {
            // Background start not allowed; the job still runs while the app process lives.
            Log.w(TAG, "Could not start import service", e)
        }
    }

    companion object {
        private const val TAG = "ImportManager"
    }
}
