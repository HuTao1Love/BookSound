package com.zyagodin.booksound.shared.server

import com.zyagodin.booksound.api.API_VERSION
import com.zyagodin.booksound.api.ApiPaths
import com.zyagodin.booksound.api.ChangeSetDto
import com.zyagodin.booksound.api.PullResponse
import com.zyagodin.booksound.api.PushRequest
import com.zyagodin.booksound.api.PushResponse
import com.zyagodin.booksound.api.chapterRecord
import com.zyagodin.booksound.api.toDto
import com.zyagodin.booksound.api.toModel
import com.zyagodin.booksound.api.toRecord
import com.zyagodin.booksound.core.model.BookId
import com.zyagodin.booksound.core.model.DeviceId
import com.zyagodin.booksound.core.sync.BackendStatus
import com.zyagodin.booksound.core.sync.ChangeSet
import com.zyagodin.booksound.core.sync.FileSink
import com.zyagodin.booksound.core.sync.PullResult
import com.zyagodin.booksound.core.sync.PushResult
import com.zyagodin.booksound.core.sync.SyncBackend
import com.zyagodin.booksound.core.sync.SyncCursor
import java.io.IOException
import java.io.InputStream

/**
 * [SyncBackend] backed by the BookSound server. Positions and preferences are the user's own on the server;
 * books are the shared library. Metadata of a book is pushed only when it was edited on this device.
 * Files are moved by the callers with [ServerClient.download] and [ServerClient.upload].
 */
class ServerSyncBackend(private val client: ServerClient) : SyncBackend {

    override suspend fun status(): BackendStatus {
        if (client.account == null) return BackendStatus.NotConfigured
        return try {
            val status = client.status()
            if (status.apiVersion != API_VERSION) BackendStatus.Incompatible(status.serverVersion, status.apiVersion, API_VERSION)
            else BackendStatus.Available(status.serverVersion)
        } catch (e: SignedOutException) {
            BackendStatus.Unauthorized(e.message ?: "Signed out")
        } catch (e: IOException) {
            BackendStatus.Unreachable
        }
    }

    override suspend fun pull(since: SyncCursor?): PullResult {
        val response = client.get("${ApiPaths.SYNC_PULL}?since=${since?.value?.toLongOrNull() ?: 0}&limit=500", PullResponse.serializer())
        val c = response.changes
        return PullResult(
            changes = ChangeSet(
                books = c.books.map { it.toRecord() },
                chapters = c.books.map { it.chapterRecord() },
                playback = c.playback.map { it.toModel() },
                preferences = c.preferences?.toRecord(),
            ),
            cursor = SyncCursor(response.cursor),
            hasMore = response.hasMore,
        )
    }

    override suspend fun push(changes: ChangeSet, device: DeviceId): PushResult {
        val chapters = changes.chapters.associate { it.bookId to it.chapters }
        val request = PushRequest(
            ChangeSetDto(
                books = changes.books.map { it.toDto(chapters[it.id].orEmpty()) },
                playback = changes.playback.map { it.toDto() },
                preferences = changes.preferences?.toDto(),
            ),
        )
        val response = client.post(ApiPaths.SYNC_PUSH, request, PushRequest.serializer(), PushResponse.serializer())
        return PushResult(
            accepted = response.accepted.mapKeys { BookId(it.key.removePrefix("playback:")) },
            conflicts = response.conflicts.map(::BookId),
        )
    }

    override suspend fun downloadFile(bookId: BookId, revision: Int, sink: FileSink, onProgress: (Long, Long) -> Unit) =
        throw UnsupportedOperationException("Use ServerClient.download")

    override suspend fun uploadFile(bookId: BookId, revision: Int, sizeBytes: Long, open: () -> InputStream, onProgress: (Long, Long) -> Unit) =
        throw UnsupportedOperationException("Use ServerClient.upload")

    override suspend fun downloadCover(bookId: BookId): ByteArray? = null

    override suspend fun uploadCover(bookId: BookId, bytes: ByteArray, mimeType: String) = Unit
}
