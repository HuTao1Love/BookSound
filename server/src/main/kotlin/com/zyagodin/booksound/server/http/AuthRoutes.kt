package com.zyagodin.booksound.server.http

import com.zyagodin.booksound.api.ApiPaths
import com.zyagodin.booksound.api.DeviceKind
import com.zyagodin.booksound.api.ErrorCodes
import com.zyagodin.booksound.api.LoginRequest
import com.zyagodin.booksound.api.PasswordChangeRequest
import com.zyagodin.booksound.api.RefreshRequest
import com.zyagodin.booksound.api.SessionDto
import com.zyagodin.booksound.api.SetupRequest
import com.zyagodin.booksound.api.StatusDto
import com.zyagodin.booksound.api.UserDto
import com.zyagodin.booksound.api.WatchDeviceRequest
import com.zyagodin.booksound.api.WebLoginRequest
import com.zyagodin.booksound.api.API_VERSION
import com.zyagodin.booksound.server.ServerContext
import com.zyagodin.booksound.server.auth.AuthException
import com.zyagodin.booksound.server.auth.LoginResult
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun Route.authRoutes(ctx: ServerContext) {
    val auth = ctx.auth

    get(ApiPaths.STATUS) {
        call.respond(StatusDto(ctx.config.version, API_VERSION, auth.setupRequired, ctx.features))
    }

    rateLimit(LOGIN_LIMIT) {
        post(ApiPaths.SETUP) {
            val body = call.receive<SetupRequest>()
            val user = io { auth.createFirstAdmin(body.setupToken, body.username, body.password) }
            call.respond(HttpStatusCode.Created, user)
        }

        post(ApiPaths.LOGIN) {
            val body = call.receive<LoginRequest>()
            val user = call.checkLogin(ctx, body.username, body.password)
            val tokens = io {
                val device = auth.createDevice(user.id, body.deviceName, body.deviceKind, body.deviceId)
                auth.issueTokens(device, user)
            }
            call.respond(tokens)
        }

        post(ApiPaths.WEB_LOGIN) {
            val body = call.receive<WebLoginRequest>()
            val user = call.checkLogin(ctx, body.username, body.password)
            val (device, token) = io {
                val agent = call.request.headers[HttpHeaders.UserAgent].orEmpty()
                val device = auth.createDevice(user.id, "Web · ${browserName(agent)}", DeviceKind.WEB, null)
                device to auth.issueSession(device)
            }
            call.response.cookies.append(
                Cookie(
                    name = SESSION_COOKIE,
                    value = token,
                    maxAge = (com.zyagodin.booksound.server.auth.AuthService.SESSION_TTL_MS / 1000).toInt(),
                    path = "/",
                    httpOnly = true,
                    secure = call.client.https,
                    extensions = mapOf("SameSite" to "Strict"),
                ),
            )
            call.respond(SessionDto(user, device))
        }

        post(ApiPaths.REFRESH) {
            val body = call.receive<RefreshRequest>()
            call.respond(io { auth.refresh(body.refreshToken) })
        }

    }


    authenticate(AUTH) {
        get(ApiPaths.ME) {
            val p = call.user
            call.respond(SessionDto(p.user, p.deviceId))
        }

        post(ApiPaths.LOGOUT) {
            val p = call.user
            io { auth.logout(p) }
            call.response.cookies.append(Cookie(SESSION_COOKIE, "", maxAge = 0, path = "/", httpOnly = true, secure = call.client.https))
            call.respond(HttpStatusCode.NoContent)
        }

        post(ApiPaths.PASSWORD) {
            val body = call.receive<PasswordChangeRequest>()
            val p = call.user
            io { auth.changePassword(p, body.currentPassword, body.newPassword) }
            call.respond(HttpStatusCode.NoContent)
        }


        get(ApiPaths.DEVICES) {
            val p = call.user
            call.respond(io { auth.devices(p) })
        }

        delete("${ApiPaths.DEVICES}/{id}") {
            val p = call.user
            io { auth.revokeDevice(p, call.parameters["id"]!!) }
            call.respond(HttpStatusCode.NoContent)
        }

        // A phone signs its watch in: the watch gets a device and tokens of its own, sent over the Data Layer.
        post(ApiPaths.WATCH_DEVICE) {
            val body = call.receive<WatchDeviceRequest>()
            val p = call.user
            if (p.viaCookie) throw AuthException(ErrorCodes.FORBIDDEN, "Only the phone app signs in watches")
            call.respond(io { auth.issueTokens(auth.createDevice(p.userId, body.deviceName, DeviceKind.WATCH, body.deviceId), p.user) })
        }
    }
}

private suspend fun ApplicationCall.checkLogin(ctx: ServerContext, name: String, password: String): UserDto =
    when (val result = io { ctx.auth.checkCredentials(name, password, client.address) }) {
        is LoginResult.Success -> result.user
        LoginResult.InvalidCredentials -> throw AuthException(ErrorCodes.INVALID_CREDENTIALS, "Wrong user name or password")
        is LoginResult.Locked -> {
            response.headers.append(HttpHeaders.RetryAfter, ((result.untilMs - System.currentTimeMillis()) / 1000).coerceAtLeast(1).toString())
            throw AuthException(ErrorCodes.TOO_MANY_ATTEMPTS, "Too many wrong passwords; try again later")
        }
    }

private fun browserName(agent: String): String = when {
    "Edg/" in agent -> "Edge"
    "Firefox/" in agent -> "Firefox"
    "Chrome/" in agent -> "Chrome"
    "Safari/" in agent -> "Safari"
    else -> "browser"
} + when {
    "Android" in agent -> " (Android)"
    "iPhone" in agent || "iPad" in agent -> " (iOS)"
    "Windows" in agent -> " (Windows)"
    "Mac OS" in agent -> " (macOS)"
    "Linux" in agent -> " (Linux)"
    else -> ""
}

/** Runs blocking work (the database, files, password hashing) off the request threads. */
suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

/** The request body, refused when it is larger than [limit] bytes. */
suspend fun ApplicationCall.receiveLimited(limit: Long): ByteArray {
    val stream = receiveChannel().toInputStream()
    val bytes = io { stream.readNBytes(limit.toInt() + 1) }
    if (bytes.size > limit) throw com.zyagodin.booksound.server.library.LibraryException(ErrorCodes.BAD_REQUEST, "Too large")
    return bytes
}
