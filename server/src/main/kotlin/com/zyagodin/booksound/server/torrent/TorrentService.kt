package com.zyagodin.booksound.server.torrent

import com.zyagodin.booksound.api.ApiJson
import com.zyagodin.booksound.api.ErrorCodes
import com.zyagodin.booksound.api.EventDto
import com.zyagodin.booksound.api.EventTypes
import com.zyagodin.booksound.api.ImportStage
import com.zyagodin.booksound.api.MetadataDto
import com.zyagodin.booksound.api.TorrentBookDto
import com.zyagodin.booksound.api.TorrentDto
import com.zyagodin.booksound.api.TorrentFileDto
import com.zyagodin.booksound.api.TorrentPhase
import com.zyagodin.booksound.api.TorrentReviewRequest
import com.zyagodin.booksound.api.toDto
import com.zyagodin.booksound.core.model.BookId
import com.zyagodin.booksound.core.torrent.TorrentContentCheck
import com.zyagodin.booksound.core.torrent.TorrentContentValidator
import com.zyagodin.booksound.core.torrent.TorrentLink
import com.zyagodin.booksound.core.torrent.TorrentLinks
import com.zyagodin.booksound.core.torrent.TorrentSuggestions
import com.zyagodin.booksound.server.auth.Principal
import com.zyagodin.booksound.server.db.Database
import com.zyagodin.booksound.server.db.str
import com.zyagodin.booksound.server.events.Events
import com.zyagodin.booksound.server.importer.ImportService
import com.zyagodin.booksound.server.importer.ImportSpec
import com.zyagodin.booksound.server.importer.JobRecord
import com.zyagodin.booksound.server.importer.SourceFileRef
import com.zyagodin.booksound.server.library.LibraryException
import com.zyagodin.booksound.server.library.LibraryService
import com.zyagodin.booksound.server.settings.SettingsStore
import com.zyagodin.booksound.torrent.InvalidTorrentException
import com.zyagodin.booksound.torrent.TorrentEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.UUID

@Serializable
data class TorrentBookEntry(
    val id: String,
    val volume: String?,
    val layout: String,
    /** Audio files of the book, by index, in the analysed order. */
    val audio: List<Int>,
    val images: List<Int>,
    val suggested: MetadataDto,
    val edited: MetadataDto,
    /** Audio files the user kept, in order. */
    val parts: List<Int>,
    val reviewed: Boolean,
    val autoImport: Boolean,
    val phase: TorrentPhase,
    val bookId: String,
    val importJobId: String? = null,
    val error: String? = null,
)

@Serializable
data class TorrentEntry(
    val id: String,
    val name: String,
    val infoHash: String?,
    val magnet: String?,
    val files: List<TorrentFileEntry> = emptyList(),
    val phase: TorrentPhase,
    val error: String? = null,
    val books: List<TorrentBookEntry> = emptyList(),
    val addedAt: Long,
    val addedBy: String,
    val autoImport: Boolean,
    val sizeBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val progress: Float = 0f,
)

@Serializable
data class TorrentFileEntry(val index: Int, val path: String, val size: Long, val padding: Boolean = false)

/**
 * Torrent downloads, as on the phone: only audiobook-shaped torrents are taken, only their audio and cover
 * files are downloaded, nothing is seeded afterwards, and each book is converted into the library once its
 * details are confirmed (or right away when the torrent was added with auto-import).
 */
