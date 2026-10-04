package com.zyagodin.booksound.torrent

import com.zyagodin.booksound.core.torrent.TorrentFile
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.EnumNet
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.alerts.AddTorrentAlert
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.alerts.DhtBootstrapAlert
import org.libtorrent4j.alerts.FileErrorAlert
import org.libtorrent4j.alerts.ListenFailedAlert
import org.libtorrent4j.alerts.ListenSucceededAlert
import org.libtorrent4j.alerts.SaveResumeDataAlert
import org.libtorrent4j.alerts.TorrentErrorAlert
import org.libtorrent4j.alerts.TrackerErrorAlert
import org.libtorrent4j.alerts.TrackerReplyAlert
import org.libtorrent4j.alerts.TrackerWarningAlert
import java.io.File

/** Metadata of a torrent, read from .torrent bytes. */
data class TorrentMeta(val name: String, val infoHash: String, val files: List<TorrentFile>, val totalBytes: Long)

class InvalidTorrentException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Live state of a torrent in the session. */
data class EngineStatus(
    val state: State,
    /** 0..1 of the wanted files. */
    val progress: Float,
    val wantedBytes: Long,
    val wantedDoneBytes: Long,
    val downloadRate: Int,
    /** Peers we are connected to. */
    val peers: Int,
    val seeds: Int,
    /** Peers known in the swarm (from trackers, DHT and peer exchange), connected or not. */
    val swarm: Int,
    val paused: Boolean,
    val error: String?,
    /** Last error reported by a tracker, cleared when a tracker answers. */
    val trackerError: String?,
) {
    enum class State { CHECKING, DOWNLOADING, FINISHED, OTHER }

    val isComplete: Boolean get() = state == State.FINISHED && wantedBytes > 0 && wantedDoneBytes >= wantedBytes
}

/**
 * Thin wrapper around a libtorrent session. The session is started on demand and stopped when
 * nothing is downloading. Torrents are identified by their info-hash (lowercase hex).
 *
 * Events (resume data, errors) arrive on libtorrent's alert thread through [listener].
 */
class TorrentEngine(private val sessionStateFile: File, private val log: (String, Throwable?) -> Unit) {

    sealed interface Event {
        data class ResumeData(val infoHash: String, val data: ByteArray) : Event
        data class Error(val infoHash: String, val message: String, val outOfSpace: Boolean) : Event
    }

    @Volatile var listener: ((Event) -> Unit)? = null

    private val session = SessionManager(false)
    private val trackerErrors = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var lastReopen = 0L

