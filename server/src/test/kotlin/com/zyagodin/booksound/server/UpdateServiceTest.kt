package com.zyagodin.booksound.server

import com.zyagodin.booksound.api.UpdateMethod
import com.zyagodin.booksound.api.UpdateStage
import com.zyagodin.booksound.server.update.UpdateNotPossibleException
import com.zyagodin.booksound.server.update.UpdateService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UpdateServiceTest {
    private lateinit var dataDir: File
    private var now = 1_000_000L

    @Before
    fun setUp() {
        dataDir = Files.createTempDirectory("booksound-update").toFile()
    }

    @After
    fun tearDown() {
        dataDir.deleteRecursively()
    }

    private fun service(version: String, updater: String?, latest: String? = "1.0.40", docker: Boolean = false): UpdateService {
        val config = ServerConfig.fromEnvironment(mapOf("BOOKSOUND_DATA" to dataDir.absolutePath)).copy(version = version, updater = updater)
        return UpdateService(config, latestRelease = { latest }, clock = { now }, dockerized = docker)
    }

    @Test
    fun `newer release is offered, the same or older is not`() {
        assertTrue(service("1.0.35", null).check().available)
        assertFalse(service("1.0.40", null).check().available)
        assertFalse(service("1.0.41", null).check().available)
        // A development build can always be replaced by a release.
        assertTrue(service("dev", null).check().available)
        assertFalse(service("1.0.35", null, latest = null).check().available)
    }

    @Test
    fun `the method follows the environment`() {
        assertEquals(UpdateMethod.SYSTEMD, service("1.0", "systemd").method)
        assertEquals(UpdateMethod.MANUAL, service("1.0", null).method)
        // Watchtower without its address and token can't be used.
        assertEquals(UpdateMethod.MANUAL, service("1.0", "watchtower").method)
        assertEquals("docker compose pull && docker compose up -d", service("1.0", null, docker = true).check().command)
    }

    @Test(expected = UpdateNotPossibleException::class)
    fun `a manual server refuses to install`() {
        service("1.0", null).install()
    }

    @Test
    fun `systemd update goes through the request and status files`() {
        val updates = service("1.0.35", "systemd")
        assertEquals(UpdateStage.REQUESTED, updates.install().stage)
        val request = File(dataDir, "update/request")
        assertTrue(request.exists())

        // Nobody picked the request up: the update unit is missing.
        request.setLastModified(now - 3 * 60 * 1000L)
        val missing = updates.check()
        assertEquals(UpdateStage.FAILED, missing.stage)
        assertTrue(missing.message!!.contains("install.sh"))

        // The unit took it and runs install.sh.
        request.delete()
        val status = File(dataDir, "update/status")
        status.writeText("installing\n")
        status.setLastModified(now)
        assertEquals(UpdateStage.INSTALLING, updates.check().stage)

        status.writeText("failed\nE: Unable to locate package ffmpeg\n")
        val failed = updates.check()
        assertEquals(UpdateStage.FAILED, failed.stage)
        assertEquals("E: Unable to locate package ffmpeg", failed.message)

        // A new attempt clears the old failure.
        assertEquals(UpdateStage.REQUESTED, updates.install().stage)
        assertFalse(status.exists())
    }

    @Test
    fun `only releases with a server archive count`() {
        val json = """{"tag_name":"1.0.41","assets":[{"name":"BookSound-1.0.41.apk"},{"name":"BookSound-Server-1.0.41.tar.gz"}]}"""
        assertEquals("1.0.41", UpdateService.latestServerVersion(json))
        assertNull(UpdateService.latestServerVersion("""{"tag_name":"1.0.42","assets":[{"name":"BookSound-1.0.42.apk"}]}"""))
    }
}
