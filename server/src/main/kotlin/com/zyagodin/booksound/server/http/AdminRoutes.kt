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
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.path
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
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
    val index = Application::class.java.getResource("/web/index.html")?.readText()
    routing {
        staticResources("/", "web") {
            default("index.html")
            cacheControl { file ->
                if (file.path.contains("/assets/")) listOf(io.ktor.http.CacheControl.MaxAge(31536000, visibility = io.ktor.http.CacheControl.Visibility.Public))
                else listOf(io.ktor.http.CacheControl.NoCache(null))
            }
        }
        get("{path...}") {
            if (call.request.path().startsWith("/api/") || index == null) {
                call.respond(HttpStatusCode.NotFound)
            } else {
                call.respondText(index, ContentType.Text.Html)
            }
        }
    }
}