    private val alerts = object : AlertListener {
        override fun types(): IntArray = intArrayOf(
            AlertType.SAVE_RESUME_DATA.swig(),
            AlertType.TORRENT_ERROR.swig(),
            AlertType.FILE_ERROR.swig(),
            AlertType.ADD_TORRENT.swig(),
            AlertType.TRACKER_ERROR.swig(),
            AlertType.TRACKER_REPLY.swig(),
            // Diagnostics only: why a torrent finds no peers.
            AlertType.TRACKER_WARNING.swig(),
            AlertType.LISTEN_SUCCEEDED.swig(),
            AlertType.LISTEN_FAILED.swig(),
            AlertType.DHT_BOOTSTRAP.swig(),
        )

        override fun alert(alert: Alert<*>) {
            try {
                when (alert) {
                    is TrackerErrorAlert -> {
                        val message = alert.errorMessage().ifBlank { alert.error().message }
                        trackerErrors[hashOf(alert.handle())] = message
                        log("Tracker ${alert.trackerUrl()} failed: $message", null)
                    }
                    is TrackerReplyAlert -> {
                        trackerErrors.remove(hashOf(alert.handle()))
                        log("Tracker ${alert.trackerUrl()} replied: ${alert.numPeers()} peers", null)
                    }
                    is TrackerWarningAlert -> log("Tracker ${alert.trackerUrl()} warning: ${alert.warningMessage()}", null)
                    is ListenSucceededAlert -> log("Listening on ${alert.address()}:${alert.port()} (${alert.socketType()})", null)
                    is ListenFailedAlert -> log(
                        "Could not listen on ${alert.listenInterface()} ${alert.address()}:${alert.port()} (${alert.socketType()}): " +
                            "${alert.operation()} ${alert.error().message}",
                        null,
                    )
                    is DhtBootstrapAlert -> log("DHT bootstrapped", null)
                    is SaveResumeDataAlert -> {
                        val bytes = AddTorrentParams.writeResumeDataBuf(alert.params())
                        listener?.invoke(Event.ResumeData(hashOf(alert.handle()), bytes))
                    }
                    is FileErrorAlert -> report(alert.handle(), alert.error().message + " (" + alert.filename() + ")", alert.error().value)
                    is TorrentErrorAlert -> report(alert.handle(), alert.error().message, alert.error().value)
                    is AddTorrentAlert -> {
                        val error = alert.error()
                        // A repeated add of a torrent already in the session is harmless.
                        if (error.isError && !error.message.contains("duplicate", ignoreCase = true)) {
                            val hash = alert.params().infoHashes.best.toHex().lowercase()
                            listener?.invoke(Event.Error(hash, error.message, error.value == ENOSPC))
                        }
                    }
                }
            } catch (t: Throwable) {
                log("Alert handling failed", t)
            }
        }
    }

    private fun report(handle: TorrentHandle, message: String, errno: Int) {
        if (!handle.isValid) return
        listener?.invoke(Event.Error(hashOf(handle), message, errno == ENOSPC || message.contains("space", ignoreCase = true)))
    }

    val isRunning: Boolean get() = session.isRunning

    @Synchronized
    fun start() {
        if (session.isRunning) return
        val params = runCatching { if (sessionStateFile.isFile) SessionParams(sessionStateFile.readBytes()) else null }
            .onFailure { log("Ignoring unreadable session state", it) }
            .getOrNull() ?: SessionParams(SettingsPack())
        val settings = params.settings
            .activeDownloads(4)
            .activeSeeds(0)
            .connectionsLimit(200)
            .alertQueueSize(5000)
        settings.setEnableDht(true)
        settings.setEnableLsd(true)
        params.settings = settings
        // Plain read/write I/O: memory-mapped files are unreliable on some Android storage.
        params.setPosixDiskIO()
        session.addListener(alerts)
        session.start(params)
        log("Session started, interfaces: " + interfaces(), null)
    }

    /** Network interfaces as libtorrent sees them; empty when it can't enumerate them. */
    private fun interfaces(): String = runCatching {
        EnumNet.enumInterfaces(session.swig()).joinToString { "${it.name()} ${it.interfaceAddress()}" }.ifEmpty { "none" }
    }.getOrElse { "failed: ${it.message}" }

    /**
     * State of the session and of every torrent for the debug log, one line each: logcat cuts
     * longer entries off.
     */
    fun diagnostics(): List<String> {
        if (!session.isRunning) return listOf("Session stopped")
        val torrents = handles().filter { it.isValid }.map { h ->
            val s = h.status()
            val trackers = runCatching {
                h.trackers().joinToString { t ->
                    val endpoints = t.endpoints().joinToString { e ->
                        val ih = e.infohashV1()
                        "${e.localEndpoint()} fails=${ih.fails()} working=${ih.isWorking} ${ih.message()}".trim()
                    }
                    "${t.url()} [$endpoints]"
                }
            }.getOrElse { "failed: ${it.message}" }
            "Torrent ${hashOf(h).take(8)} ${s.state()} paused=${s.flags().and_(TorrentFlags.PAUSED).non_zero()} " +
                "peers=${s.numPeers()} known=${s.listPeers()} candidates=${s.connectCandidates()} " +
                "scrape=${s.numComplete()}/${s.numIncomplete()} trackers=${s.announcingToTrackers()} dht=${s.announcingToDht()} " +
                "error=${s.errorCode().takeIf { it.isError }?.message} trackers: $trackers"
        }
        return listOf(
            "Session paused=${session.isPaused} listen=${session.listenEndpoints()} dhtRunning=${session.isDhtRunning} " +
                "dhtNodes=${session.dhtNodes()} external=${session.externalAddress()} interfaces: ${interfaces()}",
        ) + torrents
    }

