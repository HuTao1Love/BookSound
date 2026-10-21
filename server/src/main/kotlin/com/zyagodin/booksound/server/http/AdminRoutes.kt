package com.zyagodin.booksound.server.http

import com.zyagodin.booksound.api.ApiJson
import com.zyagodin.booksound.api.ApiPaths
import com.zyagodin.booksound.api.EventDto
import com.zyagodin.booksound.api.ServerSettingsDto
import com.zyagodin.booksound.api.UserCreateRequest
import com.zyagodin.booksound.api.UserUpdateRequest
import com.zyagodin.booksound.api.WatchProfileDto
import com.zyagodin.booksound.server.ServerContext
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.header
import io.ktor.http.HttpHeaders
import io.ktor.http.defaultForFilePath
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlin.time.Duration.Companion.seconds

fun Route.adminRoutes(ctx: ServerContext) {
    authenticate(AUTH) {
        get(ApiPaths.ADMIN_USERS) {
            call.requireAdmin()
            call.respond(io { ctx.auth.users() })
        }
        post(ApiPaths.ADMIN_USERS) {
            call.requireAdmin()
            val body = call.receive<UserCreateRequest>()
            call.respond(HttpStatusCode.Created, io { ctx.auth.createUser(body.name, body.password, body.isAdmin) })
        }
        patch("${ApiPaths.ADMIN_USERS}/{id}") {
            call.requireAdmin()
            val body = call.receive<UserUpdateRequest>()
            io { ctx.auth.updateUser(call.parameters["id"]!!, body.password, body.isAdmin) }
            call.respond(HttpStatusCode.NoContent)
        }
        delete("${ApiPaths.ADMIN_USERS}/{id}") {
            call.requireAdmin()
            io { ctx.auth.deleteUser(call.parameters["id"]!!) }
            call.respond(HttpStatusCode.NoContent)
        }

        get(ApiPaths.ADMIN_SETTINGS) {
            call.requireAdmin()
            call.respond(ctx.settings.current)
        }
        put(ApiPaths.ADMIN_SETTINGS) {
            call.requireAdmin()
            val body = call.receive<ServerSettingsDto>()
            call.respond(io { ctx.settings.update(body) })
        }

        post(ApiPaths.ADMIN_RESCAN) {
            call.requireAdmin()
            call.respond(io { ctx.library.scan() })
        }

        post(ApiPaths.WATCH_PROFILES) {
            call.requireAdmin()
            val body = call.receive<WatchProfileDto>()
            call.respond(io { ctx.settings.saveWatchProfile(body) })
        }
        delete("${ApiPaths.WATCH_PROFILES}/{id}") {
            call.requireAdmin()
            val id = call.parameters["id"]!!
            io {
                ctx.settings.deleteWatchProfile(id)
                ctx.watch.dropProfile(id)
            }
            call.respond(HttpStatusCode.NoContent)
        }

        // Live progress for the web app. Events carry no secrets beyond what the other endpoints show any user.
        sse(ApiPaths.EVENTS) {
            heartbeat { period = 20.seconds }
            ctx.events.events.collect { event ->
                send(ServerSentEvent(data = ApiJson.encodeToString(EventDto.serializer(), event), event = event.type))
            }
        }
    }
}

/** The web app, built into the jar: files by path, and index.html for its client-side routes. */
fun Application.webApp() {
    val loader = Application::class.java.classLoader
    val index = loader.getResource("web/index.html")?.readBytes()
    routing {
        get("{path...}") {
            val path = call.parameters.getAll("path").orEmpty().joinToString("/")
            if (path.startsWith("api/") || index == null || path.split('/').any { it == ".." }) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val bytes = path.takeIf { it.isNotEmpty() && '.' in it.substringAfterLast('/') }?.let { loader.getResource("web/$it")?.readBytes() }
            if (bytes == null) {
                call.response.header(HttpHeaders.CacheControl, "no-cache")
                call.respondBytes(index, ContentType.Text.Html)
            } else {
                // Built assets have content hashes in their names; the rest (service worker, manifest) must be checked.
                call.response.header(HttpHeaders.CacheControl, if (path.startsWith("assets/")) "public, max-age=31536000, immutable" else "no-cache")
                call.respondBytes(bytes, ContentType.defaultForFilePath(path))
            }
        }
    }
}
