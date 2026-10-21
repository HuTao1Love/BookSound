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
import com.zyagodin.booksound.api.LibraryEntryDto
import com.zyagodin.booksound.api.WatchFileStatusDto
import com.zyagodin.booksound.api.WatchServerLogin
import com.zyagodin.booksound.api.toModel
import com.zyagodin.booksound.core.model.BookId
import com.zyagodin.booksound.core.sync.ChangeSet
import com.zyagodin.booksound.core.sync.PlaybackRecord
import com.zyagodin.booksound.core.wear.WatchBookHeader
import com.zyagodin.booksound.core.wear.WatchPaths
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * The watch's own link to the BookSound server, over Wi-Fi or LTE. It uses the phone's account: the phone hands
 * over a login (the watch gets a device of its own on the server). Positions saved here go to the server as well
 * as to the phone, and books can be downloaded from the server's library.
 */
class WatchServer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val library: WatchLibrary,
    private val settings: WatchSettings,
) {
    private val accounts = ServerAccountStore(context)
    val client = ServerClient(OkHttpClient(), accounts)
    private val backend = ServerSyncBackend(client)
    private val prefs = context.getSharedPreferences("server_watch", Context.MODE_PRIVATE)

    val account = accounts.account

    /** Watch profile whose smaller copies are downloaded; null takes the original file. */
    var profileId: String?
        get() = prefs.getString("profile", null)
        private set(value) = prefs.edit { putString("profile", value) }

    private val _downloads = MutableStateFlow<List<ServerDownload>>(emptyList())
    val downloads: StateFlow<List<ServerDownload>> = _downloads
    private val jobs = HashMap<String, Job>()

    private val incoming = File(context.filesDir, "incoming").apply { mkdirs() }

    /** A login from the phone; an empty message signs out. */
    fun onLogin(bytes: ByteArray) {
        if (bytes.isEmpty()) {
            accounts.clear()
            return
        }
        val login = runCatching { ApiJson.decodeFromString(WatchServerLogin.serializer(), bytes.decodeToString()) }.getOrNull() ?: return
        client.adopt(login.url, login.tokens)
        profileId = login.watchProfileId
        pull()
    }

    /**
     * Asks the phone to sign the watch in with its account; the login arrives in [onLogin]. False when no phone with
     * BookSound is in reach.
     */
    suspend fun requestFromPhone(): Boolean = try {
        val node = Wearable.getCapabilityClient(context)
            .getCapability(WatchPaths.PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE).await()
            .nodes.sortedByDescending { it.isNearby }.firstOrNull()
        if (node == null) false
        else {
            Wearable.getMessageClient(context).sendMessage(node.id, WatchPaths.SERVER_AUTH_REQUEST_PATH, ByteArray(0)).await()
            true
        }
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(TAG, "Can't reach the phone", e)
        false
    }

    fun signOut() = scope.launch { client.logout() }

    /** The server's library with this user's progress. */
    suspend fun catalog(): List<LibraryEntryDto> = client.get(ApiPaths.BOOKS, ListSerializer(LibraryEntryDto.serializer()))

    /** Sends a position saved here to the server. */
    fun push(record: PlaybackRecord) {
        if (accounts.current == null) return
        scope.launch {
            runCatching { backend.push(ChangeSet(playback = listOf(record)), settings.deviceId) }
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

    fun download(entry: LibraryEntryDto) {
        val id = entry.book.id
        if (jobs[id]?.isActive == true) return
        _downloads.update { list -> list.filterNot { it.bookId == id } + ServerDownload(id, entry.book.metadata.title, null) }
        ContextCompat.startForegroundService(context, Intent(context, WatchDownloadService::class.java))
        jobs[id] = scope.launch {
            try {
                downloadBook(entry)
                _downloads.update { list -> list.filterNot { it.bookId == id } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _downloads.update { list -> list.filterNot { it.bookId == id } }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Download of $id failed", e)
                _downloads.update { list -> list.map { if (it.bookId == id) it.copy(error = e.message ?: "error") else it } }
            }
        }
    }

    fun cancel(bookId: String) {
        jobs.remove(bookId)?.cancel()
        _downloads.update { list -> list.filterNot { it.bookId == bookId } }
    }

    private suspend fun downloadBook(entry: LibraryEntryDto) {
        val book = entry.book
        val profile = profileId
        if (library.freeBytes() < book.sizeBytes / (if (profile != null) 4 else 1) + RESERVE_BYTES) throw IOException("Not enough space on the watch")
        onFastNetwork { client ->
            val path = if (profile != null) {
                // The server makes the smaller copy first; that can take a few minutes.
                var status = client.get("${ApiPaths.bookWatchFile(book.id, profile)}&status=1", WatchFileStatusDto.serializer())
                while (!status.ready) {
                    status.job?.error?.let { throw IOException(it) }
                    delay(3_000)
                    status = client.get("${ApiPaths.bookWatchFile(book.id, profile)}&status=1", WatchFileStatusDto.serializer())
                }
                ApiPaths.bookWatchFile(book.id, profile)
            } else {
                ApiPaths.bookFile(book.id)
            }
            val part = File(incoming, "${book.id}.server")
            val etag = File(incoming, "${book.id}.server.etag")
            client.download(path, part, etag) { done, total ->
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
            library.add(header, part)
        }
    }

    /**
     * Runs [block] with a client on Wi-Fi when the watch can get it: through the phone's Bluetooth link a book would
     * take hours. Without Wi-Fi in reach it uses whatever network there is (LTE, Bluetooth). Each download asks for
     * Wi-Fi on its own, so one finishing doesn't take the network away from another.
     */
    private suspend fun <T> onFastNetwork(block: suspend (ServerClient) -> T): T {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        var callback: ConnectivityManager.NetworkCallback? = null
        try {
            val network = withTimeoutOrNull(WIFI_WAIT_MS) {
                suspendCancellableCoroutine<Network> { cont ->
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            if (cont.isActive) cont.resume(network)
                        }
                    }
                    callback = cb
                    val request = NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    connectivity.requestNetwork(request, cb)
                }
            }
            return block(network?.let(client::over) ?: client)
        } finally {
            // Keeps Wi-Fi up while the download runs; released when it ends.
            callback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        }
    }

    private companion object {
        const val TAG = "WatchServer"
        const val WIFI_WAIT_MS = 15_000L
        const val RESERVE_BYTES = 200L * 1024 * 1024
        const val COVER_SIZE = 256
    }
}
