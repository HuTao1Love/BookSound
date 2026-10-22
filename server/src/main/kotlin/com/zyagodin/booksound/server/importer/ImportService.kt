package com.zyagodin.booksound.server.importer

import com.zyagodin.booksound.api.ApiJson
import com.zyagodin.booksound.api.EventDto
import com.zyagodin.booksound.api.EventTypes
import com.zyagodin.booksound.api.ImportJobDto
import com.zyagodin.booksound.api.ImportStage
import com.zyagodin.booksound.api.MetadataDto
import com.zyagodin.booksound.server.db.Database
import com.zyagodin.booksound.server.db.str
import com.zyagodin.booksound.server.events.Events
import com.zyagodin.booksound.server.library.LibraryService
import com.zyagodin.booksound.server.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.util.UUID

/** A file of an import source and the folders between the source's root and it. */
@Serializable
data class SourceFileRef(val path: String, val relativeDir: List<String> = emptyList())

/** What a job does; stored with the job so it can start over after a restart. */
@Serializable
sealed interface JobSpec

/** Builds a book from audio files: the server's version of the phone's import. */
@Serializable
@SerialName("import")
data class ImportSpec(
    val files: List<SourceFileRef>,
    val sourceName: String,
    /** Fields the user set; the files' tags fill the rest. */
    val edited: MetadataDto? = null,
    /** What the user was shown before editing (torrent review): an unchanged field may still be replaced by tags. */
    val suggested: MetadataDto? = null,
    /** Paths of the files making up the book in order; null keeps every audio file in the analysed order. */
    val partOrder: List<String>? = null,
    val coverPath: String? = null,
    /** Id the book gets (torrents fix it up front); null takes the source's own or a new one. */
    val bookId: String? = null,
    /** Reject audio that decodes shorter or longer than the files claim (downloads nobody has listened to). */
    val strict: Boolean = false,
    val torrentId: String? = null,
    /** Folder deleted once the job is finished with (uploaded sources). */
    val cleanupDir: String? = null,
    val addedBy: String? = null,
    /** For downloads: the layout the torrent promised; files that don't match it fail the import. */
    val expectedLayout: String? = null,
) : JobSpec

/** Builds the smaller copy of a library book that watches download. */
@Serializable
@SerialName("watch")
data class WatchSpec(val bookId: String, val profileId: String, val fileRevision: Int) : JobSpec

@Serializable
data class JobRecord(
    val id: String,
    val spec: JobSpec,
    val title: String,
    val stage: ImportStage = ImportStage.QUEUED,
    val progress: Float? = null,
    val error: String? = null,
    val bookId: String? = null,
    val createdAt: Long,
    val finishedAt: Long? = null,
) {
    val isActive get() = stage != ImportStage.DONE && stage != ImportStage.FAILED && stage != ImportStage.CANCELLED

    fun toDto() = ImportJobDto(id, title, stage, progress, error, bookId, (spec as? ImportSpec)?.torrentId, createdAt, finishedAt)
}

/** A failure to show the user as is. */
class ImportFailure(message: String) : Exception(message)

/**
 * The queue of conversions: imports and watch copies. Runs as many at once as the settings allow; jobs and
 * their progress are kept in the database, and jobs interrupted by a restart start over.
 */
