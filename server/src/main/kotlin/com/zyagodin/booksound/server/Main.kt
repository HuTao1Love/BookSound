package com.zyagodin.booksound.server

import com.zyagodin.booksound.api.Features
import com.zyagodin.booksound.server.auth.AuthService
import com.zyagodin.booksound.server.db.Database
import com.zyagodin.booksound.server.events.Events
import com.zyagodin.booksound.server.http.adminRoutes
import com.zyagodin.booksound.server.http.authRoutes
import com.zyagodin.booksound.server.http.importRoutes
import com.zyagodin.booksound.server.http.installHttp
import com.zyagodin.booksound.server.http.libraryRoutes
import com.zyagodin.booksound.server.http.metadataRoutes
import com.zyagodin.booksound.server.http.webApp
import com.zyagodin.booksound.server.importer.Ffmpeg
import com.zyagodin.booksound.server.importer.ImportService
import com.zyagodin.booksound.server.importer.SiteImportService
import com.zyagodin.booksound.server.importer.UploadService
import com.zyagodin.booksound.server.library.Covers
import com.zyagodin.booksound.server.library.LibraryFiles
import com.zyagodin.booksound.server.library.LibraryRepository
import com.zyagodin.booksound.server.library.LibraryService
import com.zyagodin.booksound.server.metadata.HttpPageFetcher
import com.zyagodin.booksound.server.metadata.MetadataService
import com.zyagodin.booksound.server.settings.SettingsStore
import com.zyagodin.booksound.server.sync.SyncService
import com.zyagodin.booksound.server.torrent.TorrentService
import com.zyagodin.booksound.server.update.UpdateService
import com.zyagodin.booksound.server.watch.WatchVariants
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** Every service of a running server. */
class ServerContext(val config: ServerConfig) : AutoCloseable {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = Database(config.database)
    val events = Events()
    val auth = AuthService(db)
    val settings = SettingsStore(db)
    val library = LibraryService(LibraryRepository(db), LibraryFiles(config.libraryDir), Covers(config.coversDir), events)
    val sync = SyncService(library, events)
    val ffmpeg = Ffmpeg(config.ffmpeg)
    val imports = ImportService(db, library, settings, ffmpeg, events, config.workDir, scope)
    val uploads = UploadService(db, config.uploadsDir, library, imports)
    val watch = WatchVariants(library, settings, ffmpeg, imports, config.derivedDir, scope)
    val updates = UpdateService(config)
    private val pages = HttpPageFetcher()
    val metadata: MetadataService? = if (config.metadataSearchEnabled) MetadataService(fetcher = pages) else null
    val sites: SiteImportService? = if (config.metadataSearchEnabled) SiteImportService(imports, pages, config.workDir) else null
    val torrents: TorrentService? = if (config.torrentsEnabled) TorrentService.create(db, config.torrentsDir, library, imports, settings, events, scope) else null

    val features: List<String> get() = buildList {
        if (torrents != null) add(Features.TORRENTS)
        if (ffmpeg.available) add(Features.TRANSCODE)
        if (metadata != null) add(Features.METADATA)
        if (sites != null) add(Features.SITE_IMPORT)
    }

    fun start() {
        // Without a UTF-8 locale Java can't name files like "Толстой/Война и мир.m4b".
        val names = System.getProperty("sun.jnu.encoding").orEmpty()
        if (!names.replace("-", "").equals("UTF8", ignoreCase = true)) {
            LoggerFactory.getLogger("BookSound").error("File names use $names, not UTF-8: books with non-Latin names will fail. Set LANG=C.UTF-8.")
        }
        auth.init()
        imports.start()
        torrents?.start()
        watch.start()
        scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { library.scan() }.onFailure { log.warn("Library scan failed", it) }
                uploads.cleanUp()
                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    override fun close() {
        imports.stop()
        torrents?.close()
        scope.cancel()
        db.close()
    }

    companion object {
        private val log = LoggerFactory.getLogger(ServerContext::class.java)
        private const val SCAN_INTERVAL_MS = 30 * 60 * 1000L
    }
}

fun Application.booksound(ctx: ServerContext) {
    installHttp(ctx.auth, ctx.config.trustedProxies)
    routing {
        authRoutes(ctx)
        libraryRoutes(ctx)
        importRoutes(ctx)
        adminRoutes(ctx)
        metadataRoutes(ctx)
    }
    webApp()
    monitor.subscribe(ApplicationStopping) { ctx.close() }
}

fun main() {
    val config = ServerConfig.fromEnvironment()
    config.createDirectories()
    val log = LoggerFactory.getLogger("BookSound")
    log.info("BookSound server {} — data in {}", config.version, config.dataDir)
    val ctx = ServerContext(config)
    ctx.start()
    if (!ctx.ffmpeg.available) log.warn("ffmpeg not found ({}): only AAC books can be imported", config.ffmpeg)
    embeddedServer(Netty, host = config.host, port = config.port) {
        booksound(ctx)
    }.start(wait = true)
}
