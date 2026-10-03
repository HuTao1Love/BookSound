package com.zyagodin.booksound.torrent

import android.content.Context
import android.net.Uri
import android.util.Log
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.model.EmbeddedPicture
import com.zyagodin.booksound.core.organize.DraftPart
import com.zyagodin.booksound.core.organize.ImportDraft
import com.zyagodin.booksound.core.torrent.AudiobookIntegrity
import com.zyagodin.booksound.core.torrent.IntegrityIssue
import com.zyagodin.booksound.core.torrent.MergedReview
import com.zyagodin.booksound.core.torrent.TorrentContentCheck
import com.zyagodin.booksound.core.torrent.TorrentContentProblem
import com.zyagodin.booksound.core.torrent.TorrentContentValidator
import com.zyagodin.booksound.core.torrent.TorrentLink
import com.zyagodin.booksound.core.torrent.TorrentLinks
import com.zyagodin.booksound.core.torrent.TorrentReviewMerger
import com.zyagodin.booksound.core.torrent.TorrentSuggestions
import com.zyagodin.booksound.cover.CoverImages
import com.zyagodin.booksound.cover.CoverStore
import com.zyagodin.booksound.data.library.LibraryRepository
import com.zyagodin.booksound.importer.AnalysisFailure
import com.zyagodin.booksound.importer.AnalysisState
import com.zyagodin.booksound.importer.EditorForm
import com.zyagodin.booksound.importer.ImportAnalyzer
import com.zyagodin.booksound.importer.ImportDecision
import com.zyagodin.booksound.importer.ImportJob
import com.zyagodin.booksound.importer.ImportManager
import com.zyagodin.booksound.importer.ImportPlanner
import com.zyagodin.booksound.importer.ImportSession
import com.zyagodin.booksound.importer.ImportStage
import com.zyagodin.booksound.importer.PlanOutcome
import com.zyagodin.booksound.importer.SelectedCover
import com.zyagodin.booksound.importer.SkipReason
import com.zyagodin.booksound.importer.SkippedFile
import com.zyagodin.booksound.storage.DocumentStore
import com.zyagodin.booksound.ui.importer.failureMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What the user gave us to add. */
sealed interface TorrentSource {
    /** A magnet link, an http(s) link to a .torrent file, or a bare info-hash. */
    data class Link(val text: String) : TorrentSource

    /** A .torrent file picked with the system file picker. */
    data class File(val uri: Uri) : TorrentSource
}

sealed interface AddResult {
    data class Added(val torrentId: String) : AddResult
    data class AlreadyAdded(val torrentId: String) : AddResult
    data class Rejected(val error: AddError, val problem: TorrentContentProblem? = null, val files: List<String> = emptyList()) : AddResult
}

enum class AddError { NOT_A_LINK, INVALID_TORRENT, UNREADABLE_FILE, DOWNLOAD_FAILED, OFFLINE, CONTENT_INVALID }

