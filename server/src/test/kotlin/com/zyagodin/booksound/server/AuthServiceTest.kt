package com.zyagodin.booksound.server

import com.zyagodin.booksound.api.DeviceKind
import com.zyagodin.booksound.server.auth.AuthException
import com.zyagodin.booksound.server.auth.AuthService
import com.zyagodin.booksound.server.auth.LoginResult
import com.zyagodin.booksound.server.db.Database
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AuthServiceTest {

    @Test
    fun `a replaced refresh token used after the grace window revokes the device`() {
        val dir = Files.createTempDirectory("auth").toFile()
        var now = 1_000_000L
        try {
            Database(File(dir, "db")).use { db ->
                val auth = AuthService(db) { now }
                val user = auth.createUser("anna", "long enough", isAdmin = false)
                val device = auth.createDevice(user.id, "Watch", DeviceKind.WATCH, null)
                val first = auth.issueTokens(device, user)
                val second = auth.refresh(first.refreshToken)

                now += AuthService.LOST_REFRESH_GRACE_MS + 1
                val e = runCatching { auth.refresh(first.refreshToken) }.exceptionOrNull()
                assertEquals("unauthorized", (e as AuthException).code)
                // Revoked: the tokens it was given no longer work either.
                assertNull(auth.authenticate(second.accessToken, viaCookie = false))
                assertNotNull(runCatching { auth.refresh(second.refreshToken) }.exceptionOrNull())
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `wrong passwords from one address don't lock the account out elsewhere`() {
        val dir = Files.createTempDirectory("auth").toFile()
        try {
            Database(File(dir, "db")).use { db ->
                val auth = AuthService(db) { 1_000_000L }
                auth.createUser("anna", "long enough", isAdmin = false)
                repeat(6) { auth.checkCredentials("anna", "wrong guess", "203.0.113.9") }

                assertTrue(auth.checkCredentials("anna", "long enough", "203.0.113.9") is LoginResult.Locked)
                assertTrue(auth.checkCredentials("anna", "long enough", "192.168.1.20") is LoginResult.Success)
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
