package com.zyagodin.booksound.server.auth

import com.zyagodin.booksound.api.DeviceDto
import com.zyagodin.booksound.api.DeviceKind
import com.zyagodin.booksound.api.TokenResponse
import com.zyagodin.booksound.api.UserDto
import com.zyagodin.booksound.server.db.Database
import com.zyagodin.booksound.server.db.Tx
import com.zyagodin.booksound.server.db.bool
import com.zyagodin.booksound.server.db.long
import com.zyagodin.booksound.server.db.str
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Who made a request: a user, from one of their devices. */
data class Principal(val userId: String, val userName: String, val isAdmin: Boolean, val deviceId: String, val viaCookie: Boolean) {
    val user get() = UserDto(userId, userName, isAdmin)
}

sealed interface LoginResult {
    data class Success(val user: UserDto) : LoginResult
    data object InvalidCredentials : LoginResult
    data class Locked(val untilMs: Long) : LoginResult
}

class AuthException(val code: String, message: String? = null) : Exception(message ?: code)

/**
 * Accounts, devices and tokens. Every token is a random secret stored only as a hash. Apps get a
 * short-lived access token and a refresh token that is replaced on every use; the web app gets a
 * session cookie. Revoking a device ends all of them.
 */
class AuthService(private val db: Database, private val clock: () -> Long = System::currentTimeMillis) {
    private val log = LoggerFactory.getLogger(AuthService::class.java)

    /** Valid until the first account is created; printed to the log so only whoever runs the server sees it. */
    @Volatile
    var setupToken: String? = null
        private set

