package com.zyagodin.booksound.shared.server

import com.zyagodin.booksound.api.ApiJson
import com.zyagodin.booksound.api.ApiPaths
import com.zyagodin.booksound.api.DeviceKind
import com.zyagodin.booksound.api.ErrorDto
import com.zyagodin.booksound.api.LoginRequest
import com.zyagodin.booksound.api.RefreshRequest
import com.zyagodin.booksound.api.StatusDto
import com.zyagodin.booksound.api.TokenResponse
import com.zyagodin.booksound.api.UploadCreateRequest
import com.zyagodin.booksound.api.UploadDto
import com.zyagodin.booksound.api.UploadKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** An error answer of the server; [code] is one of `ErrorCodes`. */
class ServerException(val status: Int, val code: String, message: String?) : IOException(message ?: code)

/** The server no longer accepts this device's login (signed out elsewhere, password changed, device revoked). */
class SignedOutException : IOException("Signed out by the server")

/**
 * HTTP client of the BookSound server, shared by the phone and the watch. Access tokens are refreshed when they
 * expire; a refused refresh signs the device out. Downloads resume with Range requests, uploads go in chunks.
 */
class ServerClient(http: OkHttpClient, private val store: ServerAccountStore) {
    // Books are big: no limit on a whole call, only on silence.
    private val http = http.newBuilder()
        .retryOnConnectionFailure(true)
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val json = "application/json".toMediaType()

    val account get() = store.current

    /** The same account over one network (e.g. a watch's Wi-Fi), without binding the whole app to it. */
    fun over(network: android.net.Network): ServerClient = ServerClient(
        http.newBuilder()
            .socketFactory(network.socketFactory)
            .dns { host -> network.getAllByName(host).toList() }
            .build(),
        store,
    )

    /** The server's status at [url], without signing in; used to check an address before logging in. */
    suspend fun status(url: String = account?.url ?: throw IOException("Not signed in")): StatusDto =
        decode(StatusDto.serializer(), execute(Request.Builder().url(endpoint(url, ApiPaths.STATUS)).build()))

    suspend fun login(url: String, user: String, password: String, deviceName: String, kind: DeviceKind, syncId: String?): ServerAccount {
        val base = normalizeUrl(url)
        val body = ApiJson.encodeToString(LoginRequest.serializer(), LoginRequest(user.trim(), password, deviceName, kind, syncId))
        val tokens = decode(TokenResponse.serializer(), execute(Request.Builder().url(endpoint(base, ApiPaths.LOGIN)).post(body.toRequestBody(json)).build()))
        return accountOf(base, tokens).also(store::save)
    }

    /** Takes over a login handed over by the phone (a watch's own device and tokens). */
    fun adopt(url: String, tokens: TokenResponse) = store.save(accountOf(normalizeUrl(url), tokens))

    suspend fun logout() {
        runCatching { send("POST", ApiPaths.LOGOUT, "{}") }
        store.clear()
    }

    suspend fun <T> get(path: String, serializer: KSerializer<T>): T = decode(serializer, authorized(path) { it.get() })

    suspend fun <B, T> post(path: String, body: B, bodySerializer: KSerializer<B>, serializer: KSerializer<T>): T =
        decode(serializer, send("POST", path, ApiJson.encodeToString(bodySerializer, body)))

    suspend fun <B, T> put(path: String, body: B, bodySerializer: KSerializer<B>, serializer: KSerializer<T>): T =
        decode(serializer, send("PUT", path, ApiJson.encodeToString(bodySerializer, body)))

    private suspend fun send(method: String, path: String, body: String): Response = authorized(path) { it.method(method, body.toRequestBody(json)) }

    // Reading or closing a response touches the socket: everything here runs off the main thread.
    private suspend fun authorized(path: String, build: (Request.Builder) -> Request.Builder): Response = withContext(Dispatchers.IO) {
        val first = execute(build(request(path, accessToken())).build(), checkStatus = false)
        if (first.code != 401) return@withContext checked(first)
        first.close()
        val token = accessToken(forceRefresh = true)
        execute(build(request(path, token)).build())
    }

