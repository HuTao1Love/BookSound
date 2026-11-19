package com.zyagodin.booksound.watch.sync

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.zyagodin.booksound.api.ApiJson
import com.zyagodin.booksound.api.ApiPaths
import com.zyagodin.booksound.api.DeviceKind
import com.zyagodin.booksound.api.LibraryEntryDto
import com.zyagodin.booksound.api.WatchFileStatusDto
import com.zyagodin.booksound.api.WatchProfileDto
import com.zyagodin.booksound.api.WatchProfileMode
import com.zyagodin.booksound.api.WatchServerLogin
import com.zyagodin.booksound.api.toModel
import com.zyagodin.booksound.core.model.BookId
import com.zyagodin.booksound.core.sync.ChangeSet
import com.zyagodin.booksound.core.sync.PlaybackRecord
import com.zyagodin.booksound.core.wear.WatchBookHeader
import com.zyagodin.booksound.core.wear.WatchPaths
import com.zyagodin.booksound.core.wear.WatchResult
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import com.zyagodin.booksound.shared.server.ServerAccountStore
import com.zyagodin.booksound.shared.server.ServerClient
import com.zyagodin.booksound.shared.server.ServerSyncBackend
import com.zyagodin.booksound.watch.data.WatchLibrary
import com.zyagodin.booksound.watch.data.WatchSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

/** A download from the server to the watch; [progress] is 0..1, or null while the server prepares the copy. */
data class ServerDownload(val bookId: String, val title: String, val progress: Float?, val error: String? = null) {
    val isActive get() = error == null
}

/**
 * The watch's own link to the BookSound server, over Wi-Fi or LTE. The watch signs in by itself (address, name and
 * password typed on the watch) or takes the phone's account: the phone hands over a login (the watch gets a device
 * of its own on the server). Positions saved here go to the server as well as to the phone, and books are downloaded
 * from the server's library; a book the phone sends comes from the server too when the watch reaches it.
 */
class WatchServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: WatchLibrary,
    private val settings: WatchSettings,
) {
    private val accounts = ServerAccountStore(context)
    val client = ServerClient(OkHttpClient(), accounts)
    private val prefs = context.getSharedPreferences("server_watch", Context.MODE_PRIVATE)

    val account = accounts.account

    private val _profileId = MutableStateFlow(prefs.getString("profile", null))
    /** Watch profile whose smaller copies are downloaded; null takes the original file. */
    val profileId: StateFlow<String?> = _profileId

    fun setProfile(id: String?) {
        prefs.edit { putString("profile", id) }
        _profileId.value = id
    }

    /** The login came from the phone; only then does the phone signing out sign the watch out too. */
    private var viaPhone: Boolean
        get() = prefs.getBoolean("via_phone", false)
        set(value) = prefs.edit { putBoolean("via_phone", value) }

    private val _signedOutHere = MutableStateFlow(prefs.getBoolean("signed_out_here", false))
    /**
     * The user signed out on the watch: the watch no longer asks the phone for its account by itself, and ignores
     * a login the phone sends unasked, until the user signs in again.
     */
    val signedOutHere: StateFlow<Boolean> = _signedOutHere

    private fun setSignedOutHere(value: Boolean) {
        prefs.edit { putBoolean("signed_out_here", value) }
        _signedOutHere.value = value
    }

    /** The user asked the phone for its account on this run; the answer is taken even over the watch's own login. */
    @Volatile private var phoneAnswerUntil = 0L

    private val _downloads = MutableStateFlow<List<ServerDownload>>(emptyList())
    val downloads: StateFlow<List<ServerDownload>> = _downloads
    private val jobs = ConcurrentHashMap<String, Job>()

    private val incoming = File(context.filesDir, "incoming").apply { mkdirs() }

    /**
     * A login from the phone; an empty message means the phone is signed out. A login of the watch's own stays: the
     * phone signing in or out doesn't touch it, unless the user asked the phone for its account.
     */
    @Synchronized
    fun onLogin(bytes: ByteArray) {
        val asked = android.os.SystemClock.elapsedRealtime() < phoneAnswerUntil
        phoneAnswerUntil = 0L
        val own = accounts.current != null && !viaPhone
        if (bytes.isEmpty()) {
            if (accounts.current != null && viaPhone) {
                cancelAll()
                accounts.clear()
            }
            return
        }
        if (!asked && (own || signedOutHere.value)) return
        val login = runCatching { ApiJson.decodeFromString(WatchServerLogin.serializer(), bytes.decodeToString()) }.getOrNull() ?: return
        client.adopt(login.url, login.tokens)
        viaPhone = true
        setSignedOutHere(false)
        setProfile(login.watchProfileId)
        pull()
    }

    /** Signs in with the watch's own device on the server, without the phone. Throws with the server's reason. */
    suspend fun signIn(url: String, user: String, password: String) {
        val name = listOfNotNull(android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, android.os.Build.MODEL).distinct().joinToString(" ")
        onFastNetwork { networkClient ->
            networkClient.login(url, user, password, name, DeviceKind.WATCH, settings.deviceId.value)
            viaPhone = false
            phoneAnswerUntil = 0L
            setSignedOutHere(false)
            val list = try {
                networkClient.get(ApiPaths.WATCH_PROFILES, ListSerializer(WatchProfileDto.serializer()))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            setProfile((list.firstOrNull { it.mode != WatchProfileMode.ON_DEMAND } ?: list.firstOrNull())?.id)
        }
        pull()
    }

    /** The server's watch profiles: smaller copies the watch can download instead of the original file. */
    suspend fun profiles(): List<WatchProfileDto> = onFastNetwork { it.get(ApiPaths.WATCH_PROFILES, ListSerializer(WatchProfileDto.serializer())) }

    /**
     * Asks the phone to sign the watch in with its account; the login arrives in [onLogin]. False when no phone with
     * BookSound is in reach.
     */
    suspend fun requestFromPhone(): Boolean = try {
        phoneAnswerUntil = android.os.SystemClock.elapsedRealtime() + PHONE_ANSWER_MS
        val node = Wearable.getCapabilityClient(context)
            .getCapability(WatchPaths.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
            .nodes.sortedByDescending { it.isNearby }.firstOrNull()
        if (node == null) {
            phoneAnswerUntil = 0L
            false
        }
        else {
            Wearable.getMessageClient(context).sendMessage(node.id, WatchPaths.SERVER_AUTH_REQUEST_PATH, ByteArray(0)).await()
            true
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        phoneAnswerUntil = 0L
        Log.w(TAG, "Can't reach the phone", e)
        false
    }

    /** Signs the watch out of the server (its device there is removed); books already on the watch stay. */
    fun signOut() = scope.launch {
        synchronized(this@WatchServer) {
            setSignedOutHere(true)
            phoneAnswerUntil = 0L
            viaPhone = false
            cancelAll()
            setProfile(null)
        }
        // Clear locally before contacting the server; revocation is best effort when offline.
        withTimeoutOrNull(5_000) { client.logout(clearImmediately = true) }
    }

    @Synchronized
    private fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _downloads.value = emptyList()
    }

    /**
     * The phone is about to send [bookId] over Bluetooth: take it from the server instead when the watch is signed in
     * and the server has the book. Answers [WatchResult.Downloading] then, so the phone doesn't send the file.
     */
    fun onPhoneOffersBook(bookId: String, phoneNode: String) {
        scope.launch {
            val result = try {
                if (accounts.current == null) WatchResult.Failed("not signed in")
                else {
                    val entry = withTimeout(OFFER_CHECK_MS) { onFastNetwork { it.get(ApiPaths.book(bookId), LibraryEntryDto.serializer()) } }
                    if (library.freeBytes() < neededBytes(entry.book.sizeBytes)) WatchResult.NoSpace
                    else {
                        if (download(entry)) WatchResult.Downloading else WatchResult.Failed("signed out")
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.i(TAG, "Server too slow to answer about $bookId")
                WatchResult.Failed("server")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not on this server, or the server is out of reach: the phone sends the book itself.
                Log.i(TAG, "Book $bookId not taken from the server: ${e.message}")
                WatchResult.Failed("server")
            }
            runCatching { Wearable.getMessageClient(context).sendMessage(phoneNode, WatchPaths.RESULT_PREFIX + bookId, result.encode()).await() }
                .onFailure { Log.w(TAG, "Can't answer the phone about $bookId", it) }
        }
    }

    private fun neededBytes(sizeBytes: Long) = (if (profileId.value != null) 0 else sizeBytes) + RESERVE_BYTES

    /** The server's library with this user's progress. */
    suspend fun catalog(): List<LibraryEntryDto> = onFastNetwork { it.get(ApiPaths.BOOKS, ListSerializer(LibraryEntryDto.serializer())) }

    /** Sends a position saved here to the server. */
    fun push(record: PlaybackRecord) {
        if (accounts.current == null) return
        scope.launch {
            runCatching { onFastNetwork { ServerSyncBackend(it).push(ChangeSet(playback = listOf(record)), settings.deviceId) } }
                .onFailure { Log.i(TAG, "Position not sent: ${it.message}") }
        }
    }

    /** Takes positions from the server for the books on the watch. */
    fun pull() {
        if (accounts.current == null) return
        scope.launch {
            runCatching {
                val positions = catalog().mapNotNull { it.playback?.toModel() }
                library.mergeRemote(positions)
            }.onFailure { Log.i(TAG, "Positions not pulled: ${it.message}") }
        }
    }

    @Synchronized
    fun download(entry: LibraryEntryDto): Boolean {
        if (accounts.current == null || signedOutHere.value) return false
        val id = entry.book.id
        if (jobs[id]?.isActive == true) return true
        _downloads.update { list -> list.filterNot { it.bookId == id } + ServerDownload(id, entry.book.metadata.title, null) }
        // From the background (a book the phone handed over) the system may not let it start; the download runs anyway.
        runCatching { ContextCompat.startForegroundService(context, Intent(context, WatchDownloadService::class.java)) }
            .onFailure { Log.w(TAG, "Download service not started", it) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                downloadBook(entry)
                _downloads.update { list -> list.filterNot { it.bookId == id } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (jobs[id] === coroutineContext[Job]) _downloads.update { list -> list.filterNot { it.bookId == id } }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download of $id failed", e)
                if (jobs[id] === coroutineContext[Job]) _downloads.update { list -> list.map { if (it.bookId == id) it.copy(error = e.message ?: "error") else it } }
            } finally {
                jobs.remove(id, coroutineContext[Job])
            }
        }
        jobs[id] = job
        job.start()
        return true
    }

    @Synchronized
    fun cancel(bookId: String) {
        jobs.remove(bookId)?.cancel()
        _downloads.update { list -> list.filterNot { it.bookId == bookId } }
    }

    private suspend fun downloadBook(listed: LibraryEntryDto) {
        onFastNetwork { client ->
            // The library list leaves chapters out; the book's own entry has them and the latest position.
            val entry = client.get(ApiPaths.book(listed.book.id), LibraryEntryDto.serializer())
            val book = entry.book
            val profile = profileId.value
            if (library.freeBytes() < neededBytes(book.sizeBytes)) throw IOException("Not enough space on the watch")
            val path = if (profile != null) {
                // The server makes the smaller copy first; that can take a few minutes.
                var status = client.get("${ApiPaths.bookWatchFile(book.id, profile)}&status=1", WatchFileStatusDto.serializer())
                while (!status.ready) {
                    status.job?.error?.let { throw IOException(it) }
                    delay(3_000)
                    status = client.get("${ApiPaths.bookWatchFile(book.id, profile)}&status=1", WatchFileStatusDto.serializer())
                }
                status.sizeBytes?.let { if (library.freeBytes() < it + RESERVE_BYTES) throw IOException("Not enough space on the watch") }
                ApiPaths.bookWatchFile(book.id, profile)
            } else {
                ApiPaths.bookFile(book.id)
            }
            val part = File(incoming, "${book.id}.server")
            val etag = File(incoming, "${book.id}.server.etag")
            client.download(path, part, etag) { done, total ->
                if (total > 0 && library.freeBytes() < total - done + RESERVE_BYTES) throw IOException("Not enough space on the watch")
                val progress = if (total > 0) done.toFloat() / total else null
                _downloads.update { list -> list.map { if (it.bookId == book.id) it.copy(progress = progress) else it } }
            }
            etag.delete()
            val cover = runCatching {
                val file = File(incoming, "${book.id}.cover")
                file.delete()
                client.download(ApiPaths.bookCover(book.id, COVER_SIZE), file, File(incoming, "${book.id}.cover.etag")) { _, _ -> }
                file.readBytes().also { file.delete() }
            }.getOrNull()
            File(incoming, "${book.id}.cover.etag").delete()
            val header = WatchBookHeader(
                id = BookId(book.id),
                metadata = book.metadata.toModel(),
                durationMs = book.durationMs,
                chapters = book.chapters.map { it.toModel() },
                fileSize = part.length(),
                fileRevision = book.fileRevision,
                playback = entry.playback?.toModel(),
                cover = cover,
            )
            currentCoroutineContext().ensureActive()
            library.add(header, part)
        }
    }

    /** Prefer the watch's own Wi-Fi, then LTE, retaining a requested network until the operation ends. */
    private suspend fun <T> onFastNetwork(block: suspend (ServerClient) -> T): T {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()
        fun connected(transport: Int) = connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)?.let {
                it.hasTransport(transport) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } == true
        }
        suspend fun request(transport: Int, timeout: Long): Network? = withTimeoutOrNull(timeout) {
            suspendCancellableCoroutine<Network?> { cont ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        synchronized(cont) { if (cont.isActive) cont.resume(network) }
                    }
                    override fun onUnavailable() {
                        synchronized(cont) { if (cont.isActive) cont.resume(null) }
                    }
                }
                callbacks += callback
                try {
                    connectivity.requestNetwork(NetworkRequest.Builder()
                        .addTransportType(transport)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(), callback)
                } catch (_: SecurityException) {
                    synchronized(cont) { if (cont.isActive) cont.resume(null) }
                }
            }
        }
        try {
            val transport = when {
                connected(NetworkCapabilities.TRANSPORT_WIFI) != null -> NetworkCapabilities.TRANSPORT_WIFI
                connected(NetworkCapabilities.TRANSPORT_CELLULAR) != null -> NetworkCapabilities.TRANSPORT_CELLULAR
                else -> null
            }
            val network = if (transport != null) request(transport, LTE_WAIT_MS) else
                request(NetworkCapabilities.TRANSPORT_WIFI, WIFI_WAIT_MS)
                    ?: request(NetworkCapabilities.TRANSPORT_CELLULAR, LTE_WAIT_MS)
            return block(network?.let(client::over) ?: client)
        } finally {
            callbacks.forEach { runCatching { connectivity.unregisterNetworkCallback(it) } }
        }
    }

    private companion object {
        const val TAG = "WatchServer"
        const val WIFI_WAIT_MS = 8_000L
        const val LTE_WAIT_MS = 3_000L
        const val PHONE_ANSWER_MS = 20_000L
        /** The phone waits 20 s for the answer before sending the book itself. */
        const val OFFER_CHECK_MS = 16_000L
        const val RESERVE_BYTES = 200L * 1024 * 1024
        const val COVER_SIZE = 256
    }
}
