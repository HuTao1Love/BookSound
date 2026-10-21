package com.zyagodin.booksound.server.update

import com.zyagodin.booksound.api.ApiJson
import com.zyagodin.booksound.api.ServerUpdateDto
import com.zyagodin.booksound.api.UpdateMethod
import com.zyagodin.booksound.api.UpdateStage
import com.zyagodin.booksound.core.update.AppVersion
import com.zyagodin.booksound.server.ServerConfig
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** The server can't install the update by itself; [command] does it on the host. */
class UpdateNotPossibleException(message: String) : Exception(message)

/**
 * Compares the server with the latest GitHub release and installs that release.
 *
 * The server never replaces its own files: it runs as an unprivileged user and anything it wrote would then be run
 * by root. The LXC install has a root systemd unit that watches [ServerConfig.updateDir] for a request file and runs
 * install.sh, which downloads the release from GitHub itself; in Docker, Watchtower pulls the new image.
 */
class UpdateService(
    private val config: ServerConfig,
    /** The version of the latest release that has a server archive, or null when there is none. */
    private val latestRelease: () -> String? = ::fetchLatestRelease,
    private val clock: () -> Long = System::currentTimeMillis,
    private val dockerized: Boolean = File("/.dockerenv").exists(),
) {
    private val requestFile get() = File(config.updateDir, "request")
    private val statusFile get() = File(config.updateDir, "status")

    @Volatile private var cached: Pair<Long, String?>? = null
    @Volatile private var watchtowerStage: Pair<UpdateStage, String?>? = null

    val method: UpdateMethod
        get() = when {
            config.updater == "systemd" -> UpdateMethod.SYSTEMD
            config.updater == "watchtower" && config.watchtowerUrl != null && config.watchtowerToken != null -> UpdateMethod.WATCHTOWER
            else -> UpdateMethod.MANUAL
        }

    /** What the admin page shows; asks GitHub at most every [CHECK_INTERVAL_MS] unless [refresh]. */
    fun check(refresh: Boolean = false): ServerUpdateDto {
        val now = clock()
        val latest = cached?.takeIf { !refresh && now - it.first < CHECK_INTERVAL_MS }?.second
            ?: runCatching { latestRelease() }.onFailure { log.warn("Can't check for updates: {}", it.toString()) }.getOrNull()
                .also { if (it != null) cached = now to it }
        val current = AppVersion.parse(config.version)
        val newest = AppVersion.parse(latest)
        // A development build has no release number: any release can replace it.
        val available = newest != null && (current == null || newest > current)
        val (stage, message) = stage()
        return ServerUpdateDto(
            currentVersion = config.version,
            latestVersion = latest,
            available = available,
            method = method,
            stage = stage,
            message = message,
            command = command(),
        )
    }

    /** Starts installing the latest release; the server restarts when it is in place. */
    fun install(): ServerUpdateDto {
        when (method) {
            UpdateMethod.SYSTEMD -> {
                config.updateDir.mkdirs()
                statusFile.delete()
                requestFile.writeText("${clock()}\n")
                log.info("Update requested")
            }
            UpdateMethod.WATCHTOWER -> requestWatchtower()
            UpdateMethod.MANUAL -> throw UpdateNotPossibleException("This server can't update itself; run the command on its host")
        }
        return check()
    }

    private fun stage(): Pair<UpdateStage, String?> {
        val now = clock()
        if (method == UpdateMethod.WATCHTOWER) return watchtowerStage ?: (UpdateStage.IDLE to null)
        if (method != UpdateMethod.SYSTEMD) return UpdateStage.IDLE to null
        // Written by the update unit: "installing", or "failed" with the end of install.sh's output below it.
        if (statusFile.exists()) {
            val lines = statusFile.readLines()
            val age = now - statusFile.lastModified()
            return when (lines.firstOrNull()?.trim()) {
                "installing" -> if (age < INSTALL_TIMEOUT_MS) UpdateStage.INSTALLING to null
                else UpdateStage.FAILED to "The update did not finish; see journalctl -u booksound-update"
                "failed" -> UpdateStage.FAILED to lines.drop(1).joinToString("\n").trim().ifEmpty { null }
                else -> UpdateStage.IDLE to null
            }
        }
        if (requestFile.exists()) {
            // The unit removes the request at once; one that stays means the unit is not installed.
            return if (now - requestFile.lastModified() < PICKUP_TIMEOUT_MS) UpdateStage.REQUESTED to null
            else UpdateStage.FAILED to "The update unit did not respond. Run install.sh once as root to add it: ${lxcCommand()}"
        }
        return UpdateStage.IDLE to null
    }

    /**
     * Asks Watchtower to update in the background (it answers 202 at once): it then restarts this container with the
     * new image. Which containers it may touch is Watchtower's setting (docker-compose.yml limits it to this one).
     */
    private fun requestWatchtower() {
        val request = HttpRequest.newBuilder(URI("${config.watchtowerUrl}/v1/update?async=true"))
            .header("Authorization", "Bearer ${config.watchtowerToken}")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            watchtowerStage = UpdateStage.FAILED to "Can't reach Watchtower at ${config.watchtowerUrl}: ${e.message ?: e.javaClass.simpleName}"
            return
        }
        watchtowerStage = when (response.statusCode()) {
            in 200..299 -> UpdateStage.REQUESTED to null
            401 -> UpdateStage.FAILED to "Watchtower refused the token (BOOKSOUND_WATCHTOWER_TOKEN must match WATCHTOWER_HTTP_API_TOKEN)"
            429 -> UpdateStage.FAILED to "Watchtower is already updating; try again in a minute"
            else -> UpdateStage.FAILED to "Watchtower answered ${response.statusCode()}: ${response.body().take(300)}"
        }
    }

    private fun command(): String = if (dockerized) DOCKER_COMMAND else lxcCommand()

    private fun lxcCommand() = "curl -fsSL https://raw.githubusercontent.com/$REPO/main/deploy/lxc/install.sh | sh"

    companion object {
        private val log = LoggerFactory.getLogger(UpdateService::class.java)
        const val REPO = "HuTao1Love/BookSound"
        private const val DOCKER_COMMAND = "docker compose pull && docker compose up -d"
        private const val CHECK_INTERVAL_MS = 10 * 60 * 1000L
        private const val PICKUP_TIMEOUT_MS = 2 * 60 * 1000L
        private const val INSTALL_TIMEOUT_MS = 15 * 60 * 1000L

        private val http: HttpClient = HttpClient.newBuilder()
            .proxy(ProxySelector.getDefault())
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build()

        private fun fetchLatestRelease(): String? {
            val request = HttpRequest.newBuilder(URI("https://api.github.com/repos/$REPO/releases/latest"))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "BookSound-Server")
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 404) return null
            if (response.statusCode() != 200) throw IOException("GitHub answered ${response.statusCode()}")
            return latestServerVersion(response.body())
        }

        /** The release's version when it carries a server archive (the LXC install needs it). */
        fun latestServerVersion(releaseJson: String): String? {
            val release = ApiJson.parseToJsonElement(releaseJson).jsonObject
            val assets = release["assets"] as? JsonArray ?: return null
            val hasServer = assets.any { asset ->
                val name = asset.jsonObject["name"]?.jsonPrimitive?.content.orEmpty()
                name.startsWith("BookSound-Server-") && name.endsWith(".tar.gz")
            }
            return release["tag_name"]?.jsonPrimitive?.content?.takeIf { hasServer }
        }
    }
}
