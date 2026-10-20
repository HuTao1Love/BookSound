package com.zyagodin.booksound.server.http

import com.zyagodin.booksound.api.AddTorrentRequest
import com.zyagodin.booksound.api.ApiPaths
import com.zyagodin.booksound.api.ErrorCodes
import com.zyagodin.booksound.api.ErrorDto
import com.zyagodin.booksound.api.ImportFromUploadsRequest
import com.zyagodin.booksound.api.TorrentReviewRequest
import com.zyagodin.booksound.api.UploadCreateRequest
import com.zyagodin.booksound.api.WatchFileStatusDto
import com.zyagodin.booksound.server.ServerContext
import com.zyagodin.booksound.server.importer.ImportFailure
import com.zyagodin.booksound.server.importer.UploadOffsetException
import com.zyagodin.booksound.server.library.LibraryException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.utils.io.jvm.javaio.toInputStream

private val CONTENT_RANGE = Regex("""bytes (\d+)-(\d+)/(\d+)""")

fun Route.importRoutes(ctx: ServerContext) {
    authenticate(AUTH) {
        // ------------------------------------------------------------------------------ uploads
        post(ApiPaths.UPLOADS) {
            val p = call.user
            val body = call.receive<UploadCreateRequest>()
            call.respond(HttpStatusCode.Created, io { ctx.uploads.create(p, body) })
        }
        get("${ApiPaths.UPLOADS}/{id}") {
            val p = call.user
            call.respond(io { ctx.uploads.status(p, call.parameters["id"]!!) })
        }
        put("${ApiPaths.UPLOADS}/{id}") {
            val p = call.user
            val range = call.request.header(HttpHeaders.ContentRange)?.let { CONTENT_RANGE.matchEntire(it.trim()) }
                ?: throw LibraryException(ErrorCodes.BAD_REQUEST, "Content-Range: bytes start-end/size is required")
            val (start, end, total) = range.destructured
            val stream = call.receiveChannel().toInputStream()
            try {
                call.respond(io { ctx.uploads.append(p, call.parameters["id"]!!, start.toLong(), end.toLong(), total.toLong(), stream) })
            } catch (e: UploadOffsetException) {
                // The client resends from where the server's copy ends.
                call.response.header("Upload-Offset", e.received.toString())
                call.respond(HttpStatusCode.Conflict, ErrorDto(ErrorCodes.CONFLICT, e.message))
            }
        }
        delete("${ApiPaths.UPLOADS}/{id}") {
            val p = call.user
            io { ctx.uploads.delete(p, call.parameters["id"]!!) }
            call.respond(HttpStatusCode.NoContent)
        }
        post("${ApiPaths.UPLOADS}/{id}/complete") {
            val p = call.user
            call.respond(io { ctx.uploads.completeBook(p, call.parameters["id"]!!) })
        }

        // ------------------------------------------------------------------------------ imports
        get(ApiPaths.IMPORTS) {
            call.user
            call.respond(ctx.imports.list().sortedByDescending { it.createdAt }.map { it.toDto() })
        }
        post(ApiPaths.IMPORTS) {
            val p = call.user
            val body = call.receive<ImportFromUploadsRequest>()
            call.respond(HttpStatusCode.Created, io { ctx.uploads.importSources(p, body) }.toDto())
        }
        post("${ApiPaths.IMPORTS}/{id}/cancel") {
            call.user
            ctx.imports.cancel(call.parameters["id"]!!)
            call.respond(HttpStatusCode.NoContent)
        }
        delete("${ApiPaths.IMPORTS}/{id}") {
            call.user
            io { ctx.imports.remove(call.parameters["id"]!!) }
            call.respond(HttpStatusCode.NoContent)
        }

        // ------------------------------------------------------------------------------ watch copies
        get("${ApiPaths.BOOKS}/{id}/watch-file") {
            val profileId = call.request.queryParameters["profile"] ?: throw LibraryException(ErrorCodes.BAD_REQUEST, "profile is required")
            val profile = io { ctx.settings.watchProfile(profileId) } ?: throw notFound()
            val book = io { ctx.library.repo.book(call.parameters["id"]!!) }?.takeIf { !it.deleted } ?: throw notFound()
            val ready = io { ctx.watch.ready(book, profile) }
            if (ready != null) {
                if (call.request.queryParameters["status"] == "1") {
                    call.respond(WatchFileStatusDto(ready = true, sizeBytes = ready.length()))
                } else {
                    call.respondBookFile(book, ready, download = call.request.queryParameters["download"] == "1", variant = "-${profile.id}")
                }
                return@get
            }
            val job = try {
                io { ctx.watch.request(book, profile) }
            } catch (e: ImportFailure) {
                throw LibraryException(ErrorCodes.UNSUPPORTED, e.message ?: "Unavailable")
            }
            call.respond(HttpStatusCode.Accepted, WatchFileStatusDto(ready = false, job = job?.toDto()))
        }

        // ------------------------------------------------------------------------------ torrents
        val torrents = ctx.torrents
        get(ApiPaths.TORRENTS) {
            call.user
            call.respond(torrents?.let { t -> t.list().sortedByDescending { it.addedAt }.map(t::toDto) }.orEmpty())
        }
        post(ApiPaths.TORRENTS) {
            val p = call.user
            val t = torrents ?: throw LibraryException(ErrorCodes.UNSUPPORTED, "Torrents are not available on this server")
            val body = call.receive<AddTorrentRequest>()
            call.respond(HttpStatusCode.Created, t.toDto(io { t.addLink(body.link, body.autoImport, p.userId) }))
        }
        // A .torrent file as the body.
        post("${ApiPaths.TORRENTS}/file") {
            val p = call.user
            val t = torrents ?: throw LibraryException(ErrorCodes.UNSUPPORTED, "Torrents are not available on this server")
            val bytes = call.receiveLimited(10L * 1024 * 1024)
            val auto = call.request.queryParameters["autoImport"] == "true"
            call.respond(HttpStatusCode.Created, t.toDto(io { t.addFile(bytes, auto, p.userId) }))
        }
        put("${ApiPaths.TORRENTS}/{id}/books/{book}") {
            call.user
            val t = torrents ?: throw notFound()
            val body = call.receive<TorrentReviewRequest>()
            io { t.review(call.parameters["id"]!!, call.parameters["book"]!!, body, null) }
            call.respond(t.toDto(t.get(call.parameters["id"]!!) ?: throw notFound()))
        }
        put("${ApiPaths.TORRENTS}/{id}/books/{book}/cover") {
            call.user
            val t = torrents ?: throw notFound()
            val bytes = call.receiveLimited(15L * 1024 * 1024)
            com.zyagodin.booksound.server.library.Covers.normalize(bytes) ?: throw LibraryException(ErrorCodes.UNSUPPORTED, "Not an image")
            io { t.setCover(call.parameters["id"]!!, call.parameters["book"]!!, bytes) }
            call.respond(HttpStatusCode.NoContent)
        }
        post("${ApiPaths.TORRENTS}/{id}/retry") {
            call.user
            val t = torrents ?: throw notFound()
            io { t.retry(call.parameters["id"]!!) }
            call.respond(HttpStatusCode.NoContent)
        }
        delete("${ApiPaths.TORRENTS}/{id}") {
            call.user
            io { torrents?.remove(call.parameters["id"]!!) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
