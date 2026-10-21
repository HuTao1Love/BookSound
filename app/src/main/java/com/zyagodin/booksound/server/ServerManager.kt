package com.zyagodin.booksound.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.Wearable
import com.zyagodin.booksound.api.API_VERSION
import com.zyagodin.booksound.api.ApiJson
import com.zyagodin.booksound.api.ApiPaths
import com.zyagodin.booksound.api.BookDto
import com.zyagodin.booksound.api.DeviceKind
import com.zyagodin.booksound.api.TokenResponse
import com.zyagodin.booksound.api.UploadKind
import com.zyagodin.booksound.api.UploadResultDto
import com.zyagodin.booksound.api.WatchDeviceRequest
import com.zyagodin.booksound.api.WatchFileStatusDto
import com.zyagodin.booksound.api.WatchProfileDto
import com.zyagodin.booksound.api.WatchServerLogin
import com.zyagodin.booksound.api.toModel
import com.zyagodin.booksound.core.model.Audiobook
import com.zyagodin.booksound.core.model.BookFile
import com.zyagodin.booksound.core.model.BookId
import com.zyagodin.booksound.core.model.LibraryEntry
import com.zyagodin.booksound.core.metadata.AudioProbe
import com.zyagodin.booksound.core.sync.BackendStatus
import com.zyagodin.booksound.core.sync.SyncBackend
import com.zyagodin.booksound.core.model.EmbeddedPicture
import com.zyagodin.booksound.core.naming.LibraryLayout
import com.zyagodin.booksound.core.wear.WatchPaths
import com.zyagodin.booksound.cover.CoverImages
import com.zyagodin.booksound.cover.CoverStore
import com.zyagodin.booksound.data.db.AppDatabase
import com.zyagodin.booksound.data.db.BookEntity
import com.zyagodin.booksound.data.db.PlaybackStateEntity
import com.zyagodin.booksound.data.db.ServerBookEntity
import com.zyagodin.booksound.data.db.ServerPlaybackEntity
import com.zyagodin.booksound.data.library.BookDetails
import com.zyagodin.booksound.data.library.BookLocation
import com.zyagodin.booksound.data.library.LibraryItem
import com.zyagodin.booksound.data.library.LibraryRepository
import com.zyagodin.booksound.data.settings.SettingsRepository
import com.zyagodin.booksound.shared.server.ServerAccountStore
import com.zyagodin.booksound.shared.server.ServerClient
import com.zyagodin.booksound.shared.server.ServerException
import com.zyagodin.booksound.shared.server.ServerSyncBackend
import com.zyagodin.booksound.shared.server.SignedOutException
import com.zyagodin.booksound.storage.DocumentStore
import com.zyagodin.booksound.sync.SyncTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.serializer
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** A book moving between the phone and the server. */
data class ServerTransfer(
    val bookId: String,
    val title: String,
    val direction: Direction,
    val stage: Stage = Stage.QUEUED,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
) {
    enum class Direction { DOWNLOAD, UPLOAD }
    /** [FINISHING]: every byte is on the server, which checks the book and adds it to the library. */
    enum class Stage { QUEUED, RUNNING, FINISHING, DONE, FAILED, CANCELLED }

    val isActive get() = stage == Stage.QUEUED || stage == Stage.RUNNING || stage == Stage.FINISHING
    val progress: Float? get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
}

/** What the server said about itself in the last sync round. */
data class ServerInfo(val version: String, val apiVersion: Int) {
    /** It speaks another API than this app: nothing syncs until the older side is updated. */
    val incompatible: Boolean get() = apiVersion != API_VERSION

    /** The server is the older side of an [incompatible] pair. */
    val serverOutdated: Boolean get() = apiVersion < API_VERSION
}

/** A book of the server's library that is not on this phone. */
data class ServerOnlyBook(val entity: ServerBookEntity, val positionMs: Long?, val finished: Boolean)

/**
 * The phone's link to the optional BookSound server: signing in and out, syncing, moving books both ways
 * and signing the watch in. Without an account every part of the app works as before.
 */