    private data class Cached(val principal: Principal, val expiresAt: Long, val checkedAt: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    private data class Failures(val count: Int, val lockedUntil: Long?)

    /**
     * Wrong passwords per account and client address. Counted per address so a stranger guessing at an account
     * locks out only themselves, not its owner signing in from elsewhere.
     */
    private val failures = ConcurrentHashMap<String, Failures>()

    fun init() {
        if (db.tx { queryOne("SELECT COUNT(*) FROM users") { it.getInt(1) } } == 0) {
            setupToken = Secrets.token().take(24)
            log.warn("No accounts yet. Open the web app and create the admin account with this setup token: {}", setupToken)
        }
    }

    val setupRequired get() = setupToken != null

    // ------------------------------------------------------------------------------------- users

    fun createFirstAdmin(token: String, name: String, password: String): UserDto {
        val expected = setupToken ?: throw AuthException("setup_done")
        if (!java.security.MessageDigest.isEqual(expected.toByteArray(), token.trim().toByteArray())) {
            throw AuthException("invalid_credentials", "Wrong setup token")
        }
        val user = createUser(name, password, isAdmin = true)
        setupToken = null
        return user
    }

    fun createUser(name: String, password: String, isAdmin: Boolean): UserDto {
        val clean = name.trim()
        if (clean.isEmpty() || clean.length > 64) throw AuthException("bad_request", "Invalid user name")
        checkPassword(password)
        val id = UUID.randomUUID().toString()
        val hash = Secrets.hashPassword(password)
        db.tx {
            if (queryOne("SELECT 1 FROM users WHERE name = ?", clean) { 1 } != null) throw AuthException("conflict", "User exists")
            update("INSERT INTO users (id, name, password_hash, is_admin, created_at) VALUES (?, ?, ?, ?, ?)", id, clean, hash, isAdmin, clock())
        }
        return UserDto(id, clean, isAdmin)
    }

    fun users(): List<UserDto> = db.tx {
        query("SELECT id, name, is_admin FROM users ORDER BY name") { UserDto(it.str("id")!!, it.str("name")!!, it.bool("is_admin")) }
    }

    fun updateUser(id: String, password: String?, isAdmin: Boolean?) {
        password?.let(::checkPassword)
        val hash = password?.let(Secrets::hashPassword)
        db.tx {
            if (queryOne("SELECT 1 FROM users WHERE id = ?", id) { 1 } == null) throw AuthException("not_found")
            if (isAdmin == false) requireAnotherAdmin(id)
            hash?.let { update("UPDATE users SET password_hash = ? WHERE id = ?", it, id) }
            isAdmin?.let { update("UPDATE users SET is_admin = ? WHERE id = ?", it, id) }
        }
        if (hash != null) {
            revokeAllDevices(id, keep = null)
            failures.keys.removeIf { it.startsWith("$id|") }
        }
        cache.clear()
    }

    fun deleteUser(id: String) {
        db.tx {
            requireAnotherAdmin(id)
            update("DELETE FROM users WHERE id = ?", id)
        }
        cache.clear()
    }

    fun changePassword(principal: Principal, current: String, new: String) {
        val stored = db.tx { queryOne("SELECT password_hash FROM users WHERE id = ?", principal.userId) { it.str("password_hash") } }
            ?: throw AuthException("not_found")
        if (!Secrets.verifyPassword(current, stored)) throw AuthException("invalid_credentials")
        checkPassword(new)
        db.tx { update("UPDATE users SET password_hash = ? WHERE id = ?", Secrets.hashPassword(new), principal.userId) }
        // Other devices sign in again with the new password.
        revokeAllDevices(principal.userId, keep = principal.deviceId)
    }

    private fun Tx.requireAnotherAdmin(userId: String) {
        val admins = query("SELECT id FROM users WHERE is_admin = 1") { it.str("id")!! }
        if (admins == listOf(userId)) throw AuthException("conflict", "The last admin can't be removed")
    }

    private fun checkPassword(password: String) {
        if (password.length < 8) throw AuthException("bad_request", "Password must be at least 8 characters")
        if (password.length > 256) throw AuthException("bad_request", "Password is too long")
    }

    /**
     * Checks a password; repeated failures from one [address] lock the account there for longer each time (up to an
     * hour). Other addresses are not affected.
     */
    fun checkCredentials(name: String, password: String, address: String): LoginResult {
        val now = clock()
        data class Row(val id: String, val name: String, val hash: String, val admin: Boolean)
        val row = db.tx {
            queryOne("SELECT * FROM users WHERE name = ?", name.trim()) {
                Row(it.str("id")!!, it.str("name")!!, it.str("password_hash")!!, it.bool("is_admin"))
            }
        }
        if (row == null) {
            Secrets.burnPasswordCheck(password)
            return LoginResult.InvalidCredentials
        }
        val key = "${row.id}|$address"
        val previous = failures[key]
        if (previous?.lockedUntil != null && previous.lockedUntil > now) return LoginResult.Locked(previous.lockedUntil)
        if (!Secrets.verifyPassword(password, row.hash)) {
            val failed = (previous?.count ?: 0) + 1
            val lock = if (failed >= FREE_ATTEMPTS) now + minOf(60_000L shl minOf(failed - FREE_ATTEMPTS, 6), 3_600_000L) else null
            // Many addresses guessing: forget the ones that hold no lock, so the map stays small.
            if (failures.size > MAX_TRACKED_FAILURES) failures.values.removeIf { it.lockedUntil == null || it.lockedUntil <= now }
            failures[key] = Failures(failed, lock)
            return LoginResult.InvalidCredentials
        }
        failures.remove(key)
        return LoginResult.Success(UserDto(row.id, row.name, row.admin))
    }

    // ----------------------------------------------------------------------------------- devices

    fun createDevice(userId: String, name: String, kind: DeviceKind, syncId: String?): String {
        val id = UUID.randomUUID().toString()
        db.tx {
            update(
                "INSERT INTO devices (id, user_id, name, kind, sync_id, created_at, last_seen_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, userId, name.trim().take(80).ifEmpty { kind.name.lowercase() }, kind, syncId?.take(80), clock(), clock(),
            )
        }
        return id
    }

    /** Access and refresh tokens for an app on [deviceId]. */
    fun issueTokens(deviceId: String, user: UserDto): TokenResponse {
        val access = Secrets.token()
        val refresh = Secrets.token()
        val expires = clock() + ACCESS_TTL_MS
        db.tx {
            update("DELETE FROM tokens WHERE device_id = ?", deviceId)
            update("INSERT INTO tokens (hash, device_id, kind, expires_at) VALUES (?, ?, 'ACCESS', ?)", Secrets.tokenHash(access), deviceId, expires)
            update("INSERT INTO tokens (hash, device_id, kind, expires_at) VALUES (?, ?, 'REFRESH', ?)", Secrets.tokenHash(refresh), deviceId, clock() + REFRESH_TTL_MS)
        }
        cache.entries.removeIf { it.value.principal.deviceId == deviceId }
        return TokenResponse(access, expires, refresh, deviceId, user)
    }

    /**
     * Replaces a refresh token with a new pair. A token that was already replaced revokes the device (it leaked),
     * except shortly after the replacement: then the answer most likely never reached the device (a watch on a
     * flaky link) and it retries, so it gets a fresh pair and the one it never saw stops working.
     */
    fun refresh(refreshToken: String): TokenResponse {
        val hash = Secrets.tokenHash(refreshToken)
        val now = clock()
        val found = db.tx {
            queryOne(
                """SELECT d.id AS device_id, u.id AS user_id, u.name, u.is_admin FROM tokens t
                   JOIN devices d ON d.id = t.device_id JOIN users u ON u.id = d.user_id
                   WHERE t.hash = ? AND t.kind = 'REFRESH' AND t.expires_at > ? AND d.revoked_at IS NULL""",
                hash, now,
            ) { it.str("device_id")!! to UserDto(it.str("user_id")!!, it.str("name")!!, it.bool("is_admin")) }
        }
        if (found == null) {
            data class Replaced(val deviceId: String, val refreshedAt: Long?, val user: UserDto)
            val replaced = db.tx {
                queryOne(
                    """SELECT d.id AS device_id, d.refreshed_at, u.id AS user_id, u.name, u.is_admin FROM devices d
                       JOIN users u ON u.id = d.user_id WHERE d.previous_refresh_hash = ? AND d.revoked_at IS NULL""",
                    hash,
                ) { Replaced(it.str("device_id")!!, it.long("refreshed_at"), UserDto(it.str("user_id")!!, it.str("name")!!, it.bool("is_admin"))) }
            } ?: throw AuthException("unauthorized")
            if (replaced.refreshedAt != null && now - replaced.refreshedAt <= LOST_REFRESH_GRACE_MS) {
                // The window doesn't move: a token can be retried only within it of its first replacement.
                return issueTokens(replaced.deviceId, replaced.user)
            }
            log.warn("A replaced refresh token was used again; revoking device {}", replaced.deviceId)
            revokeDevice(replaced.deviceId)
            throw AuthException("unauthorized")
        }
        val (deviceId, user) = found
        db.tx {
            update("UPDATE devices SET previous_refresh_hash = ?, refreshed_at = ?, last_seen_at = ? WHERE id = ?", hash, now, now, deviceId)
        }
        return issueTokens(deviceId, user)
    }

    /** A web session: one long-lived token kept in an HttpOnly cookie, extended while it is used. */
    fun issueSession(deviceId: String): String {
        val token = Secrets.token()
        db.tx { update("INSERT INTO tokens (hash, device_id, kind, expires_at) VALUES (?, ?, 'SESSION', ?)", Secrets.tokenHash(token), deviceId, clock() + SESSION_TTL_MS) }
        return token
    }

    /** The principal for an access token or session cookie, or null. */
    fun authenticate(token: String, viaCookie: Boolean): Principal? {
        val hash = Secrets.tokenHash(token)
        val now = clock()
        cache[hash]?.let { c ->
            if (c.expiresAt > now && now - c.checkedAt < CACHE_MS) return c.principal.copy(viaCookie = viaCookie)
        }
        val kind = if (viaCookie) "SESSION" else "ACCESS"
        data class Row(val principal: Principal, val expiresAt: Long)
        val row = db.tx {
            queryOne(
                """SELECT d.id AS device_id, u.id AS user_id, u.name, u.is_admin, t.expires_at FROM tokens t
                   JOIN devices d ON d.id = t.device_id JOIN users u ON u.id = d.user_id
                   WHERE t.hash = ? AND t.kind = ? AND t.expires_at > ? AND d.revoked_at IS NULL""",
                hash, kind, now,
            ) { Row(Principal(it.str("user_id")!!, it.str("name")!!, it.bool("is_admin"), it.str("device_id")!!, viaCookie), it.long("expires_at")!!) }
        } ?: run {
            cache.remove(hash)
            return null
        }
        var expiresAt = row.expiresAt
        db.tx {
            update("UPDATE devices SET last_seen_at = ? WHERE id = ?", now, row.principal.deviceId)
            if (viaCookie && row.expiresAt - now < SESSION_TTL_MS / 2) {
                expiresAt = now + SESSION_TTL_MS
                update("UPDATE tokens SET expires_at = ? WHERE hash = ?", expiresAt, hash)
            }
        }
        cache[hash] = Cached(row.principal, expiresAt, now)
        return row.principal
    }

    fun logout(principal: Principal) = revokeDevice(principal.deviceId)

    fun devices(principal: Principal): List<DeviceDto> = db.tx {
        query(
            "SELECT * FROM devices WHERE user_id = ? AND revoked_at IS NULL ORDER BY COALESCE(last_seen_at, created_at) DESC",
            principal.userId,
        ) {
            DeviceDto(
                it.str("id")!!, it.str("name")!!, DeviceKind.valueOf(it.str("kind")!!),
                it.long("created_at")!!, it.long("last_seen_at"), it.str("id") == principal.deviceId,
            )
        }
    }

    fun revokeDevice(principal: Principal, deviceId: String) {
        val owner = db.tx { queryOne("SELECT user_id FROM devices WHERE id = ?", deviceId) { it.str("user_id") } }
        if (owner != principal.userId && !principal.isAdmin) throw AuthException("not_found")
        revokeDevice(deviceId)
    }

    private fun revokeDevice(deviceId: String) {
        db.tx {
            update("UPDATE devices SET revoked_at = ? WHERE id = ?", clock(), deviceId)
            update("DELETE FROM tokens WHERE device_id = ?", deviceId)
        }
        cache.entries.removeIf { it.value.principal.deviceId == deviceId }
    }

    private fun revokeAllDevices(userId: String, keep: String?) {
        val ids = db.tx { query("SELECT id FROM devices WHERE user_id = ? AND revoked_at IS NULL", userId) { it.str("id")!! } }
        ids.filter { it != keep }.forEach(::revokeDevice)
    }

    companion object {
        const val ACCESS_TTL_MS = 60 * 60 * 1000L
        const val REFRESH_TTL_MS = 180L * 24 * 60 * 60 * 1000
        const val SESSION_TTL_MS = 30L * 24 * 60 * 60 * 1000
        /** How long a replaced refresh token may be retried, for an answer lost on the way. */
        const val LOST_REFRESH_GRACE_MS = 2 * 60 * 1000L
        private const val FREE_ATTEMPTS = 5
        private const val MAX_TRACKED_FAILURES = 10_000
        private const val CACHE_MS = 30_000L
    }
}
