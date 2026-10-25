package com.zyagodin.booksound.server

import java.io.File
import java.net.InetAddress

/**
 * Settings that must be known before the server starts, from environment variables. Everything an
 * admin can change while it runs (bitrate, torrent limits, ...) lives in the database instead.
 */
data class ServerConfig(
    val dataDir: File,
    val host: String,
    val port: Int,
    /** Addresses allowed to tell the client's address and scheme in X-Forwarded-For/-Proto (the router). */
    val trustedProxies: Set<InetAddress>,
    val ffmpeg: String,
    val torrentsEnabled: Boolean,
    val version: String,
    /** What installs a newer release: "systemd" (the LXC install's update unit), "watchtower" or nothing. */
    val updater: String? = null,
    /** Watchtower's HTTP API, e.g. http://watchtower:8080, and its token. */
    val watchtowerUrl: String? = null,
    val watchtowerToken: String? = null,
    /** Book details may be looked up on audiobook sites, and books downloaded from them (the server then fetches their pages). */
    val metadataSearchEnabled: Boolean = true,
) {
    val libraryDir get() = File(dataDir, "library")
    val coversDir get() = File(dataDir, "covers")
    val derivedDir get() = File(dataDir, "derived")
    val torrentsDir get() = File(dataDir, "torrents")
    val uploadsDir get() = File(dataDir, "uploads")
    val workDir get() = File(dataDir, "work")
    val database get() = File(dataDir, "booksound.db")
    val updateDir get() = File(dataDir, "update")

    fun createDirectories() {
        listOf(dataDir, libraryDir, coversDir, derivedDir, torrentsDir, uploadsDir, workDir).forEach { it.mkdirs() }
    }

    companion object {
        fun fromEnvironment(env: Map<String, String> = System.getenv()): ServerConfig {
            fun value(name: String) = env["BOOKSOUND_$name"]?.trim()?.takeIf { it.isNotEmpty() }
            return ServerConfig(
                dataDir = File(value("DATA") ?: "data").absoluteFile,
                host = value("HOST") ?: "0.0.0.0",
                port = value("PORT")?.toInt() ?: 8080,
                trustedProxies = value("TRUSTED_PROXIES").orEmpty().split(',', ' ')
                    .filter { it.isNotBlank() }
                    .map { InetAddress.getByName(it.trim()) }
                    .toSet(),
                ffmpeg = value("FFMPEG") ?: "ffmpeg",
                torrentsEnabled = value("TORRENTS")?.lowercase() != "off",
                version = ServerConfig::class.java.getResource("/booksound-version.txt")?.readText()?.trim()
                    ?.takeIf { !it.startsWith("$") } ?: "dev",
                updater = value("UPDATER")?.lowercase(),
                watchtowerUrl = value("WATCHTOWER_URL")?.trimEnd('/'),
                watchtowerToken = value("WATCHTOWER_TOKEN"),
                metadataSearchEnabled = value("METADATA_SEARCH")?.lowercase() != "off",
            )
        }
    }
}
