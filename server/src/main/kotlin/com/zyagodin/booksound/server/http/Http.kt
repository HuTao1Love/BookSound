package com.zyagodin.booksound.server.http

import com.zyagodin.booksound.api.ApiJson
import com.zyagodin.booksound.api.ApiPaths
import com.zyagodin.booksound.api.ErrorCodes
import com.zyagodin.booksound.api.ErrorDto
import com.zyagodin.booksound.server.auth.AuthException
import com.zyagodin.booksound.server.auth.AuthService
import com.zyagodin.booksound.server.auth.Principal
import com.zyagodin.booksound.server.library.LibraryException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.NotFoundException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.deflate
import io.ktor.server.plugins.compression.excludeContentType
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.conditionalheaders.ConditionalHeaders
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.sse.SSE
import io.ktor.http.ContentType
import io.ktor.util.AttributeKey
import org.slf4j.event.Level
import java.net.InetAddress
import kotlin.time.Duration.Companion.minutes

const val AUTH = "booksound"
const val SESSION_COOKIE = "bs_session"
val LOGIN_LIMIT = RateLimitName("login")

/** Where a request really came from: the router passes the client's address and scheme in X-Forwarded-*. */
data class ClientInfo(val address: String, val https: Boolean)

private val clientKey = AttributeKey<ClientInfo>("client")

val ApplicationCall.client: ClientInfo get() = attributes[clientKey]

val ApplicationCall.user: Principal get() = principal<Principal>() ?: throw AuthException(ErrorCodes.UNAUTHORIZED)

fun ApplicationCall.requireAdmin(): Principal = user.also { if (!it.isAdmin) throw AuthException(ErrorCodes.FORBIDDEN) }

fun Application.installHttp(auth: AuthService, trustedProxies: Set<InetAddress>) {
    install(createApplicationPlugin("ClientInfo") {
        onCall { call ->
            val peer = call.request.local.remoteAddress
            val trusted = trustedProxies.isNotEmpty() && runCatching { InetAddress.getByName(peer) in trustedProxies }.getOrDefault(false)
            val info = if (trusted) {
                ClientInfo(
                    address = call.request.headers["X-Forwarded-For"]?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: peer,
                    https = call.request.headers["X-Forwarded-Proto"]?.trim()?.lowercase() == "https",
                )
            } else {
                ClientInfo(peer, call.request.local.scheme == "https")
            }
            call.attributes.put(clientKey, info)
        }
    })

    install(createApplicationPlugin("SecurityHeaders") {
        onCall { call ->
            val headers = call.response.headers
            headers.append("X-Content-Type-Options", "nosniff")
            headers.append("Referrer-Policy", "same-origin")
            headers.append("X-Frame-Options", "DENY")
            headers.append(
                "Content-Security-Policy",
                "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; style-src 'self' 'unsafe-inline'; " +
                    "connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'",
            )
            if (call.client.https) headers.append("Strict-Transport-Security", "max-age=31536000")
        }
    })

    install(CallLogging) {
        level = Level.INFO
        filter { it.request.path().startsWith("/api/") && !it.request.path().startsWith(ApiPaths.EVENTS) }
        format { call -> "${call.response.status()?.value} ${call.request.httpMethod.value} ${call.request.path()} from ${call.client.address}" }
    }

    install(ContentNegotiation) { json(ApiJson) }
    install(Compression) {
        gzip { excludeContentType(ContentType.Audio.Any, ContentType.Image.Any, ContentType.Application.OctetStream, ContentType.Text.EventStream) }
        deflate { excludeContentType(ContentType.Audio.Any, ContentType.Image.Any, ContentType.Application.OctetStream, ContentType.Text.EventStream) }
    }
    install(ConditionalHeaders)
    install(PartialContent)
    install(SSE)

    install(RateLimit) {
        register(LOGIN_LIMIT) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { call -> call.client.address }
        }
    }

    install(Authentication) {
        bearer(AUTH) {
            authHeader { call ->
                call.request.headers[HttpHeaders.Authorization]?.let { header ->
                    return@authHeader io.ktor.http.auth.parseAuthorizationHeader(header)
                }
                call.request.cookies[SESSION_COOKIE]?.let { HttpAuthHeader.Single("Bearer", it) }
            }
            authenticate { credential ->
                val viaCookie = request.headers[HttpHeaders.Authorization] == null
                // A form or script on another site can send the cookie but can't add this header.
                if (viaCookie && request.httpMethod != HttpMethod.Get && request.httpMethod != HttpMethod.Head &&
                    request.headers[ApiPaths.CSRF_HEADER] == null
                ) {
                    return@authenticate null
                }
                auth.authenticate(credential.token, viaCookie)
            }
        }
    }

    install(StatusPages) {
        exception<AuthException> { call, e ->
            val status = when (e.code) {
                ErrorCodes.UNAUTHORIZED, ErrorCodes.INVALID_CREDENTIALS -> HttpStatusCode.Unauthorized
                ErrorCodes.FORBIDDEN -> HttpStatusCode.Forbidden
                ErrorCodes.NOT_FOUND -> HttpStatusCode.NotFound
                ErrorCodes.CONFLICT, ErrorCodes.SETUP_DONE -> HttpStatusCode.Conflict
                ErrorCodes.TOO_MANY_ATTEMPTS -> HttpStatusCode.TooManyRequests
                ErrorCodes.PENDING -> HttpStatusCode.Accepted
                ErrorCodes.EXPIRED -> HttpStatusCode.Gone
                else -> HttpStatusCode.BadRequest
            }
            call.respond(status, ErrorDto(e.code, e.message?.takeIf { it != e.code }))
        }
        exception<LibraryException> { call, e ->
            val status = when (e.code) {
                ErrorCodes.NOT_FOUND -> HttpStatusCode.NotFound
                ErrorCodes.CONFLICT -> HttpStatusCode.Conflict
                ErrorCodes.INSUFFICIENT_STORAGE -> HttpStatusCode.InsufficientStorage
                else -> HttpStatusCode.UnprocessableEntity
            }
            call.respond(status, ErrorDto(e.code, e.message))
        }
        exception<BadRequestException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorDto(ErrorCodes.BAD_REQUEST, e.message)) }
        exception<NotFoundException> { call, _ -> call.respond(HttpStatusCode.NotFound, ErrorDto(ErrorCodes.NOT_FOUND)) }
        exception<IllegalArgumentException> { call, e -> call.respond(HttpStatusCode.BadRequest, ErrorDto(ErrorCodes.BAD_REQUEST, e.message)) }
        exception<Throwable> { call, e ->
            call.application.environment.log.error("Request failed: ${call.request.httpMethod.value} ${call.request.path()}", e)
            call.respond(HttpStatusCode.InternalServerError, ErrorDto("internal", "Internal error"))
        }
        status(HttpStatusCode.Unauthorized) { call, _ ->
            if (call.request.path().startsWith("/api/")) call.respond(HttpStatusCode.Unauthorized, ErrorDto(ErrorCodes.UNAUTHORIZED))
        }
        status(HttpStatusCode.TooManyRequests) { call, _ ->
            call.respond(HttpStatusCode.TooManyRequests, ErrorDto(ErrorCodes.TOO_MANY_ATTEMPTS))
        }
    }
}