class ServerManager(
    private val context: Context,
    private val scope: CoroutineScope,
    http: OkHttpClient,
    private val db: AppDatabase,
    private val library: LibraryRepository,
    private val documents: DocumentStore,
    private val covers: CoverStore,
    private val settings: SettingsRepository,
) {
    val accounts = ServerAccountStore(context)
    val client = ServerClient(http, accounts)
    val store = ServerLocalStore(db, accounts)
    private val _reachable = MutableStateFlow(true)
    /** False when the last attempt to reach the server failed; the app then works from what it has. */
    val reachable: StateFlow<Boolean> = _reachable

    private val _info = MutableStateFlow<ServerInfo?>(null)
    /** The server's version and API, once a sync round reached it; null before that and without an account. */
    val info: StateFlow<ServerInfo?> = _info

    private val backend = ServerSyncBackend(client)
    val syncTarget = SyncTarget(
        object : SyncBackend by backend {
            override suspend fun status() = backend.status().also { status ->
                _reachable.value = status != BackendStatus.Unreachable
                when (status) {
                    is BackendStatus.Available -> _info.value = ServerInfo(status.serverVersion, API_VERSION)
                    is BackendStatus.Incompatible -> _info.value = ServerInfo(status.serverVersion, status.serverApi)
                    BackendStatus.NotConfigured -> _info.value = null
                    // Offline or signed out: what it said last still holds.
                    BackendStatus.Unreachable, is BackendStatus.Unauthorized -> Unit
                }
            }
        },
        store,
    )

    val account = accounts.account

    private val _transfers = MutableStateFlow<List<ServerTransfer>>(emptyList())
    val transfers: StateFlow<List<ServerTransfer>> = _transfers
    private val queue = Channel<String>(Channel.UNLIMITED)
    private var current: Pair<String, Job>? = null

    private val workDir = File(context.cacheDir, "server").apply { mkdirs() }

    /** Covers of the server's books that are not on the phone, shown in the library; named "<id>-<sha>". */
    private val coverDir = File(context.filesDir, "server-covers").apply { mkdirs() }
    /** Raised when a cover lands in [coverDir], so the library shows it. */
    private val coversChanged = MutableStateFlow(0)

    /** Called to start a sync round after the account or the catalog changed. */
    var requestSync: () -> Unit = {}

    init {
        scope.launch(Dispatchers.IO) { keepServerCovers() }
        scope.launch {
            for (key in queue) {
                val transfer = _transfers.value.firstOrNull { it.key == key && it.stage == ServerTransfer.Stage.QUEUED } ?: continue
                val job = scope.launch { run(transfer) }
                current = key to job
                job.join()
                current = null
            }
        }
    }

    private val ServerTransfer.key get() = "${direction.name}:$bookId"

    // --------------------------------------------------------------------------------- account

    suspend fun signIn(url: String, user: String, password: String) {
        val name = listOfNotNull(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" ")
        client.login(url, user, password, name, DeviceKind.PHONE, library.device().value)
        store.clear()
        _info.value = null
        requestSync()
        // The watch uses this account too; a watch out of reach asks again when it opens its server screen.
        scope.launch { runCatching { connectWatch(settings.current().serverWatchProfile) } }
    }

    /** The watch asked to use this phone's account: sign it in, or tell it there is none. */
    fun onWatchAsksToSignIn() {
        scope.launch {
            runCatching {
                if (accounts.current != null) connectWatch(settings.current().serverWatchProfile) else sendToWatch(ByteArray(0))
            }.onFailure { Log.w(TAG, "Can't sign the watch in", it) }
        }
    }

    suspend fun signOut() {
        _transfers.value.filter { it.isActive }.forEach { cancel(it.bookId, it.direction) }
        client.logout()
        store.clear()
        _info.value = null
        runCatching { sendToWatch(ByteArray(0)) }
    }

    /** Books on the server but not on the phone, with the user's progress on the server. */
    fun serverOnlyBooks(): Flow<List<ServerOnlyBook>> = combine(db.server().observeBooks(), db.books().observeLibrary()) { remote, local ->
        val here = local.map { it.book.id }.toSet()
        val positions = db.server().playback().associateBy { it.bookId }
        remote.filter { it.id !in here }.map { ServerOnlyBook(it, positions[it.id]?.positionMs, positions[it.id]?.finished == true) }
    }

    /** Books on the phone that the server does not have. */
    fun phoneOnlyBooks(): Flow<List<BookEntity>> = combine(db.server().observeBooks(), db.books().observeLibrary()) { remote, local ->
        val there = remote.map { it.id }.toSet()
        local.map { it.book }.filter { it.id !in there && it.missingSince == null }
    }

    /**
     * The library as the phone shows it: its own books, each saying whether the server has it, and the server's
     * books that are not on the phone. Without an account it is just the phone's library.
     */
    fun libraryWithServer(): Flow<List<LibraryItem>> = combine(
        library.library, db.server().observeBooks(), db.server().observePlayback(), account, coversChanged,
    ) { local, remote, playback, account, _ ->
        if (account == null) return@combine local
        val there = remote.map { it.id }.toSet()
        val here = local.map { it.id }.toSet()
        val positions = playback.associateBy { it.bookId }
        local.map { it.copy(location = if (it.id in there) BookLocation.SYNCED else BookLocation.PHONE_ONLY) } +
            remote.filter { it.id !in here }.mapNotNull { serverItem(it, positions[it.id])?.first }
    }.flowOn(Dispatchers.IO)

    /** The page of a book that is only on the server, or null when the server doesn't have it. */
    fun serverDetails(bookId: String): Flow<BookDetails?> = combine(
        db.server().observeBooks(), db.server().observePlayback(), account, coversChanged,
    ) { remote, playback, account, _ ->
        val book = remote.firstOrNull { it.id == bookId }?.takeIf { account != null } ?: return@combine null
        val position = playback.firstOrNull { it.bookId == bookId }
        val (item, dto) = serverItem(book, position) ?: return@combine null
        BookDetails(
            item = item,
            chapters = item.entry.book.chapters,
            relativePath = dto.relativePath.orEmpty(),
            fileUri = Uri.EMPTY,
            fileSize = dto.sizeBytes,
            speed = position?.speed,
        )
    }.flowOn(Dispatchers.IO)

    private fun serverItem(book: ServerBookEntity, playback: ServerPlaybackEntity?): Pair<LibraryItem, BookDto>? {
        val dto = runCatching { ApiJson.decodeFromString(BookDto.serializer(), book.body) }.getOrNull() ?: return null
        val audiobook = Audiobook(
            id = BookId(dto.id),
            metadata = dto.metadata.toModel(),
            durationMs = dto.durationMs,
            chapters = dto.chapters.map { it.toModel() },
            file = BookFile("", dto.sizeBytes, dto.sha256, dto.fileRevision),
            addedAt = dto.addedAt,
            sync = dto.stamp.toModel(),
        )
        val item = LibraryItem(
            entry = LibraryEntry(audiobook, playback?.positionMs ?: 0L, playback?.finished == true, playback?.lastPlayedAt),
            coverPath = coverFile(book)?.takeIf { it.exists() }?.absolutePath,
            isMissing = false,
            chapterCount = dto.chapters.size,
            location = BookLocation.SERVER_ONLY,
        )
        return item to dto
    }

    private fun coverFile(book: ServerBookEntity): File? = book.coverSha256?.let { File(coverDir, "${book.id}-${it.take(16)}") }

    /** Downloads the covers of the server's books the phone lacks, and drops the ones no longer needed. */
    private suspend fun keepServerCovers() {
        combine(db.server().observeBooks(), db.books().observeLibrary().map { rows -> rows.map { it.book.id }.toSet() }) { remote, here ->
            remote.filter { it.id !in here }
        }.distinctUntilChanged().collectLatest { books ->
            val wanted = books.mapNotNull { book -> coverFile(book)?.let { book to it } }
            val keep = wanted.map { it.second.name }.toSet()
            coverDir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
            for ((book, file) in wanted) {
                if (file.exists() || accounts.current == null) continue
                val picture = try {
                    downloadCover(book.id)
                } catch (e: IOException) {
                    // Offline or signed out: the next change of the server's books tries again.
                    return@collectLatest
                } ?: continue
                val part = File(coverDir, "${file.name}.part")
                part.writeBytes(picture.bytes)
                if (part.renameTo(file)) coversChanged.update { it + 1 } else part.delete()
            }
        }
    }

    /** Ids of the books the server has. */
    fun serverBookIds(): Flow<Set<String>> = db.server().observeBooks().map { list -> list.map { it.id }.toSet() }

    suspend fun watchProfiles(): List<WatchProfileDto> =
        client.get(ApiPaths.WATCH_PROFILES, kotlinx.serialization.builtins.ListSerializer(WatchProfileDto.serializer()))

    // ------------------------------------------------------------------------------- transfers

    fun download(book: ServerBookEntity) = enqueue(ServerTransfer(book.id, book.title, ServerTransfer.Direction.DOWNLOAD, totalBytes = book.sizeBytes))

    /** Downloads a book of the server by its id; nothing happens when the server no longer has it. */
    suspend fun download(bookId: String) {
        db.server().book(bookId)?.let { download(it) }
    }

    fun upload(bookId: String, title: String) = enqueue(ServerTransfer(bookId, title, ServerTransfer.Direction.UPLOAD))

    /** Sends every book of the phone that the server lacks. */
    fun uploadAll(books: List<BookEntity>) = books.forEach { upload(it.id, it.title) }

    /** Brings every book of the server that the phone lacks. */
    fun downloadAll(books: List<ServerOnlyBook>) = books.forEach { download(it.entity) }

    private fun enqueue(transfer: ServerTransfer) {
        if (accounts.current == null) return
        var added = false
        _transfers.update { list ->
            if (list.any { it.key == transfer.key && it.isActive }) list
            else (list.filterNot { it.key == transfer.key } + transfer).also { added = true }
        }
        if (!added) return
        queue.trySend(transfer.key)
        ContextCompat.startForegroundService(context, Intent(context, ServerTransferService::class.java))
    }

    fun cancel(bookId: String, direction: ServerTransfer.Direction) {
        val key = "${direction.name}:$bookId"
        if (current?.first == key) current?.second?.cancel()
        setStage(key, ServerTransfer.Stage.CANCELLED)
    }

    fun clearFinished() = _transfers.update { list -> list.filter { it.isActive } }

    private fun setStage(key: String, stage: ServerTransfer.Stage, error: String? = null) =
        _transfers.update { list -> list.map { if (it.key == key && it.isActive) it.copy(stage = stage, error = error) else it } }

    private fun progress(key: String, done: Long, total: Long) =
        _transfers.update { list -> list.map { if (it.key == key) it.copy(doneBytes = done, totalBytes = if (total > 0) total else it.totalBytes) else it } }

    private suspend fun run(transfer: ServerTransfer) {
        val key = transfer.key
        setStage(key, ServerTransfer.Stage.RUNNING)
        try {
            when (transfer.direction) {
                ServerTransfer.Direction.DOWNLOAD -> downloadBook(transfer.bookId) { d, t -> progress(key, d, t) }
                ServerTransfer.Direction.UPLOAD -> uploadBook(transfer.bookId, { d, t -> progress(key, d, t) }) {
                    setStage(key, ServerTransfer.Stage.FINISHING)
                }
            }
            setStage(key, ServerTransfer.Stage.DONE)
            requestSync()
        } catch (e: CancellationException) {
            setStage(key, ServerTransfer.Stage.CANCELLED)
        } catch (e: Exception) {
            Log.w(TAG, "Transfer of ${transfer.bookId} failed", e)
            setStage(key, ServerTransfer.Stage.FAILED, e.message ?: e.javaClass.simpleName)
        }
    }

    /** Downloads a server book (resuming a partial download) and puts it into the library like an import does. */
    private suspend fun downloadBook(bookId: String, onProgress: (Long, Long) -> Unit) {
        val entity = db.server().book(bookId) ?: throw IOException("The book is no longer on the server")
        val dto = ApiJson.decodeFromString(BookDto.serializer(), entity.body)
        val treeUri = settings.current().libraryTreeUri?.let(Uri::parse) ?: throw IOException("Choose a library folder first")
        if (!documents.hasPersistedPermission(treeUri, write = true)) throw IOException("No access to the library folder")
        val part = File(workDir, "$bookId-r${dto.fileRevision}.part")
        val etag = File(workDir, "$bookId-r${dto.fileRevision}.etag")
        client.download(ApiPaths.bookFile(bookId), part, etag, onProgress)

        // The file must be the book it claims to be before it goes into the library.
        val parsed = documents.openSource(Uri.fromFile(part), part.name).use { AudioProbe.probe(it, part.name) }
        if (parsed.bookId != bookId) throw IOException("The server sent a different book")

        val metadata = dto.metadata.toModel()
        val path = LibraryLayout.pathFor(metadata, metadata.title)
        var dir = documents.rootDocumentId(treeUri)
        for (name in path.directories) dir = documents.ensureDirectory(treeUri, dir, name)
        val name = LibraryLayout.uniqueFileName(path.fileName, documents.children(treeUri, dir).map { it.name })
        val partialName = "${LibraryLayout.PARTIAL_PREFIX}server-$bookId.m4b"
        val target = documents.createFile(treeUri, dir, partialName, "application/octet-stream")
        try {
            documents.openForWrite(target).use { pfd ->
                java.io.FileOutputStream(pfd.fileDescriptor).use { out ->
                    FileInputStream(part).use { it.copyTo(out, 1 shl 20) }
                    out.flush()
                    pfd.fileDescriptor.sync()
                }
            }
        } catch (e: Exception) {
            documents.delete(target)
            throw e
        }
        val finalUri = documents.rename(target, name)
        val finalName = documents.entry(treeUri, finalUri)?.name ?: name

        val cover = runCatching { downloadCover(bookId) }.getOrNull()
        val coverFile = cover?.let { covers.save(bookId, it) }
        library.saveImportedBook(
            bookId = bookId,
            metadata = metadata,
            durationMs = dto.durationMs,
            chapters = dto.chapters.map { it.toModel() },
            fileUri = finalUri,
            relativePath = (path.directories + finalName).joinToString("/"),
            fileSize = part.length(),
            fileSha256 = dto.sha256,
            coverPath = coverFile?.absolutePath,
            coverSha256 = cover?.let { CoverImages.sha256(it.bytes) },
            keepAddedAt = false,
        )
        // Continue where the user is on the server.
        db.server().playback(bookId)?.let { p ->
            if (db.playback().get(bookId) == null) {
                db.playback().upsert(PlaybackStateEntity(bookId, p.positionMs, p.speed, p.finished, p.lastPlayedAt, p.revision, p.updatedAt, p.updatedBy, dirty = true))
            }
        }
        part.delete()
        etag.delete()
    }

    private suspend fun downloadCover(bookId: String): EmbeddedPicture? {
        val file = File(workDir, "$bookId.cover")
        val etag = File(workDir, "$bookId.cover.etag")
        file.delete()
        return try {
            client.download(ApiPaths.bookCover(bookId), file, etag) { _, _ -> }
            val bytes = file.readBytes()
            EmbeddedPicture.sniffMimeType(bytes)?.let { EmbeddedPicture(bytes, it) }
        } catch (e: ServerException) {
            null
        } finally {
            file.delete()
            etag.delete()
        }
    }

    /** Sends a library book to the server; it lands in the shared library with the same id. */
    private suspend fun uploadBook(bookId: String, onProgress: (Long, Long) -> Unit, onFinishing: () -> Unit) {
        val book = library.book(bookId)?.takeIf { !it.deleted } ?: throw IOException("The book is gone")
        val uri = Uri.parse(book.fileUri)
        val size = documents.describe(uri)?.second?.takeIf { it > 0 } ?: throw IOException("Can't read the book file")
        val upload = client.upload(
            fileName = book.relativePath.substringAfterLast('/'),
            size = size,
            kind = UploadKind.BOOK,
            sha256 = book.fileSha256?.takeIf { size == book.fileSize },
            open = { offset ->
                val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open the book file")
                var skipped = 0L
                while (skipped < offset) {
                    val n = input.skip(offset - skipped)
                    if (n <= 0) throw IOException("Can't seek in the book file")
                    skipped += n
                }
                input
            },
            onProgress = onProgress,
        )
        onFinishing()
        // The server keeps the upload when adding it fails on its side, so that is worth another try.
        var attempt = 0
        while (true) {
            try {
                client.post(ApiPaths.uploadComplete(upload.id), "", String.serializer(), UploadResultDto.serializer())
                return
            } catch (e: ServerException) {
                if (e.status < 500 || ++attempt >= COMPLETE_ATTEMPTS) throw e
                delay(5_000L * attempt)
            }
        }
    }

    // ----------------------------------------------------------------------------------- watch

    /**
     * Signs the watch in with the phone's account: it gets a device and tokens of its own on the server, sent over
     * the Data Layer, so it reaches the server by itself over Wi-Fi or LTE.
     */
    suspend fun connectWatch(watchProfileId: String?): Boolean {
        val tokens = client.post(
            ApiPaths.WATCH_DEVICE,
            WatchDeviceRequest("Watch", null),
            WatchDeviceRequest.serializer(),
            TokenResponse.serializer(),
        )
        val payload = ApiJson.encodeToString(WatchServerLogin.serializer(), WatchServerLogin(accounts.current!!.url, tokens, watchProfileId))
        return sendToWatch(payload.toByteArray())
    }

    private suspend fun sendToWatch(bytes: ByteArray): Boolean {
        val nodes = Wearable.getCapabilityClient(context)
            .getCapability(WatchPaths.WATCH_CAPABILITY, com.google.android.gms.wearable.CapabilityClient.FILTER_REACHABLE).await().nodes
        if (nodes.isEmpty()) return false
        val messages = Wearable.getMessageClient(context)
        nodes.forEach { messages.sendMessage(it.id, WatchPaths.SERVER_AUTH_PATH, bytes).await() }
        return true
    }

    /**
     * The server's smaller watch copy of a book, downloaded to a temporary file, or null when the book is not on
     * the server or no copy can be made in time. The caller deletes the file.
     */
    suspend fun watchCopy(bookId: String, profileId: String, onWaiting: () -> Unit): File? {
        if (accounts.current == null || db.server().book(bookId) == null) return null
        return try {
            val ready = withTimeoutOrNull(WATCH_COPY_WAIT_MS) {
                var status = client.get("${ApiPaths.bookWatchFile(bookId, profileId)}&status=1", WatchFileStatusDto.serializer())
                while (!status.ready && status.job?.error == null) {
                    onWaiting()
                    delay(3_000)
                    status = client.get("${ApiPaths.bookWatchFile(bookId, profileId)}&status=1", WatchFileStatusDto.serializer())
                }
                status.ready
            }
            if (ready != true) return null
            val file = File(workDir, "$bookId-watch-$profileId.m4b")
            val etag = File(workDir, "$bookId-watch-$profileId.etag")
            client.download(ApiPaths.bookWatchFile(bookId, profileId), file, etag) { _, _ -> }
            etag.delete()
            file
        } catch (e: SignedOutException) {
            null
        } catch (e: IOException) {
            Log.w(TAG, "No watch copy of $bookId", e)
            null
        }
    }

    companion object {
        private const val COMPLETE_ATTEMPTS = 3
        private const val TAG = "ServerManager"
        private const val WATCH_COPY_WAIT_MS = 15 * 60 * 1000L
    }
}