/**
 * Orchestrates torrent imports. Every step is driven from the persisted [TorrentRecord]s by a
 * periodic reconcile loop, so closing the app, a crash, a reboot or a lost connection only pauses
 * the work: on the next start each torrent continues from its stored phase.
 *
 * Flow: add → (magnet: fetch metadata) → validate the file list → download only the audio and
 * cover files while the user reviews the details → when both are done, verify the downloaded
 * files → hand the book to the [ImportManager] (strict validation) → book in library, download
 * deleted. A book that fails any check never reaches the library.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TorrentManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: TorrentStore,
    private val engine: TorrentEngine,
    private val network: NetworkMonitor,
    private val http: OkHttpClient,
    private val documents: DocumentStore,
    private val analyzer: ImportAnalyzer,
    private val planner: ImportPlanner,
    private val imports: ImportManager,
    private val library: LibraryRepository,
    private val covers: CoverStore,
) {
    /** All state transitions run on this single thread, so they never race each other. */
    private val serial = Dispatchers.IO.limitedParallelism(1)

    private val live = MutableStateFlow<Map<String, TorrentLive>>(emptyMap())
    private val editing = MutableStateFlow<Set<String>>(emptySet())
    private val fetchJobs = ConcurrentHashMap<String, Job>()
    private val verifyJobs = ConcurrentHashMap<String, Job>()

    /** Torrents handed to the engine recently; adding is asynchronous. */
    private val pendingAdds = HashMap<String, Long>()
    private var started = false
    private var idleSince = 0L
    private var lastResumeSave = 0L
    private var lastProgressSave = 0L

    val items: StateFlow<List<TorrentItem>> = combine(store.records, live, imports.jobs, network.online, editing) { records, liveMap, jobs, online, open ->
        records.sortedByDescending { it.addedAt }.map { r ->
            TorrentItem(
                record = r,
                live = liveMap[r.id],
                job = r.importJobId?.let { id -> jobs.firstOrNull { it.id == id } },
                coverPath = store.coverFile(r.id).takeIf { r.hasCustomCover && it.isFile }?.absolutePath,
                online = online,
                editing = r.id in open,
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** True while something needs the network in the background (keeps the foreground service up). */
    val needsForeground: StateFlow<Boolean> = store.records
        .map { list -> list.any { !it.paused && (it.phase == TorrentPhase.DOWNLOADING || it.phase == TorrentPhase.FETCHING_METADATA) } }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    fun record(id: String): TorrentRecord? = store[id]

    /** Resumes all unfinished torrents. Called once at app start. */
    fun start() {
        if (started) return
        started = true
        engine.listener = { event -> scope.launch(serial) { onEngineEvent(event) } }
        network.onChanged = {
            engine.onNetworkChanged()
            scope.launch(serial) { tick() }
        }
        scope.launch(serial) {
            while (isActive) {
                try {
                    tick()
                } catch (t: Throwable) {
                    Log.e(TAG, "Torrent reconcile failed", t)
                }
                delay(if (store.records.value.any { it.isActive }) 1_000 else 5_000)
            }
        }
        scope.launch(serial) {
            imports.jobs.collect { jobs -> onImportJobs(jobs) }
        }
    }

    /** Persists libtorrent's progress now, e.g. when the app goes to the background. */
    fun saveState() {
        scope.launch(serial) { runCatching { engine.requestAllResumeData() } }
    }

    // ------------------------------------------------------------------ adding

    suspend fun add(source: TorrentSource): AddResult = withContext(Dispatchers.IO) {
        when (source) {
            is TorrentSource.File -> {
                val bytes = documents.readBytes(source.uri, MAX_TORRENT_BYTES) ?: return@withContext AddResult.Rejected(AddError.UNREADABLE_FILE)
                addTorrentBytes(bytes, null)
            }
            is TorrentSource.Link -> when (val link = TorrentLinks.parse(source.text)) {
                null -> AddResult.Rejected(AddError.NOT_A_LINK)
                is TorrentLink.Magnet -> addMagnet(link)
                is TorrentLink.Web -> {
                    if (!network.online.value) return@withContext AddResult.Rejected(AddError.OFFLINE)
                    when (val fetched = fetchTorrentFile(link.url)) {
                        is Fetched.Bytes -> addTorrentBytes(fetched.bytes, null)
                        is Fetched.Magnet -> addMagnet(fetched.link)
                        Fetched.Failed -> AddResult.Rejected(AddError.DOWNLOAD_FAILED)
                    }
                }
            }
        }
    }

    private suspend fun addMagnet(link: TorrentLink.Magnet): AddResult = withContext(serial) {
        store.records.value.firstOrNull { it.infoHash == link.infoHash }?.let { return@withContext AddResult.AlreadyAdded(it.id) }
        val id = UUID.randomUUID().toString()
        store.add(
            TorrentRecord(
                id = id,
                name = link.displayName ?: link.infoHash.take(12),
                infoHash = link.infoHash,
                magnetUri = link.uri,
                addedAt = System.currentTimeMillis(),
                phase = TorrentPhase.FETCHING_METADATA,
                dataDir = store.newDataDir(id).absolutePath,
                bookId = UUID.randomUUID().toString(),
            ),
        )
        tick()
        AddResult.Added(id)
    }

    private suspend fun addTorrentBytes(bytes: ByteArray, magnetUri: String?): AddResult = withContext(serial) {
        val meta = try {
            TorrentEngine.parse(bytes)
        } catch (e: InvalidTorrentException) {
            return@withContext AddResult.Rejected(AddError.INVALID_TORRENT)
        } catch (e: Throwable) {
            Log.w(TAG, "Unreadable torrent", e)
            return@withContext AddResult.Rejected(AddError.INVALID_TORRENT)
        }
        store.records.value.firstOrNull { it.infoHash == meta.infoHash }?.let { return@withContext AddResult.AlreadyAdded(it.id) }
        when (val check = TorrentContentValidator.validate(meta.files)) {
            is TorrentContentCheck.Invalid -> AddResult.Rejected(AddError.CONTENT_INVALID, check.problem, check.files)
            is TorrentContentCheck.Valid -> {
                val id = UUID.randomUUID().toString()
                store.dir(id).mkdirs()
                store.writeTorrent(id, bytes)
                val base = TorrentRecord(
                    id = id,
                    name = meta.name,
                    infoHash = meta.infoHash,
                    magnetUri = magnetUri,
                    addedAt = System.currentTimeMillis(),
                    phase = TorrentPhase.DOWNLOADING,
                    dataDir = store.newDataDir(id).absolutePath,
                    bookId = UUID.randomUUID().toString(),
                )
                store.add(withContent(base, meta, check))
                startDownload(id)
                AddResult.Added(id)
            }
        }
    }

    private fun withContent(record: TorrentRecord, meta: TorrentMeta, content: TorrentContentCheck.Valid): TorrentRecord {
        val suggestion = TorrentSuggestions.build(meta.name, content) { context.getString(R.string.chapter_number, it) }
        return record.copy(
            name = meta.name,
            layout = content.layout,
            files = meta.files.map { StoredFile(it.index, it.path, it.sizeBytes, it.isPadding) },
            wanted = content.wantedIndices,
            suggested = StoredMetadata.of(suggestion.metadata),
            parts = suggestion.parts.map { StoredPart(it.fileIndex, it.path, it.title, it.title, it.sizeBytes) },
        )
    }

    private sealed interface Fetched {
        class Bytes(val bytes: ByteArray) : Fetched
        class Magnet(val link: TorrentLink.Magnet) : Fetched
        data object Failed : Fetched
    }

    private fun fetchTorrentFile(url: String): Fetched = try {
        http.newCall(Request.Builder().url(url).header("Accept", "application/x-bittorrent, */*").build()).execute().use { response ->
            // Some sites redirect a download link to a magnet link, which OkHttp doesn't follow.
            response.header("Location")?.takeIf { it.startsWith("magnet:", ignoreCase = true) }
                ?.let { TorrentLinks.parseMagnet(it) }
                ?.let { return Fetched.Magnet(it) }
            if (!response.isSuccessful) return Fetched.Failed
            val body = response.body.byteStream()
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = body.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_TORRENT_BYTES) return Fetched.Failed
            }
            Fetched.Bytes(out.toByteArray())
        }
    } catch (e: IOException) {
        Log.w(TAG, "Torrent download failed", e)
        Fetched.Failed
    } catch (e: IllegalArgumentException) {
        Fetched.Failed
    }

    // ------------------------------------------------------------------ user actions

    fun pause(id: String) = scope.launch(serial) {
        val r = store.update(id) { if (it.isActive) it.copy(paused = true) else it } ?: return@launch
        if (r.phase == TorrentPhase.FETCHING_METADATA) fetchJobs.remove(id)?.cancel()
        runCatching { engine.pause(r.infoHash) }
        tick()
    }

    fun resume(id: String) = scope.launch(serial) {
        val r = store.update(id) { it.copy(paused = false) } ?: return@launch
        runCatching { engine.resume(r.infoHash) }
        tick()
    }

    /** Retries a failed torrent from the step that failed. */
    fun retry(id: String) = scope.launch(serial) {
        val r = store[id] ?: return@launch
        val failure = r.failure ?: return@launch
        if (r.phase != TorrentPhase.FAILED || !failure.retryable) return@launch
        r.importJobId?.let { imports.dismiss(it) }
        val next = when (failure.code) {
            TorrentFailureCode.METADATA_NOT_FOUND -> TorrentPhase.FETCHING_METADATA
            TorrentFailureCode.CONVERSION_FAILED, TorrentFailureCode.CONVERSION_CANCELLED -> TorrentPhase.VERIFYING
            else -> {
                // Re-add from scratch: libtorrent re-checks what is on disk and fetches what is missing.
                runCatching { engine.remove(r.infoHash, deleteFiles = false) }
                store.resumeFile(id).delete()
                TorrentPhase.DOWNLOADING
            }
        }
        store.update(id) { it.copy(phase = next, failure = null, importJobId = null, paused = false) }
        if (next == TorrentPhase.DOWNLOADING) startDownload(id)
        tick()
    }

    /** Stops and forgets the torrent, deleting everything it downloaded. */
    fun remove(id: String) = scope.launch(serial) {
        val r = store[id] ?: return@launch
        fetchJobs.remove(id)?.cancel()
        verifyJobs.remove(id)?.cancel()
        r.importJobId?.let { jobId ->
            if (imports.jobs.value.any { it.id == jobId && it.isActive }) imports.cancel(jobId) else imports.dismiss(jobId)
        }
        runCatching { engine.remove(r.infoHash, deleteFiles = true) }
        store.delete(id)
        live.update { it - id }
    }

    fun dismissFinished() = scope.launch(serial) {
        store.records.value.filter { it.phase == TorrentPhase.COMPLETED }.forEach { r ->
            r.importJobId?.let(imports::dismiss)
            store.delete(r.id)
        }
    }

    fun beginEditing(id: String) = editing.update { it + id }

    fun endEditing(id: String) {
        editing.update { it - id }
        scope.launch(serial) { tick() }
    }

    /**
     * Stores what the user entered in the editor. Called continuously while editing (so nothing
     * is lost if the app is killed) and with [confirm] when the user presses "Import".
     */
    fun saveReview(id: String, edited: BookMetadata, parts: List<DraftPart>, cover: EmbeddedPicture?, confirm: Boolean) = scope.launch(serial) {
        val record = store[id] ?: return@launch
        if (record.suggested == null) return@launch
        val byUri = record.parts.associateBy { uriOf(record, it.path) }
        val kept = parts.mapNotNull { p -> byUri[p.sourceId]?.copy(title = p.title.trim().ifEmpty { byUri.getValue(p.sourceId).suggestedTitle }) }
        if (kept.isEmpty()) return@launch
        if (cover != null) {
            TorrentEngine.writeAtomically(store.coverFile(id), cover.bytes)
        } else {
            store.coverFile(id).delete()
        }
        val images = record.wanted.filterTo(HashSet()) { i -> record.parts.none { it.fileIndex == i } }
        val wanted = images + kept.map { it.fileIndex }
        val updated = store.update(id) {
            it.copy(
                edited = StoredMetadata.of(edited),
                parts = kept,
                wanted = wanted,
                hasCustomCover = cover != null,
                reviewed = it.reviewed || confirm,
            )
        } ?: return@launch
        if (wanted != record.wanted && updated.phase == TorrentPhase.DOWNLOADING) {
            runCatching { engine.setWanted(updated.infoHash, wantedFlags(updated)) }
        }
        if (confirm) tick()
    }

    // ------------------------------------------------------------------ editor sessions

    /** Fills an editor session from the torrent's record, waiting for magnet metadata if needed. */
    fun loadSession(session: ImportSession) {
        val id = session.torrentId ?: return
        session.analysisJob = scope.launch {
            combine(store.records, network.online) { list, online -> list.firstOrNull { it.id == id } to online }.first { (r, online) ->
                if (r != null && r.phase == TorrentPhase.FETCHING_METADATA) session.analysis.value = AnalysisState.AwaitingTorrent(r.name, online)
                r == null || r.phase != TorrentPhase.FETCHING_METADATA
            }
            val record = store[id]
            session.analysis.value = when {
                record == null -> AnalysisState.Failed(AnalysisFailure.TORRENT_UNAVAILABLE)
                record.failure?.code == TorrentFailureCode.CONTENT_INVALID -> AnalysisState.Failed(
                    AnalysisFailure.TORRENT_INVALID,
                    skipped = record.failure.files.map { SkippedFile(it.substringAfterLast('/'), SkipReason.UNSUPPORTED) },
                    detail = record.failure.problem,
                )
                record.suggested == null -> AnalysisState.Failed(AnalysisFailure.TORRENT_UNAVAILABLE)
                record.phase == TorrentPhase.VERIFYING || record.phase == TorrentPhase.CONVERTING || record.phase == TorrentPhase.COMPLETED ->
                    AnalysisState.Failed(AnalysisFailure.TORRENT_BUSY)
                else -> {
                    fillSession(session, record)
                    val form = session.form.value!!
                    AnalysisState.Ready(
                        draft = ImportDraft(record.suggested.toMetadata(), form.parts, emptyList(), record.name, null),
                        files = emptyList(),
                        skipped = emptyList(),
                    )
                }
            }
        }
    }

    private suspend fun fillSession(session: ImportSession, record: TorrentRecord) = withContext(Dispatchers.IO) {
        val m = (record.edited ?: record.suggested!!)
        if (session.form.value == null) {
            session.form.value = EditorForm(
                title = m.title,
                author = m.author.orEmpty(),
                narrator = m.narrator.orEmpty(),
                series = m.series.orEmpty(),
                seriesIndex = m.seriesIndex.orEmpty(),
                year = m.year.orEmpty(),
                description = m.description.orEmpty(),
                parts = record.parts.map { DraftPart(uriOf(record, it.path), it.path.substringAfterLast('/'), it.title, 0L, emptyList()) },
            )
        }
        if (session.cover.value == null && record.hasCustomCover) {
            customCover(record.id)?.let { session.cover.value = SelectedCover(it, covers.draftFile(session.id, it), null) }
        }
    }

    /** Download size of each part of the editor's form, by source id. */
    fun partSizes(id: String): Map<String, Long> {
        val record = store[id] ?: return emptyMap()
        return record.parts.associate { uriOf(record, it.path) to it.size }
    }

    // ------------------------------------------------------------------ reconcile loop

    private suspend fun tick() {
        val records = store.records.value
        val online = network.online.value
        val now = System.currentTimeMillis()
        val liveNow = HashMap<String, TorrentLive>()

        for (r in records) {
            when (r.phase) {
                TorrentPhase.FETCHING_METADATA -> if (!r.paused && online) ensureFetching(r)
                TorrentPhase.DOWNLOADING -> reconcileDownload(r)?.let { liveNow[r.id] = it }
                TorrentPhase.DOWNLOADED -> if (r.reviewed && r.id !in editing.value) {
                    store.update(r.id) { it.copy(phase = TorrentPhase.VERIFYING) }?.let(::ensureVerifying)
                }
                TorrentPhase.VERIFYING -> if (r.id !in editing.value) ensureVerifying(r)
                TorrentPhase.CONVERTING -> recoverConversion(r)
                TorrentPhase.COMPLETED, TorrentPhase.FAILED -> Unit
            }
        }
        live.value = liveNow

        if (now - lastProgressSave > PROGRESS_SAVE_INTERVAL_MS) {
            lastProgressSave = now
            for ((id, l) in liveNow) store.update(id) { if (it.phase == TorrentPhase.DOWNLOADING) it.copy(progress = l.progress) else it }
        }
        val engineNeeded = store.records.value.any { it.needsEngine } || fetchJobs.values.any { it.isActive }
        if (engine.isRunning) {
            if (engineNeeded) {
                idleSince = 0
                if (now - lastResumeSave > RESUME_SAVE_INTERVAL_MS) {
                    lastResumeSave = now
                    engine.requestAllResumeData()
                }
            } else if (idleSince == 0L) {
                idleSince = now
                engine.requestAllResumeData()
            } else if (now - idleSince > ENGINE_IDLE_STOP_MS) {
                engine.stop()
                idleSince = 0
            }
        }
    }

    private fun reconcileDownload(r: TorrentRecord): TorrentLive? {
        if (r.paused) {
            if (engine.isRunning && engine.contains(r.infoHash)) {
                engine.status(r.infoHash)?.takeIf { !it.paused }?.let { engine.pause(r.infoHash) }
            }
            return null
        }
        if (!engine.contains(r.infoHash)) {
            val now = System.currentTimeMillis()
            if (now - (pendingAdds[r.id] ?: 0L) < ADD_RETRY_MS) return null
            val torrent = store.torrentFile(r.id)
            if (!torrent.isFile) {
                fail(r.id, TorrentFailure(TorrentFailureCode.DOWNLOAD_ERROR, "missing torrent metadata"))
                return null
            }
            try {
                engine.add(torrent.readBytes(), File(r.dataDir), store.resumeFile(r.id), wantedFlags(r))
                pendingAdds[r.id] = now
            } catch (t: Throwable) {
                Log.e(TAG, "Could not start ${r.name}", t)
                fail(r.id, TorrentFailure(TorrentFailureCode.DOWNLOAD_ERROR, t.message ?: t.javaClass.simpleName))
            }
            return null
        }
        pendingAdds.remove(r.id)
        val status = engine.status(r.infoHash) ?: return null
        if (status.paused) engine.resume(r.infoHash)
        if (status.error != null) {
            onEngineError(r.id, status.error, outOfSpace = false)
            return null
        }
        if (status.isComplete) {
            onDownloaded(r)
            return null
        }
        return TorrentLive(
            progress = status.progress,
            downloadRate = status.downloadRate,
            peers = status.peers,
            seeds = status.seeds,
            swarm = status.swarm,
            checking = status.state == EngineStatus.State.CHECKING,
            trackerError = status.trackerError,
        )
    }

    private fun startDownload(id: String) {
        val r = store[id] ?: return
        val dir = File(r.dataDir).apply { mkdirs() }
        val onDisk = r.files.filter { it.index in r.wanted }.sumOf { f -> File(dir, f.path).length().coerceAtMost(f.size) }
        val required = r.wantedBytes - onDisk + SPACE_MARGIN
        val available = dir.usableSpace
        if (available in 1 until required) {
            fail(id, TorrentFailure(TorrentFailureCode.NOT_ENOUGH_SPACE, context.getString(R.string.torrent_space_detail, formatBytes(required), formatBytes(available))))
        }
    }

    private fun onDownloaded(r: TorrentRecord) {
        // No seeding: stop sharing as soon as the book is complete and keep only the files.
        runCatching { engine.remove(r.infoHash, deleteFiles = false) }
        store.resumeFile(r.id).delete()
        val next = if (r.reviewed && r.id !in editing.value) TorrentPhase.VERIFYING else TorrentPhase.DOWNLOADED
        store.update(r.id) { it.copy(phase = next, progress = 1f) }?.let { if (next == TorrentPhase.VERIFYING) ensureVerifying(it) }
    }

    private fun ensureFetching(r: TorrentRecord) {
        if (fetchJobs[r.id]?.isActive == true) return
        val magnet = r.magnetUri ?: return fail(r.id, TorrentFailure(TorrentFailureCode.METADATA_NOT_FOUND))
        fetchJobs[r.id] = scope.launch(Dispatchers.IO) {
            var attempts = 0
            while (isActive && network.online.value) {
                val bytes = try {
                    engine.fetchMetadata(magnet, FETCH_ATTEMPT_SECONDS, File(store.metadataTempDir, r.id))
                } catch (t: Throwable) {
                    Log.w(TAG, "Metadata fetch failed", t)
                    null
                }
                if (!isActive) return@launch
                if (bytes != null) {
                    withContext(serial) { onMetadata(r.id, bytes) }
                    return@launch
                }
                if (++attempts >= FETCH_MAX_ATTEMPTS) {
                    withContext(serial) { fail(r.id, TorrentFailure(TorrentFailureCode.METADATA_NOT_FOUND)) }
                    return@launch
                }
            }
        }.also { job -> job.invokeOnCompletion { fetchJobs.remove(r.id, job); File(store.metadataTempDir, r.id).deleteRecursively() } }
    }

    private fun onMetadata(id: String, bytes: ByteArray) {
        val record = store[id]?.takeIf { it.phase == TorrentPhase.FETCHING_METADATA } ?: return
        val meta = try {
            TorrentEngine.parse(bytes)
        } catch (e: Exception) {
            return fail(id, TorrentFailure(TorrentFailureCode.METADATA_NOT_FOUND))
        }
        when (val check = TorrentContentValidator.validate(meta.files)) {
            is TorrentContentCheck.Invalid -> fail(id, TorrentFailure(TorrentFailureCode.CONTENT_INVALID, problem = check.problem.name, files = check.files))
            is TorrentContentCheck.Valid -> {
                store.dir(id).mkdirs()
                store.writeTorrent(id, bytes)
                store.update(id) { withContent(it, meta, check).copy(phase = TorrentPhase.DOWNLOADING, infoHash = meta.infoHash) }
                startDownload(id)
                if (record.name != meta.name) Log.i(TAG, "Metadata received for ${meta.name}")
            }
        }
    }

    // ------------------------------------------------------------------ verification & conversion

    private fun ensureVerifying(r: TorrentRecord) {
        if (verifyJobs[r.id]?.isActive == true) return
        verifyJobs[r.id] = scope.launch(Dispatchers.IO) {
            val outcome = try {
                verifyAndPlan(r.id)
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.e(TAG, "Verification failed", t)
                Verification.Failed(TorrentFailure(TorrentFailureCode.AUDIOBOOK_INVALID, t.message))
            }
            withContext(serial) {
                val current = store[r.id]?.takeIf { it.phase == TorrentPhase.VERIFYING } ?: return@withContext
                when (outcome) {
                    is Verification.Failed -> fail(current.id, outcome.failure)
                    Verification.AlreadyInLibrary -> complete(current, current.bookId)
                    is Verification.Planned -> {
                        val jobId = imports.enqueue(outcome.request)
                        store.update(current.id) { it.copy(phase = TorrentPhase.CONVERTING, importJobId = jobId) }
                    }
                }
            }
        }.also { job -> job.invokeOnCompletion { verifyJobs.remove(r.id, job) } }
    }

    private sealed interface Verification {
        class Planned(val request: com.zyagodin.booksound.importer.ImportRequest) : Verification
        class Failed(val failure: TorrentFailure) : Verification
        data object AlreadyInLibrary : Verification
    }

    private suspend fun verifyAndPlan(id: String): Verification {
        val r = store[id] ?: return Verification.Failed(TorrentFailure(TorrentFailureCode.FILES_MISSING))
        // A conversion interrupted by a crash may have finished just before it.
        library.book(r.bookId)?.takeIf { !it.deleted }?.let { return Verification.AlreadyInLibrary }

        val dir = File(r.dataDir)
        val wantedFiles = r.files.filter { it.index in r.wanted }
        val missing = wantedFiles.filter { f -> File(dir, f.path).let { !it.isFile || it.length() != f.size } }
        if (missing.isNotEmpty()) return Verification.Failed(TorrentFailure(TorrentFailureCode.FILES_MISSING, files = missing.map { it.path }))

        val inputs = wantedFiles.map { f -> File(dir, f.path) to f.path.split('/').dropLast(1) }
        val analysis = analyzer.analyzeLocalFiles(inputs, r.name)
        val ready = analysis as? AnalysisState.Ready
            ?: return Verification.Failed(TorrentFailure(TorrentFailureCode.AUDIOBOOK_INVALID, files = (analysis as? AnalysisState.Failed)?.skipped.orEmpty().map { it.name }))
        if (ready.skipped.isNotEmpty()) {
            return Verification.Failed(
                TorrentFailure(TorrentFailureCode.AUDIOBOOK_INVALID, files = ready.skipped.map { "${it.name} — ${skipLabel(it.reason)}" }),
            )
        }
        val problems = AudiobookIntegrity.check(r.layout ?: return Verification.Failed(TorrentFailure(TorrentFailureCode.FILES_MISSING)), ready.files)
        if (problems.isNotEmpty()) {
            return Verification.Failed(
                TorrentFailure(TorrentFailureCode.AUDIOBOOK_INVALID, files = problems.map { "${it.fileName} — ${integrityLabel(it.issue)}" }),
            )
        }

        val review = r.review() ?: return Verification.Failed(TorrentFailure(TorrentFailureCode.FILES_MISSING))
        val pathByUri = r.files.associate { uriOf(r, it.path) to it.path }
        val merged = when (val m = TorrentReviewMerger.merge(review, ready.draft.metadata, ready.draft.parts) { pathByUri[it] }) {
            is MergedReview.MissingParts -> return Verification.Failed(TorrentFailure(TorrentFailureCode.FILES_MISSING, files = m.paths))
            is MergedReview.Ready -> m
        }
        val cover = customCover(id) ?: ready.draft.covers.firstNotNullOfOrNull { CoverImages.normalize(it.picture.bytes) }
        val outcome = planner.plan(
            ImportPlanner.Input(
                metadata = merged.metadata,
                parts = merged.parts,
                files = ready.files.associateBy { it.id },
                cover = cover,
                sourceName = r.name,
                isEdit = false,
                existingBookId = null,
                heldPermissions = emptyList(),
                newBookId = r.bookId,
                torrentId = r.id,
                strictValidation = true,
            ),
            // Unattended: never overwrite anything, add "Title (2)" instead.
            ImportDecision.KEEP_BOTH,
        )
        return when (outcome) {
            is PlanOutcome.Ready -> Verification.Planned(outcome.request)
            PlanOutcome.LibraryUnavailable -> Verification.Failed(TorrentFailure(TorrentFailureCode.CONVERSION_FAILED, context.getString(R.string.failure_library)))
            is PlanOutcome.NeedsDecision -> Verification.Failed(TorrentFailure(TorrentFailureCode.CONVERSION_FAILED, null))
        }
    }

    private fun onImportJobs(jobs: List<ImportJob>) {
        for (r in store.records.value) {
            if (r.phase != TorrentPhase.CONVERTING) continue
            val job = jobs.firstOrNull { it.id == r.importJobId } ?: continue
            when (job.stage) {
                ImportStage.DONE -> complete(r, job.resultBookId ?: r.bookId)
                ImportStage.FAILED -> fail(r.id, TorrentFailure(TorrentFailureCode.CONVERSION_FAILED, job.failure?.let { failureMessage(context, it) }))
                ImportStage.CANCELLED -> fail(r.id, TorrentFailure(TorrentFailureCode.CONVERSION_CANCELLED))
                else -> Unit
            }
        }
    }

    /** After a restart the in-memory import job is gone: finish or redo the conversion. */
    private suspend fun recoverConversion(r: TorrentRecord) {
        if (imports.jobs.value.any { it.id == r.importJobId }) return
        val book = library.book(r.bookId)
        if (book != null && !book.deleted) {
            complete(r, r.bookId)
        } else {
            store.update(r.id) { it.copy(phase = TorrentPhase.VERIFYING, importJobId = null) }?.let(::ensureVerifying)
        }
    }

    private fun complete(r: TorrentRecord, bookId: String) {
        store.update(r.id) {
            it.copy(phase = TorrentPhase.COMPLETED, resultBookId = bookId, failure = null, finishedAt = System.currentTimeMillis(), progress = 1f)
        }
        // The book is in the library as a verified M4B; the download is no longer needed.
        runCatching { engine.remove(r.infoHash, deleteFiles = true) }
        File(r.dataDir).deleteRecursively()
        store.resumeFile(r.id).delete()
        store.torrentFile(r.id).delete()
    }

    private fun fail(id: String, failure: TorrentFailure) {
        val r = store.update(id) { it.copy(phase = TorrentPhase.FAILED, failure = failure) } ?: return
        runCatching { engine.pause(r.infoHash) }
        Log.w(TAG, "Torrent ${r.name} failed: ${failure.code} ${failure.detail.orEmpty()} ${failure.files.take(3)}")
    }

    // ------------------------------------------------------------------ engine events

    private fun onEngineEvent(event: TorrentEngine.Event) {
        when (event) {
            is TorrentEngine.Event.ResumeData -> store.records.value.firstOrNull { it.infoHash == event.infoHash && it.phase == TorrentPhase.DOWNLOADING }
                ?.let { store.writeResume(it.id, event.data) }
            is TorrentEngine.Event.Error -> store.records.value.firstOrNull { it.infoHash == event.infoHash && it.phase == TorrentPhase.DOWNLOADING }
                ?.let { onEngineError(it.id, event.message, event.outOfSpace) }
        }
    }

    private fun onEngineError(id: String, message: String, outOfSpace: Boolean) {
        val code = if (outOfSpace) TorrentFailureCode.NOT_ENOUGH_SPACE else TorrentFailureCode.DOWNLOAD_ERROR
        fail(id, TorrentFailure(code, message))
    }

    // ------------------------------------------------------------------ helpers

    private fun wantedFlags(r: TorrentRecord): BooleanArray {
        val count = (r.files.maxOfOrNull { it.index } ?: -1) + 1
        return BooleanArray(count) { it in r.wanted }
    }

    private fun uriOf(r: TorrentRecord, path: String): String = Uri.fromFile(File(r.dataDir, path)).toString()

    private fun customCover(id: String): EmbeddedPicture? {
        val file = store.coverFile(id).takeIf { it.isFile } ?: return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return EmbeddedPicture.sniffMimeType(bytes)?.let { EmbeddedPicture(bytes, it) }
    }

    private fun skipLabel(reason: SkipReason): String = context.getString(
        when (reason) {
            SkipReason.UNSUPPORTED -> R.string.skip_unsupported
            SkipReason.CORRUPTED -> R.string.skip_corrupted
            SkipReason.UNREADABLE -> R.string.skip_unreadable
        },
    )

    private fun integrityLabel(issue: IntegrityIssue): String = context.getString(
        when (issue) {
            IntegrityIssue.WRONG_FORMAT -> R.string.integrity_wrong_format
            IntegrityIssue.NO_AUDIO_STREAM -> R.string.integrity_no_audio
            IntegrityIssue.NO_DURATION -> R.string.integrity_no_duration
            IntegrityIssue.IMPLAUSIBLE_BITRATE -> R.string.integrity_implausible
        },
    )

    private fun formatBytes(bytes: Long): String = android.text.format.Formatter.formatShortFileSize(context, bytes)

    companion object {
        private const val TAG = "TorrentManager"
        private const val MAX_TORRENT_BYTES = 10 * 1024 * 1024
        private const val SPACE_MARGIN = 64L * 1024 * 1024
        private const val FETCH_ATTEMPT_SECONDS = 60
        private const val FETCH_MAX_ATTEMPTS = 15
        private const val RESUME_SAVE_INTERVAL_MS = 30_000L
        private const val PROGRESS_SAVE_INTERVAL_MS = 15_000L
        private const val ENGINE_IDLE_STOP_MS = 20_000L
        private const val ADD_RETRY_MS = 15_000L
    }
}
