package com.zyagodin.booksound.server.watch

import com.zyagodin.booksound.api.EventTypes
import com.zyagodin.booksound.api.ImportStage
import com.zyagodin.booksound.api.WatchProfileDto
import com.zyagodin.booksound.api.WatchProfileMode
import com.zyagodin.booksound.api.toModel
import com.zyagodin.booksound.core.io.CancellationSignal
import com.zyagodin.booksound.core.metadata.AudioProbe
import com.zyagodin.booksound.core.metadata.mp4.Mp4TagSpec
import com.zyagodin.booksound.core.model.EmbeddedPicture
import com.zyagodin.booksound.core.organize.ImportChecks
import com.zyagodin.booksound.server.importer.Ffmpeg
import com.zyagodin.booksound.server.importer.ImportFailure
import com.zyagodin.booksound.server.importer.ImportService
import com.zyagodin.booksound.server.importer.JobRecord
import com.zyagodin.booksound.server.importer.WatchSpec
import com.zyagodin.booksound.server.library.BookRow
import com.zyagodin.booksound.server.library.Covers
import com.zyagodin.booksound.server.library.LibraryService
import com.zyagodin.booksound.server.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Smaller copies of books for watches, made with a watch profile (bitrate, mono, sample rate, cover size).
 * A copy keeps the book's id, details and chapters, and positions are times, so progress syncs as usual.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class WatchVariants(
    private val library: LibraryService,
    private val settings: SettingsStore,
    private val ffmpeg: Ffmpeg,
    private val imports: ImportService,
    private val dir: File,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(WatchVariants::class.java)

    fun start() {
        imports.watchRunner = { spec, workDir, progress -> build(spec, workDir, progress) }
        // Profiles that build copies ahead of time look for new work after library changes and every hour.
        scope.launch(Dispatchers.IO) {
            while (true) {
                runCatching { prepareAhead() }.onFailure { log.warn("Preparing watch copies failed", it) }
                delay(60 * 60 * 1000L)
            }
        }
        scope.launch(Dispatchers.IO) {
            library.events.events.filter { it.type == EventTypes.LIBRARY && it.bookId != null }.debounce(5_000).collect {
                runCatching { prepareAhead() }
            }
        }
    }

    fun file(book: BookRow, profile: WatchProfileDto): File = File(File(dir, book.id), "${profile.id}-r${book.fileRevision}.m4b")

    /** The ready copy, touched so the cache keeps it, or null. */
    fun ready(book: BookRow, profile: WatchProfileDto): File? = file(book, profile).takeIf { it.isFile }?.also { it.setLastModified(System.currentTimeMillis()) }

    /**
     * Queues the copy unless it exists or is being made; returns the job making it. A copy that failed lately is not
     * tried again: its job comes back with the error, so whoever waits for it stops (clearing the job allows a retry).
     */
    fun request(book: BookRow, profile: WatchProfileDto): JobRecord? {
        if (ready(book, profile) != null) return null
        imports.activeWatchJob(book.id, profile.id)?.let { return it }
        imports.latestWatchJob(book.id, profile.id, book.fileRevision)
            ?.takeIf { it.stage == ImportStage.FAILED && System.currentTimeMillis() - (it.finishedAt ?: it.createdAt) < RETRY_FAILED_AFTER_MS }
            ?.let { return it }
        if (!ffmpeg.available) throw ImportFailure("ffmpeg is needed for watch copies")
        return imports.enqueue(WatchSpec(book.id, profile.id, book.fileRevision), "⌚ ${book.metadata.title}")
    }

    fun dropProfile(profileId: String) {
        dir.listFiles()?.forEach { bookDir -> bookDir.listFiles { f -> f.name.startsWith("$profileId-r") }?.forEach { it.delete() } }
    }

    private fun prepareAhead() {
        if (!ffmpeg.available) return
        val profiles = settings.watchProfiles().filter { it.mode != WatchProfileMode.ON_DEMAND }
        if (profiles.isEmpty()) return
        val books = library.repo.books()
        val inProgress = library.repo.booksInProgress()
        for (profile in profiles) {
            for (book in books) {
                if (profile.mode == WatchProfileMode.IN_PROGRESS && book.id !in inProgress) continue
                if (ready(book, profile) == null && imports.activeWatchJob(book.id, profile.id) == null) request(book, profile)
            }
        }
    }

    private suspend fun build(spec: WatchSpec, workDir: File, progress: (Float) -> Unit) {
        val book = library.repo.book(spec.bookId)?.takeIf { !it.deleted } ?: throw ImportFailure("The book is gone")
        val profile = settings.watchProfile(spec.profileId) ?: throw ImportFailure("The watch profile is gone")
        val source = library.fileOf(book).takeIf { it.isFile } ?: throw ImportFailure("The book's file is missing")
        val audio = File(workDir, "audio.m4a")
        ffmpeg.encodeAac(listOf(source), audio, profile.bitrateKbps, if (profile.mono) 1 else null, profile.sampleRate, book.durationMs) { progress(it * 0.95f) }

        val chapters = library.repo.chapters(book.id).map { it.toModel() }
        val cover = if (profile.coverSize > 0) {
            library.covers.file(book.id)?.readBytes()?.let { bytes ->
                EmbeddedPicture.sniffMimeType(bytes)?.let { Covers.shrink(EmbeddedPicture(bytes, it), profile.coverSize) }
            }
        } else {
            null
        }
        val target = file(book, profile)
        target.parentFile.mkdirs()
        val partial = File(target.parentFile, target.name + ".partial")
        library.files.open(audio).use { src ->
            val actual = AudioProbe.probe(src, audio.name).durationMs ?: book.durationMs
            val aligned = ImportChecks.alignChapters(chapters, actual, book.durationMs)
            val ctx = currentCoroutineContext()
            library.files.writeTagged(src, partial, Mp4TagSpec(book.id, book.metadata.toModel(), aligned, cover), CancellationSignal { !ctx.isActive })
            library.verify(partial, book.id, actual, aligned.size)
        }
        if (!partial.renameTo(target)) {
            partial.delete()
            throw ImportFailure("Could not store the watch copy")
        }
        // Copies of older revisions of the book are obsolete now.
        target.parentFile.listFiles { f -> f.name.startsWith("${profile.id}-r") && f != target }?.forEach { it.delete() }
        progress(1f)
        trimCache()
    }

    /** Deletes the least recently used copies above the size limit. */
    private fun trimCache() {
        val limit = settings.current.watchCacheLimitMib.toLong() shl 20
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "m4b" }.sortedByDescending { it.lastModified() }.toList()
        var total = 0L
        for (f in files) {
            total += f.length()
            if (total > limit) f.delete()
        }
        dir.listFiles()?.filter { it.isDirectory && it.list()?.isEmpty() == true }?.forEach { it.delete() }
    }

    private companion object {
        /** How long a failed copy is reported as failed before a request builds it again. */
        const val RETRY_FAILED_AFTER_MS = 30 * 60 * 1000L
    }
}