    /**
     * Downloads [path] into [target], continuing a partial file. [onProgress] gets bytes so far and the total.
     * When the server's file changed since the partial download started (another revision), it starts over.
     */
    suspend fun download(path: String, target: File, etagFile: File, onProgress: (Long, Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        val have = if (target.isFile) target.length() else 0L
        val etag = etagFile.takeIf { it.isFile }?.readText()
        val response = authorized(path) { b -> if (have > 0 && etag != null) b.header("Range", "bytes=$have-") else b }
        response.use { r ->
            val resumed = r.code == 206
            // The file changed since the partial download (the tag names the file's revision): start over.
            if (resumed && r.header("ETag") != etag) {
                r.close()
                target.delete()
                etagFile.delete()
                return@withContext download(path, target, etagFile, onProgress)
            }
            val total = (if (resumed) r.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() else null)
                ?: (r.body.contentLength().takeIf { it >= 0 }?.let { if (resumed) it + have else it }) ?: -1L
            r.header("ETag")?.let { etagFile.writeText(it) }
            FileOutputStream(target, resumed).use { out ->
                var done = if (resumed) have else 0L
                val buffer = ByteArray(64 * 1024)
                r.body.byteStream().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
                out.fd.sync()
            }
            if (total >= 0 && target.length() != total) throw IOException("Download ended early: ${target.length()} of $total bytes")
        }
    }

    /**
     * Sends a file in chunks; [open] opens it at an offset (resumes skip what the server has). Returns the
     * finished upload, which the caller completes or turns into an import.
     */
    suspend fun upload(
        fileName: String,
        size: Long,
        kind: UploadKind,
        sha256: String?,
        open: (offset: Long) -> InputStream,
        onProgress: (Long, Long) -> Unit,
    ): UploadDto = withContext(Dispatchers.IO) {
        var upload = post(ApiPaths.UPLOADS, UploadCreateRequest(fileName, size, kind, sha256), UploadCreateRequest.serializer(), UploadDto.serializer())
        var failures = 0
        try {
            while (upload.receivedBytes < size) {
                currentCoroutineContext().ensureActive()
                val start = upload.receivedBytes
                val end = minOf(start + CHUNK_BYTES, size) - 1
                try {
                    val body = object : RequestBody() {
                        override fun contentType() = "application/octet-stream".toMediaType()
                        override fun contentLength() = end - start + 1
                        override fun writeTo(sink: BufferedSink) {
                            open(start).use { input ->
                                val buffer = ByteArray(64 * 1024)
                                var left = end - start + 1
                                while (left > 0) {
                                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                                    if (n < 0) throw IOException("File ended early")
                                    sink.write(buffer, 0, n)
                                    left -= n
                                    // Bytes handed to the socket, every MiB, so the bar moves within a chunk too.
                                    if (left % PROGRESS_STEP < n) onProgress(end + 1 - left, size)
                                }
                            }
                        }
                    }
                    upload = decode(UploadDto.serializer(), authorized(ApiPaths.upload(upload.id)) {
                        it.put(body).header("Content-Range", "bytes $start-$end/$size")
                    })
                    failures = 0
                    onProgress(upload.receivedBytes, size)
                } catch (e: ServerException) {
                    if (e.status != 409 && e.status < 500 || ++failures > 5) throw e
                    upload = get(ApiPaths.upload(upload.id), UploadDto.serializer())
                } catch (e: IOException) {
                    if (e is SignedOutException || ++failures > 5) throw e
                    kotlinx.coroutines.delay(2_000L * failures)
                    upload = get(ApiPaths.upload(upload.id), UploadDto.serializer())
                }
            }
        } catch (e: Throwable) {
            // Cancelled or failed: the next attempt starts a new upload, so the server needn't keep (and show) this one.
            val id = upload.id
            withContext(NonCancellable) { runCatching { withTimeout(10_000) { authorized(ApiPaths.upload(id)) { it.delete() }.close() } } }
            throw e
        }
        upload
    }

    // ------------------------------------------------------------------------------------- internals

    private fun request(path: String, token: String): Request.Builder {
        val base = account?.url ?: throw SignedOutException()
        return Request.Builder().url(endpoint(base, path)).header("Authorization", "Bearer $token")
    }

    private suspend fun accessToken(forceRefresh: Boolean = false): String {
        val current = account ?: throw SignedOutException()
        if (!forceRefresh && current.accessToken != null && current.accessExpiresAt > System.currentTimeMillis() + 60_000) return current.accessToken
        return store.refreshLock.withLock {
            val latest = account ?: throw SignedOutException()
            // Another call refreshed meanwhile.
            if (latest.accessToken != current.accessToken && latest.accessToken != null && latest.accessExpiresAt > System.currentTimeMillis()) {
                return@withLock latest.accessToken
            }
            val body = ApiJson.encodeToString(RefreshRequest.serializer(), RefreshRequest(latest.refreshToken))
            val response = execute(Request.Builder().url(endpoint(latest.url, ApiPaths.REFRESH)).post(body.toRequestBody(json)).build(), checkStatus = false)
            if (response.code == 401) {
                response.close()
                store.clear()
                throw SignedOutException()
            }
            val tokens = decode(TokenResponse.serializer(), checked(response))
            accountOf(latest.url, tokens).also(store::save).accessToken!!
        }
    }

    private fun accountOf(url: String, t: TokenResponse) = ServerAccount(url, t.user.name, t.user.isAdmin, t.deviceId, t.refreshToken, t.accessToken, t.accessExpiresAt)

    private suspend fun execute(request: Request, checkStatus: Boolean = true): Response = withContext(Dispatchers.IO) {
        val call: Call = http.newCall(request)
        val response = try {
            kotlinx.coroutines.runInterruptible { call.execute() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            call.cancel()
            throw e
        }
        if (checkStatus) checked(response) else response
    }

    private fun checked(response: Response): Response {
        if (response.isSuccessful) return response
        val text = response.use { runCatching { it.body.string() }.getOrNull() }
        val error = text?.let { runCatching { ApiJson.decodeFromString(ErrorDto.serializer(), it) }.getOrNull() }
        throw ServerException(response.code, error?.code ?: response.code.toString(), error?.message)
    }

    private suspend fun <T> decode(serializer: KSerializer<T>, response: Response): T = withContext(Dispatchers.IO) {
        response.use { ApiJson.decodeFromString(serializer, it.body.string()) }
    }

    companion object {
        private const val CHUNK_BYTES = 8L * 1024 * 1024
        private const val PROGRESS_STEP = 1L shl 20

        /** "abook.example.link" → "https://abook.example.link"; trailing slashes go. */
        fun normalizeUrl(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
            withScheme.toHttpUrlOrNull() ?: throw IOException("Not a server address: $input")
            return withScheme
        }

        private fun endpoint(base: String, path: String) = base.trimEnd('/') + path
    }
}