class TorrentService private constructor(
    private val db: Database,
    private val dir: File,
    private val library: LibraryService,
    private val imports: ImportService,
    private val settings: SettingsStore,
    private val events: Events,
    private val scope: CoroutineScope,
    private val engine: TorrentEngine,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(TorrentService::class.java)
    private val lock = Any()
    private val entries = LinkedHashMap<String, TorrentEntry>()
    /** Download rate and peers of running torrents; not stored. */
    private val rates = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Int>>()

    private fun torrentFile(id: String) = File(dir, "$id.torrent")
    private fun resumeFile(id: String) = File(dir, "$id.resume")
    private fun dataDir(id: String) = File(dir, id)
    private fun coverFile(entryId: String, bookId: String) = File(dir, "$entryId-$bookId.cover")

    fun start() {
        val stored = db.tx { query("SELECT body FROM torrents") { it.str("body")!! } }
        synchronized(lock) {
            stored.mapNotNull { runCatching { ApiJson.decodeFromString(TorrentEntry.serializer(), it) }.getOrNull() }.forEach { entries[it.id] = it }
        }
        engine.listener = { event ->
            when (event) {
                is TorrentEngine.Event.ResumeData -> entryByHash(event.infoHash)?.let { TorrentEngine.writeAtomically(resumeFile(it.id), event.data) }
                is TorrentEngine.Event.Error -> entryByHash(event.infoHash)?.let { e ->
                    log.warn("Torrent {} failed: {}", e.name, event.message)
                    update(e.id) { it.copy(phase = TorrentPhase.FAILED, error = if (event.outOfSpace) "Not enough disk space" else event.message) }
                    engine.remove(event.infoHash, deleteFiles = false)
                }
            }
        }
        imports.finishedListeners += ::onJobFinished
        for (e in list()) {
            when (e.phase) {
                TorrentPhase.FETCHING_METADATA -> fetchMetadata(e.id)
                TorrentPhase.DOWNLOADING -> addToEngine(e)
                TorrentPhase.VERIFYING, TorrentPhase.CONVERTING -> e.books.forEach { b -> if (b.phase == TorrentPhase.CONVERTING && imports.get(b.importJobId ?: "") == null) startImport(e.id, b.id) }
                else -> Unit
            }
        }
        scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { poll() }.onFailure { log.warn("Torrent status failed", it) }
                delay(2_000)
            }
        }
    }

    fun list(): List<TorrentEntry> = synchronized(lock) { entries.values.toList() }

    /** Everyone sees the torrents; only whoever added one, or an admin, changes or removes it. */
    fun checkAccess(torrentId: String, principal: Principal) {
        val entry = get(torrentId) ?: throw LibraryException(ErrorCodes.NOT_FOUND, "No such torrent")
        if (!principal.isAdmin && entry.addedBy != principal.userId) {
            throw LibraryException(ErrorCodes.FORBIDDEN, "Only whoever added this torrent or an admin can change it")
        }
    }

    fun get(id: String): TorrentEntry? = synchronized(lock) { entries[id] }

    /** Adds a magnet link, a .torrent URL or an info-hash. */
    fun addLink(text: String, autoImport: Boolean, userId: String): TorrentEntry = when (val link = TorrentLinks.parse(text)) {
        null -> throw LibraryException(ErrorCodes.BAD_REQUEST, "Not a magnet link, .torrent link or info-hash")
        is TorrentLink.Magnet -> {
            if (link.infoHash.startsWith("btmh:")) throw LibraryException(ErrorCodes.UNSUPPORTED, "BitTorrent v2-only links are not supported")
            existing(link.infoHash)?.let { return it }
            val entry = TorrentEntry(
                id = UUID.randomUUID().toString(),
                name = link.displayName ?: link.infoHash,
                infoHash = link.infoHash,
                magnet = link.uri,
                phase = TorrentPhase.FETCHING_METADATA,
                addedAt = System.currentTimeMillis(),
                addedBy = userId,
                autoImport = autoImport,
            )
            put(entry)
            fetchMetadata(entry.id)
            entry
        }
        is TorrentLink.Web -> addFile(download(link.url), autoImport, userId)
    }

    /** Adds a torrent from .torrent file bytes. */
    fun addFile(bytes: ByteArray, autoImport: Boolean, userId: String, magnet: String? = null, entryId: String? = null): TorrentEntry {
        val meta = try {
            TorrentEngine.parse(bytes)
        } catch (e: InvalidTorrentException) {
            throw LibraryException(ErrorCodes.BAD_REQUEST, "Not a valid .torrent file")
        }
        if (entryId == null) existing(meta.infoHash)?.let { return it }
        val id = entryId ?: UUID.randomUUID().toString()
        val files = meta.files.map { TorrentFileEntry(it.index, it.path, it.sizeBytes, it.isPadding) }
        val base = TorrentEntry(
            id = id, name = meta.name, infoHash = meta.infoHash, magnet = magnet, files = files, phase = TorrentPhase.DOWNLOADING,
            addedAt = get(id)?.addedAt ?: System.currentTimeMillis(), addedBy = userId, autoImport = autoImport, sizeBytes = meta.totalBytes,
        )
        val books = when (val check = TorrentContentValidator.validate(meta.files)) {
            is TorrentContentCheck.Invalid -> {
                val entry = base.copy(phase = TorrentPhase.FAILED, error = "Not an audiobook: ${check.problem.name.lowercase().replace('_', ' ')}" +
                    check.files.take(5).joinToString(prefix = " (", postfix = ")").takeIf { check.files.isNotEmpty() }.orEmpty())
                put(entry)
                throw LibraryException(ErrorCodes.NOT_AUDIOBOOK, entry.error!!)
            }
            is TorrentContentCheck.Valid -> listOf(bookOf(meta.name, check, 0, autoImport))
            is TorrentContentCheck.Collection -> check.books.mapIndexed { i, b -> bookOf(meta.name, b, i, autoImport) }
        }
        val entry = base.copy(books = books, sizeBytes = books.sumOf { b -> (b.audio + b.images).sumOf { i -> files[i].size } })
        TorrentEngine.writeAtomically(torrentFile(id), bytes)
        put(entry)
        addToEngine(entry)
        return entry
    }

    private fun bookOf(name: String, content: TorrentContentCheck.Valid, position: Int, autoImport: Boolean): TorrentBookEntry {
        val suggestion = TorrentSuggestions.build(name, content, position)
        val metadata = suggestion.metadata.toDto()
        return TorrentBookEntry(
            id = UUID.randomUUID().toString(),
            volume = content.volume,
            layout = content.layout.name,
            audio = content.audio.map { it.index },
            images = content.images.map { it.index },
            suggested = metadata,
            edited = metadata,
            parts = content.audio.map { it.index },
            reviewed = false,
            autoImport = autoImport,
            phase = TorrentPhase.DOWNLOADING,
            bookId = BookId.random().value,
        )
    }

    /** The user's details for one book of a torrent; converting starts once it is downloaded. */
    /** The user's own cover for a book of the torrent, used when it is converted. */
    fun setCover(torrentId: String, bookId: String, bytes: ByteArray) {
        val entry = get(torrentId) ?: throw LibraryException(ErrorCodes.NOT_FOUND, "No such torrent")
        if (entry.books.none { it.id == bookId }) throw LibraryException(ErrorCodes.NOT_FOUND, "No such book")
        coverFile(torrentId, bookId).writeBytes(bytes)
        update(torrentId) { it }
    }

    fun review(torrentId: String, bookId: String, request: TorrentReviewRequest, cover: ByteArray?) {
        val entry = get(torrentId) ?: throw LibraryException(ErrorCodes.NOT_FOUND, "No such torrent")
        val book = entry.books.firstOrNull { it.id == bookId } ?: throw LibraryException(ErrorCodes.NOT_FOUND, "No such book")
        if (book.phase == TorrentPhase.CONVERTING || book.phase == TorrentPhase.COMPLETED) throw LibraryException(ErrorCodes.CONFLICT, "The book is already being converted")
        val parts = request.files.filter { it in book.audio }.distinct()
        if (parts.isEmpty()) throw LibraryException(ErrorCodes.BAD_REQUEST, "Keep at least one file")
        if (request.metadata.title.isBlank()) throw LibraryException(ErrorCodes.BAD_REQUEST, "Title is required")
        cover?.let { coverFile(torrentId, bookId).writeBytes(it) }
        update(torrentId) { e ->
            e.copy(books = e.books.map { if (it.id == bookId) it.copy(edited = request.metadata, parts = parts, reviewed = true, autoImport = request.autoImport, error = null) else it })
        }
        val updated = get(torrentId)!!
        if (updated.phase == TorrentPhase.DOWNLOADING) engine.setWanted(updated.infoHash!!, wantedFlags(updated)) else maybeImport(torrentId)
    }

    fun remove(torrentId: String) {
        val entry = get(torrentId) ?: return
        entry.books.mapNotNull { it.importJobId }.forEach(imports::cancel)
        entry.infoHash?.let { engine.remove(it, deleteFiles = true) }
        dataDir(torrentId).deleteRecursively()
        listOf(torrentFile(torrentId), resumeFile(torrentId)).forEach(File::delete)
        dir.listFiles { f -> f.name.startsWith("$torrentId-") }?.forEach { it.delete() }
        synchronized(lock) { entries.remove(torrentId) }
        db.tx { update("DELETE FROM torrents WHERE id = ?", torrentId) }
        events.emit(EventDto(EventTypes.TORRENT_REMOVED, torrent = toDto(entry)))
    }

    /** Starts a failed book (or the whole torrent) again. */
    fun retry(torrentId: String) {
        val entry = get(torrentId) ?: throw LibraryException(ErrorCodes.NOT_FOUND, "No such torrent")
        when {
            entry.phase == TorrentPhase.FETCHING_METADATA || (entry.phase == TorrentPhase.FAILED && entry.files.isEmpty() && entry.magnet != null) -> {
                update(torrentId) { it.copy(phase = TorrentPhase.FETCHING_METADATA, error = null) }
                fetchMetadata(torrentId)
            }
            entry.books.all { it.phase == TorrentPhase.FAILED || it.phase == TorrentPhase.COMPLETED } && entry.files.isNotEmpty() -> {
                update(torrentId) { e ->
                    e.copy(phase = TorrentPhase.DOWNLOADING, error = null, books = e.books.map { if (it.phase == TorrentPhase.FAILED) it.copy(phase = TorrentPhase.DOWNLOADING, error = null) else it })
                }
                addToEngine(get(torrentId)!!)
            }
        }
    }

    // ---------------------------------------------------------------------------------- engine

    private fun fetchMetadata(id: String) {
        scope.launch(Dispatchers.IO) {
            val entry = get(id) ?: return@launch
            var bytes: ByteArray? = null
            for (attempt in 1..METADATA_ATTEMPTS) {
                bytes = runCatching { engine.fetchMetadata(entry.magnet!!, 60, File(dir, "fetch")) }.getOrNull()
                if (bytes != null || get(id) == null) break
            }
            if (get(id) == null) return@launch
            if (bytes == null) {
                update(id) { it.copy(phase = TorrentPhase.FAILED, error = "No peer sent the torrent's file list") }
                return@launch
            }
            runCatching { addFile(bytes, entry.autoImport, entry.addedBy, entry.magnet, entryId = id) }
                .onFailure { e -> if (e !is LibraryException) update(id) { it.copy(phase = TorrentPhase.FAILED, error = e.message) } }
        }
    }

    private fun addToEngine(entry: TorrentEntry) {
        val bytes = torrentFile(entry.id).takeIf { it.isFile }?.readBytes() ?: run {
            update(entry.id) { it.copy(phase = TorrentPhase.FAILED, error = "The .torrent file is gone") }
            return
        }
        engine.add(bytes, dataDir(entry.id), resumeFile(entry.id), wantedFlags(entry), entry.magnet)
        engine.resume(entry.infoHash!!)
    }

    /** Download only the files some book still needs: the audio the user kept, and pictures for the cover. */
    private fun wantedFlags(entry: TorrentEntry): BooleanArray {
        val wanted = entry.books.filter { it.phase != TorrentPhase.COMPLETED }.flatMap { it.parts + it.images }.toSet()
        return BooleanArray(entry.files.size) { it in wanted }
    }

    private fun poll() {
        for (entry in list()) {
            if (entry.phase != TorrentPhase.DOWNLOADING) continue
            val hash = entry.infoHash ?: continue
            val status = engine.status(hash) ?: continue
            if (status.paused) engine.resume(hash)
            val changed = entry.copy(progress = status.progress, downloadedBytes = status.wantedDoneBytes, sizeBytes = status.wantedBytes.takeIf { it > 0 } ?: entry.sizeBytes)
            rates[entry.id] = status.downloadRate.toLong() to status.peers
            if (status.isComplete) {
                engine.requestResumeData(hash)
                // Downloaded: out of the session at once, so nothing is seeded.
                engine.remove(hash, deleteFiles = false)
                update(entry.id) {
                    changed.copy(
                        phase = TorrentPhase.DOWNLOADED, progress = 1f,
                        books = it.books.map { b -> if (b.phase == TorrentPhase.DOWNLOADING) b.copy(phase = TorrentPhase.DOWNLOADED) else b },
                    )
                }
                maybeImport(entry.id)
            } else {
                // Progress is shown live and worked out again after a restart: not worth a database write every poll.
                update(entry.id, persist = false) { changed }
            }
        }
    }

    private fun maybeImport(torrentId: String) {
        val entry = get(torrentId) ?: return
        if (entry.phase == TorrentPhase.DOWNLOADING || entry.phase == TorrentPhase.FETCHING_METADATA) return
        for (book in entry.books) {
            if (book.phase == TorrentPhase.DOWNLOADED && (book.reviewed || book.autoImport)) startImport(torrentId, book.id)
            else if (book.phase == TorrentPhase.DOWNLOADED) update(torrentId) { e -> e.copy(books = e.books.map { if (it.id == book.id) it.copy(phase = TorrentPhase.AWAITING_REVIEW) else it }) }
            else if (book.phase == TorrentPhase.AWAITING_REVIEW && book.reviewed) startImport(torrentId, book.id)
        }
        refreshPhase(torrentId)
    }

    private fun startImport(torrentId: String, bookId: String) {
        val entry = get(torrentId) ?: return
        val book = entry.books.firstOrNull { it.id == bookId } ?: return
        val root = dataDir(torrentId)
        fun ref(index: Int): SourceFileRef {
            val path = entry.files[index].path
            return SourceFileRef(File(root, path).absolutePath, path.split('/').dropLast(1))
        }
        val missing = (book.parts + book.images).filter { i -> File(root, entry.files[i].path).let { !it.isFile || it.length() != entry.files[i].size } }
            .filter { it in book.parts }
        if (missing.isNotEmpty()) {
            update(torrentId) { e -> e.copy(books = e.books.map { if (it.id == bookId) it.copy(phase = TorrentPhase.FAILED, error = "Files missing: " + missing.joinToString { i -> entry.files[i].path }) else it }) }
            refreshPhase(torrentId)
            return
        }
        val spec = ImportSpec(
            files = (book.parts + book.images.filter { File(root, entry.files[it].path).isFile }).map(::ref),
            sourceName = book.volume ?: entry.name,
            edited = book.edited,
            suggested = book.suggested,
            partOrder = book.parts.map { File(root, entry.files[it].path).absolutePath },
            coverPath = coverFile(torrentId, bookId).takeIf { it.isFile }?.absolutePath,
            bookId = book.bookId,
            strict = true,
            torrentId = torrentId,
            addedBy = entry.addedBy,
            expectedLayout = book.layout,
        )
        val job = imports.enqueue(spec, book.edited.title)
        update(torrentId) { e -> e.copy(books = e.books.map { if (it.id == bookId) it.copy(phase = TorrentPhase.CONVERTING, importJobId = job.id, error = null) else it }) }
        refreshPhase(torrentId)
    }

    private fun onJobFinished(job: JobRecord) {
        val spec = job.spec as? ImportSpec ?: return
        val torrentId = spec.torrentId ?: return
        val entry = get(torrentId) ?: return
        update(torrentId) { e ->
            e.copy(books = e.books.map { b ->
                if (b.importJobId != job.id) b
                else when (job.stage) {
                    ImportStage.DONE -> b.copy(phase = TorrentPhase.COMPLETED, bookId = job.bookId ?: b.bookId)
                    ImportStage.CANCELLED -> b.copy(phase = TorrentPhase.AWAITING_REVIEW, reviewed = false, importJobId = null)
                    else -> b.copy(phase = TorrentPhase.FAILED, error = job.error)
                }
            })
        }
        refreshPhase(entry.id)
        val now = get(torrentId) ?: return
        // Every book is in the library: the download is no longer needed.
        if (now.books.all { it.phase == TorrentPhase.COMPLETED }) {
            dataDir(torrentId).deleteRecursively()
            resumeFile(torrentId).delete()
        }
    }

    private fun refreshPhase(torrentId: String) = update(torrentId) { e ->
        if (e.phase == TorrentPhase.DOWNLOADING || e.phase == TorrentPhase.FETCHING_METADATA || e.books.isEmpty()) return@update e
        val phases = e.books.map { it.phase }
        val phase = when {
            phases.all { it == TorrentPhase.COMPLETED } -> TorrentPhase.COMPLETED
            TorrentPhase.CONVERTING in phases -> TorrentPhase.CONVERTING
            TorrentPhase.AWAITING_REVIEW in phases || TorrentPhase.DOWNLOADED in phases -> TorrentPhase.AWAITING_REVIEW
            phases.all { it == TorrentPhase.COMPLETED || it == TorrentPhase.FAILED } -> TorrentPhase.FAILED
            else -> e.phase
        }
        e.copy(phase = phase, error = if (phase == TorrentPhase.FAILED) e.books.firstNotNullOfOrNull { it.error } else e.error)
    }

    // ------------------------------------------------------------------------------- storage

    private fun existing(infoHash: String) = list().firstOrNull { it.infoHash == infoHash }

    private fun entryByHash(hash: String) = list().firstOrNull { it.infoHash == hash }

    private fun put(entry: TorrentEntry) {
        synchronized(lock) { entries[entry.id] = entry }
        persist(entry)
        events.emit(EventDto(EventTypes.TORRENT, torrent = toDto(entry)))
    }

    private fun update(id: String, persist: Boolean = true, change: (TorrentEntry) -> TorrentEntry) {
        val updated = synchronized(lock) {
            val current = entries[id] ?: return
            change(current).also { entries[id] = it }
        }
        if (persist) persist(updated)
        events.emit(EventDto(EventTypes.TORRENT, torrent = toDto(updated)))
    }

    private fun persist(entry: TorrentEntry) = db.tx {
        update(
            "INSERT INTO torrents (id, body) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET body = excluded.body",
            entry.id, ApiJson.encodeToString(TorrentEntry.serializer(), entry),
        )
    }

    fun toDto(e: TorrentEntry): TorrentDto {
        val (rate, peers) = if (e.phase == TorrentPhase.DOWNLOADING) rates[e.id] ?: (0L to 0) else 0L to 0
        return TorrentDto(
            id = e.id,
            name = e.name,
            infoHash = e.infoHash,
            phase = e.phase,
            progress = e.progress,
            downloadRate = rate,
            peers = peers,
            sizeBytes = e.sizeBytes,
            downloadedBytes = e.downloadedBytes,
            error = e.error,
            addedAt = e.addedAt,
            books = e.books.map { b ->
                val kept = b.parts.toSet()
                TorrentBookDto(
                    id = b.id,
                    volume = b.volume,
                    metadata = b.edited,
                    files = b.audio.map { i -> TorrentFileDto(i, e.files[i].path, e.files[i].size, i in kept) },
                    phase = b.phase,
                    reviewed = b.reviewed,
                    autoImport = b.autoImport,
                    progress = when (b.phase) {
                        TorrentPhase.CONVERTING -> imports.get(b.importJobId ?: "")?.progress ?: 0f
                        TorrentPhase.COMPLETED -> 1f
                        else -> e.progress
                    },
                    bookId = b.bookId,
                    importJobId = b.importJobId,
                    error = b.error,
                    hasCustomCover = coverFile(e.id, b.id).isFile,
                )
            },
        )
    }

    override fun close() {
        runCatching {
            engine.requestAllResumeData()
            Thread.sleep(500)
            engine.stop()
        }
    }

    /** Fetches a .torrent file from a web link. Only http(s), at most 10 MB. */
    private fun download(url: String): ByteArray {
        val uri = URI(url)
        if (uri.scheme !in setOf("http", "https")) throw LibraryException(ErrorCodes.BAD_REQUEST, "Only http(s) links")
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BookSound")
        }
        try {
            if (connection.responseCode !in 200..299) throw LibraryException(ErrorCodes.BAD_REQUEST, "The link answered ${connection.responseCode}")
            val bytes = connection.inputStream.use { it.readNBytes(MAX_TORRENT_BYTES + 1) }
            if (bytes.size > MAX_TORRENT_BYTES) throw LibraryException(ErrorCodes.BAD_REQUEST, "Too large for a .torrent file")
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val METADATA_ATTEMPTS = 5
        private const val NATIVE_PROPERTY = "libtorrent4j.jni.path"

        /**
         * libtorrent4j loads its native library from [NATIVE_PROPERTY] or the system library path; the desktop
         * artifacts ship it inside the jar, so it is copied out first. Only x86_64 builds exist for Linux and Windows.
         */
        private fun prepareNativeLibrary(dir: File) {
            if (System.getProperty(NATIVE_PROPERTY) != null) return
            val arch = System.getProperty("os.arch").lowercase()
            if (arch != "amd64" && arch != "x86_64") error("no libtorrent build for $arch")
            val name = if (System.getProperty("os.name").lowercase().startsWith("windows")) "libtorrent4j.dll" else "libtorrent4j.so"
            val resource = TorrentService::class.java.getResource("/lib/x86_64/$name") ?: error("$name is not on the classpath")
            dir.mkdirs()
            val target = File(dir, name)
            val bytes = resource.readBytes()
            if (!target.isFile || target.length() != bytes.size.toLong()) {
                val tmp = File(dir, "$name.tmp")
                tmp.writeBytes(bytes)
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            System.setProperty(NATIVE_PROPERTY, target.absolutePath)
        }
        private const val MAX_TORRENT_BYTES = 10 * 1024 * 1024

        fun create(
            db: Database,
            dir: File,
            library: LibraryService,
            imports: ImportService,
            settings: SettingsStore,
            events: Events,
            scope: CoroutineScope,
        ): TorrentService? {
            val log = LoggerFactory.getLogger(TorrentService::class.java)
            val engine = try {
                prepareNativeLibrary(File(dir, "native"))
                // Loading the native library fails on platforms libtorrent4j has no build for (arm64 Linux).
                TorrentEngine(
                    File(dir, "session.state"),
                    tune = { pack ->
                        val s = settings.current
                        pack.uploadRateLimit(s.torrentUploadLimitKibps * 1024)
                        pack.listenInterfaces("0.0.0.0:${s.torrentListenPort},[::]:${s.torrentListenPort}")
                    },
                ) { message, error ->
                    if (error != null) log.warn(message, error) else log.debug(message)
                }.also { org.libtorrent4j.swig.libtorrent.version() }
            } catch (t: Throwable) {
                log.warn("Torrents are unavailable on this platform: {}", t.message)
                return null
            }
            dir.mkdirs()
            return TorrentService(db, dir, library, imports, settings, events, scope, engine)
        }
    }
}
