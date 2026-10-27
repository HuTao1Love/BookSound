package com.zyagodin.booksound.server.http

import com.zyagodin.booksound.api.ApiPaths
import com.zyagodin.booksound.api.ErrorCodes
import com.zyagodin.booksound.api.MetadataSearchRequest
import com.zyagodin.booksound.core.model.EmbeddedPicture
import com.zyagodin.booksound.server.ServerContext
import com.zyagodin.booksound.server.library.LibraryException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** Book details from audiobook sites, for any book: the server's, an upload's, or one on a phone. */
fun Route.metadataRoutes(ctx: ServerContext) {
    val metadata = ctx.metadata ?: return

    authenticate(AUTH) {
        post(ApiPaths.METADATA_SEARCH) {
            call.user
            val body = call.receive<MetadataSearchRequest>()
            if (body.title.isBlank() && body.query.isNullOrBlank()) throw LibraryException(ErrorCodes.BAD_REQUEST, "Nothing to search for")
            call.respond(metadata.search(body))
        }

        // A cover one of the searches found, fetched here: browsers can't read other sites' images.
        get(ApiPaths.METADATA_COVER) {
            call.user
            val url = call.request.queryParameters["url"] ?: throw notFound()
            val bytes = runCatching { metadata.cover(url) }.getOrNull() ?: throw notFound()
            val type = EmbeddedPicture.sniffMimeType(bytes) ?: throw LibraryException(ErrorCodes.UNSUPPORTED, "Not an image")
            call.response.header(HttpHeaders.CacheControl, "private, max-age=3600")
            call.respondBytes(bytes, ContentType.parse(type))
        }
    }
}
