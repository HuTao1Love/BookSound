package com.zyagodin.booksound.server.http

import com.zyagodin.booksound.api.ApiPaths
import com.zyagodin.booksound.api.ErrorCodes
import com.zyagodin.booksound.api.LibraryEntryDto
import com.zyagodin.booksound.api.MetadataPatch
import com.zyagodin.booksound.api.PreferencesDto
import com.zyagodin.booksound.api.PushRequest
import com.zyagodin.booksound.core.naming.FileNameSanitizer
import com.zyagodin.booksound.server.ServerContext
import com.zyagodin.booksound.server.auth.AuthException
import com.zyagodin.booksound.server.library.BookRow
import com.zyagodin.booksound.server.library.Covers
import com.zyagodin.booksound.server.library.LibraryException
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.io.File

fun Route.libraryRoutes(ctx: ServerContext) {
    val library = ctx.library
    val repo = library.repo

    authenticate(AUTH) {
        get(ApiPaths.BOOKS) {
            val p = call.user
            val entries = io {
                val chapters = repo.allChapters()
                val positions = repo.playbackOf(p.userId)
                repo.books().map { LibraryEntryDto(it.toDto(chapters[it.id].orEmpty()), positions[it.id]) }
            }
            call.respond(entries)
        }

        get("${ApiPaths.BOOKS}/{id}") {
            val p = call.user
            val id = call.parameters["id"]!!
            val entry = io { repo.dto(id)?.let { LibraryEntryDto(it, repo.playback(p.userId, id)) } } ?: throw notFound()
            call.respond(entry)
        }

        patch("${ApiPaths.BOOKS}/{id}") {
            val p = call.user
            val body = call.receive<MetadataPatch>()
            val id = call.parameters["id"]!!
            val book = io { repo.book(id) } ?: throw notFound()
            if (book.revision != body.baseRevision) throw LibraryException(ErrorCodes.CONFLICT, "The book was changed meanwhile")
            call.respond(io { library.updateMetadata(id, body.metadata, null, p.deviceId) })
        }

        delete("${ApiPaths.BOOKS}/{id}") {
            val p = call.requireAdmin()
            io { library.delete(call.parameters["id"]!!, p.deviceId) }
            call.respond(HttpStatusCode.NoContent)
        }

        get("${ApiPaths.BOOKS}/{id}/file") {
            val book = io { repo.book(call.parameters["id"]!!) }?.takeIf { !it.deleted } ?: throw notFound()
            val file = library.fileOf(book).takeIf { it.isFile } ?: throw notFound()
            call.respondBookFile(book, file, download = call.request.queryParameters["download"] == "1")
        }

        get("${ApiPaths.BOOKS}/{id}/cover") {
            val id = call.parameters["id"]!!
            val size = call.request.queryParameters["size"]?.toIntOrNull()?.coerceIn(32, 1400)
            val file = io { if (size != null) library.covers.sized(id, size) else library.covers.file(id) } ?: throw notFound()
            call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
            call.respond(LocalFileContent(file, if (file.extension == "png") ContentType.Image.PNG else ContentType.Image.JPEG))
        }

        // A new cover: the image bytes as the body. It is written into the book's file.
        put("${ApiPaths.BOOKS}/{id}/cover") {
            val p = call.user
            val id = call.parameters["id"]!!
            val bytes = call.receiveLimited(MAX_COVER_BYTES)
            val cover = Covers.normalize(bytes) ?: throw LibraryException(ErrorCodes.UNSUPPORTED, "Not an image")
            val book = io { repo.book(id) } ?: throw notFound()
            call.respond(io { library.updateMetadata(id, book.metadata, cover, p.deviceId) })
        }

        get(ApiPaths.SYNC_PULL) {
            val p = call.user
            val since = call.request.queryParameters["since"]?.toLongOrNull() ?: 0
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 500
            call.respond(io { ctx.sync.pull(p.userId, since, limit) })
        }

        post(ApiPaths.SYNC_PUSH) {
            val p = call.user
            val body = call.receive<PushRequest>()
            call.respond(io { ctx.sync.push(p.userId, p.deviceId, body.changes) })
        }

        get(ApiPaths.PREFERENCES) {
            val p = call.user
            call.respond(io { ctx.sync.preferences(p.userId) })
        }

        put(ApiPaths.PREFERENCES) {
            val p = call.user
            val body = call.receive<PreferencesDto>()
            call.respond(io { ctx.sync.savePreferences(p.userId, body, p.deviceId) })
        }

        get(ApiPaths.WATCH_PROFILES) {
            call.respond(io { ctx.settings.watchProfiles() })
        }
    }
}

/** A book file with byte ranges (seeking, resumed downloads); the tag changes whenever the file does. */
suspend fun ApplicationCall.respondBookFile(book: BookRow, file: File, download: Boolean, variant: String = "") {
    response.header(HttpHeaders.ETag, "\"${book.id}-${book.fileRevision}$variant-${file.length()}\"")
    response.header(HttpHeaders.CacheControl, "private, no-cache")
    if (download) {
        val name = (FileNameSanitizer.sanitize(book.metadata.title, 180) ?: "book") + ".m4b"
        response.header(HttpHeaders.ContentDisposition, ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, name).toString())
    }
    respond(LocalFileContent(file, ContentType("audio", "mp4")))
}

fun notFound() = AuthException(ErrorCodes.NOT_FOUND)

private const val MAX_COVER_BYTES = 15L * 1024 * 1024