    /** Saves DHT state and stops the session; torrents must have saved their resume data before. */
    @Synchronized
    fun stop() {
        if (!session.isRunning) return
        runCatching { writeAtomically(sessionStateFile, session.saveState()) }.onFailure { log("Could not save session state", it) }
        session.removeListener(alerts)
        session.stop()
    }

    /**
     * Called when the device switched networks so sockets are rebound immediately. Reopening drops
     * peer connections and re-announces to trackers, so it is rate limited.
     */
    fun onNetworkChanged() {
        val now = System.currentTimeMillis()
        if (!session.isRunning || now - lastReopen < REOPEN_MIN_INTERVAL_MS) return
        lastReopen = now
        runCatching { session.reopenNetworkSockets() }
    }

    /**
     * Downloads the metadata of a magnet link. Blocks for up to [timeoutSeconds]; returns the
     * .torrent bytes or null when no peer delivered them in time.
     */
    fun fetchMetadata(magnet: String, timeoutSeconds: Int, tempDir: File): ByteArray? {
        start()
        tempDir.mkdirs()
        return session.fetchMagnet(magnet, timeoutSeconds, tempDir)
    }

    /**
     * Adds a torrent (or updates the file selection of one already in the session) and lets it
     * download. [wanted] holds one flag per file. Resume data, when present, avoids re-checking
     * finished pieces. The torrent appears in the session asynchronously.
     */
    @Synchronized
    fun add(torrent: ByteArray, saveDir: File, resumeFile: File?, wanted: BooleanArray) {
        start()
        val info = TorrentInfo.bdecode(torrent)
        saveDir.mkdirs()
        val priorities = Array(info.numFiles()) { if (wanted.getOrElse(it) { false }) Priority.DEFAULT else Priority.IGNORE }
        val resume = resumeFile?.takeIf { it.isFile && it.length() > 0 }
        val flags = TorrentFlags.AUTO_MANAGED
        try {
            session.download(info, saveDir, resume, priorities, null, flags)
        } catch (e: IllegalArgumentException) {
            // Damaged resume data: start over, libtorrent re-checks whatever is already on disk.
            log("Resume data rejected, re-checking files", e)
            resume?.delete()
            session.download(info, saveDir, null, priorities, null, flags)
        }
    }

    fun contains(infoHash: String): Boolean = handle(infoHash) != null

    fun setWanted(infoHash: String, wanted: BooleanArray) {
        val h = handle(infoHash) ?: return
        val count = h.torrentFile()?.numFiles() ?: return
        h.prioritizeFiles(Array(count) { if (wanted.getOrElse(it) { false }) Priority.DEFAULT else Priority.IGNORE })
    }

    fun pause(infoHash: String) {
        val h = handle(infoHash) ?: return
        h.unsetFlags(TorrentFlags.AUTO_MANAGED)
        h.pause()
        h.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
    }

    /**
     * Starts the torrent right away. Torrents are taken out of libtorrent's automatic queue: it
     * starts queued torrents only every 30 s and may keep them paused; BookSound runs few
     * downloads and decides itself what runs.
     */
    fun resume(infoHash: String) {
        val h = handle(infoHash) ?: return
        h.unsetFlags(TorrentFlags.AUTO_MANAGED)
        h.resume()
    }