class ImportService(
    private val db: Database,
    private val library: LibraryService,
    private val settings: SettingsStore,
    private val ffmpeg: Ffmpeg,
    private val events: Events,
    private val workRoot: File,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(ImportService::class.java)
    private val lock = Any()
    private val jobs = LinkedHashMap<String, JobRecord>()
    private val running = HashMap<String, Job>()
    private val pipeline = ImportPipeline(library, settings, ffmpeg)

    /** Called on a background thread after a job ends (done, failed or cancelled). */
    val finishedListeners = mutableListOf<(JobRecord) -> Unit>()

    /** Set while the server shuts down: jobs cut short then are left as they are stored, to run again on start. */
    @Volatile
    private var stopping = false

    /** Runs a watch job; set by the watch copies service. */
    var watchRunner: (suspend (WatchSpec, File, (Float) -> Unit) -> Unit)? = null

    fun start() {
        val stored = db.tx { query("SELECT body FROM import_jobs ORDER BY created_at") { it.str("body")!! } }
        synchronized(lock) {
            for (body in stored) {
                val job = runCatching { ApiJson.decodeFromString(JobRecord.serializer(), body) }.getOrNull() ?: continue
                jobs[job.id] = if (job.isActive) job.copy(stage = ImportStage.QUEUED, progress = null) else job
            }
        }
        workRoot.listFiles()?.filter { it.name !in jobs.keys }?.forEach { it.deleteRecursively() }
        // Finished jobs are listed for a week.
        val cutoff = System.currentTimeMillis() - 7 * 24 * 3_600_000L
        synchronized(lock) { jobs.values.filter { !it.isActive && (it.finishedAt ?: 0) < cutoff }.map { it.id } }.forEach(::remove)
        pump()
    }

    fun list(): List<JobRecord> = synchronized(lock) { jobs.values.toList() }

    fun get(id: String): JobRecord? = synchronized(lock) { jobs[id] }

    fun enqueue(spec: JobSpec, title: String): JobRecord {
        val job = JobRecord(UUID.randomUUID().toString(), spec, title, createdAt = System.currentTimeMillis())
        synchronized(lock) { jobs[job.id] = job }
        persist(job)
        events.emit(EventDto(EventTypes.JOB, job = job.toDto()))
        pump()
        return job
    }

    /** An active watch job for the book and profile, so the same copy is not built twice. */
    fun activeWatchJob(bookId: String, profileId: String): JobRecord? = synchronized(lock) {
        jobs.values.firstOrNull { j -> j.isActive && (j.spec as? WatchSpec)?.let { it.bookId == bookId && it.profileId == profileId } == true }
    }

    /** The newest watch job for this revision of the book and the profile, running or finished. */
    fun latestWatchJob(bookId: String, profileId: String, fileRevision: Int): JobRecord? = synchronized(lock) {
        jobs.values.filter { j -> (j.spec as? WatchSpec)?.let { it.bookId == bookId && it.profileId == profileId && it.fileRevision == fileRevision } == true }
            .maxByOrNull { it.createdAt }
    }

    fun cancel(id: String) {
        val job = synchronized(lock) { jobs[id] } ?: return
        if (!job.isActive) return
        val runningJob = synchronized(lock) { running[id] }
        if (runningJob != null) runningJob.cancel() else finish(job.copy(stage = ImportStage.CANCELLED, finishedAt = System.currentTimeMillis()))
    }

    /** Forgets a finished job. */
    fun remove(id: String) {
        synchronized(lock) {
            val job = jobs[id] ?: return
            if (job.isActive) return
            jobs.remove(id)
        }
        db.tx { update("DELETE FROM import_jobs WHERE id = ?", id) }
    }

    /**
     * Stops running jobs for a shutdown and waits a little for them to let go of files and the database. They stay
     * queued in the database (only finished jobs are stored with their outcome) and start over on the next start.
     */
    fun stop(timeoutMs: Long = 10_000) {
        stopping = true
        val active = synchronized(lock) { running.values.toList() }
        active.forEach { it.cancel() }
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { active.forEach { it.join() } } }
    }

    private fun pump() {
        if (stopping) return
        val toStart = synchronized(lock) {
            val limit = settings.current.parallelImports
            val free = limit - running.size
            if (free <= 0) return
            jobs.values.filter { it.stage == ImportStage.QUEUED && it.id !in running }.take(free)
        }
        for (job in toStart) {
            val handle = scope.launch(Dispatchers.IO, start = kotlinx.coroutines.CoroutineStart.LAZY) { execute(job) }
            synchronized(lock) { running[job.id] = handle }
            handle.invokeOnCompletion {
                synchronized(lock) { running.remove(job.id) }
                pump()
            }
            handle.start()
        }
    }

    private suspend fun execute(initial: JobRecord) {
        val workDir = File(workRoot, initial.id).apply { mkdirs() }
        var job = initial
        var lastPersist = 0L
        val report = { stage: ImportStage, progress: Float? ->
            job = job.copy(stage = stage, progress = progress)
            synchronized(lock) { jobs[job.id] = job }
            val now = System.currentTimeMillis()
            if (progress == null || now - lastPersist > 2_000) {
                lastPersist = now
                events.emit(EventDto(EventTypes.JOB, job = job.toDto()))
            }
        }
        try {
            val bookId = when (val spec = job.spec) {
                is ImportSpec -> pipeline.run(job.id, spec, workDir, report)
                is WatchSpec -> {
                    report(ImportStage.CONVERTING, 0f)
                    val runner = watchRunner ?: throw ImportFailure("Watch copies are unavailable")
                    runner(spec, workDir) { report(ImportStage.CONVERTING, it) }
                    spec.bookId
                }
            }
            finish(job.copy(stage = ImportStage.DONE, progress = 1f, bookId = bookId, finishedAt = System.currentTimeMillis()))
        } catch (e: CancellationException) {
            if (stopping) return
            finish(job.copy(stage = ImportStage.CANCELLED, progress = null, finishedAt = System.currentTimeMillis()))
        } catch (e: Throwable) {
            // ffmpeg killed by the shutdown, files closed under the job: not the job's fault.
            if (stopping) return
            log.warn("Job {} ({}) failed", job.id, job.title, e)
            val message = when (e) {
                is ImportFailure, is FfmpegException -> e.message
                else -> e.message ?: e.javaClass.simpleName
            }
            finish(job.copy(stage = ImportStage.FAILED, progress = null, error = message, finishedAt = System.currentTimeMillis()))
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun finish(job: JobRecord) {
        synchronized(lock) { jobs[job.id] = job }
        persist(job)
        (job.spec as? ImportSpec)?.cleanupDir?.let { File(it).deleteRecursively() }
        events.emit(EventDto(EventTypes.JOB, job = job.toDto()))
        finishedListeners.forEach { listener -> runCatching { listener(job) }.onFailure { log.warn("Job listener failed", it) } }
    }

    private fun persist(job: JobRecord) = db.tx {
        update(
            "INSERT INTO import_jobs (id, body, stage, created_at) VALUES (?, ?, ?, ?) ON CONFLICT(id) DO UPDATE SET body = excluded.body, stage = excluded.stage",
            job.id, ApiJson.encodeToString(JobRecord.serializer(), job), job.stage, job.createdAt,
        )
    }
}