    /** Asks for resume data; it arrives asynchronously as [Event.ResumeData]. */
    fun requestResumeData(infoHash: String) {
        val h = handle(infoHash) ?: return
        if (h.needSaveResumeData()) h.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
    }

    fun requestAllResumeData() {
        if (!session.isRunning) return
        for (h in handles()) if (h.isValid && h.needSaveResumeData()) h.saveResumeData(TorrentHandle.SAVE_INFO_DICT)
    }

    fun remove(infoHash: String, deleteFiles: Boolean) {
        val h = handle(infoHash) ?: return
        trackerErrors.remove(infoHash)
        if (deleteFiles) session.remove(h, SessionHandle.DELETE_FILES) else session.remove(h)
    }

    fun status(infoHash: String): EngineStatus? {
        val h = handle(infoHash) ?: return null
        val s = h.status()
        // Includes torrents added paused for libtorrent's queue; the manager resumes those at once.
        val paused = s.flags().and_(TorrentFlags.PAUSED).non_zero()
        val state = when (s.state()) {
            TorrentStatus.State.CHECKING_FILES, TorrentStatus.State.CHECKING_RESUME_DATA -> EngineStatus.State.CHECKING
            TorrentStatus.State.DOWNLOADING, TorrentStatus.State.DOWNLOADING_METADATA -> EngineStatus.State.DOWNLOADING
            TorrentStatus.State.FINISHED, TorrentStatus.State.SEEDING -> EngineStatus.State.FINISHED
            else -> EngineStatus.State.OTHER
        }
        val wanted = s.totalWanted()
        val done = s.totalWantedDone()
        // Tracker scrape counts are -1 when unknown; the peer list also holds DHT/PEX peers.
        val scraped = maxOf(0, s.numComplete()) + maxOf(0, s.numIncomplete())
        return EngineStatus(
            state = state,
            progress = if (wanted > 0) (done.toDouble() / wanted).toFloat().coerceIn(0f, 1f) else 0f,
            wantedBytes = wanted,
            wantedDoneBytes = done,
            downloadRate = s.downloadPayloadRate(),
            peers = s.numPeers(),
            seeds = s.numSeeds(),
            swarm = maxOf(scraped, s.listPeers(), s.numPeers()),
            paused = paused,
            error = s.errorCode().takeIf { it.isError }?.message,
            trackerError = trackerErrors[infoHash],
        )
    }

    private fun handle(infoHash: String): TorrentHandle? {
        if (!session.isRunning || infoHash.startsWith("btmh:")) return null
        return runCatching { session.find(Sha1Hash.parseHex(infoHash)) }.getOrNull()?.takeIf { it.isValid }
    }

    private fun handles(): List<TorrentHandle> =
        runCatching { session.swig().get_torrents() }.getOrNull()?.let { v -> (0 until v.size).map { TorrentHandle(v.get(it)) } }.orEmpty()

    companion object {
        private const val ENOSPC = 28
        private const val REOPEN_MIN_INTERVAL_MS = 10_000L

        fun hashOf(handle: TorrentHandle): String = handle.infoHash().toHex().lowercase()

        /** Reads .torrent bytes; throws [InvalidTorrentException] when they are not a valid torrent. */
        fun parse(bytes: ByteArray): TorrentMeta {
            val info = try {
                TorrentInfo.bdecode(bytes)
            } catch (e: IllegalArgumentException) {
                throw InvalidTorrentException(e.message ?: "invalid torrent", e)
            }
            if (!info.isValid) throw InvalidTorrentException("invalid torrent")
            val storage = info.files()
            val files = (0 until storage.numFiles()).map { i ->
                TorrentFile(i, storage.filePath(i).replace('\\', '/'), storage.fileSize(i), storage.padFileAt(i))
            }
            return TorrentMeta(info.name(), info.infoHash().toHex().lowercase(), files, info.totalSize())
        }

        fun writeAtomically(target: File, bytes: ByteArray) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.outputStream().use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
        }
    }
}
