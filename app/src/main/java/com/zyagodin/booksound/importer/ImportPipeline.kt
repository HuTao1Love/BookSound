package com.zyagodin.booksound.importer

import android.net.Uri
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.media.MediaCodec
import androidx.media3.transformer.ExportException
import com.zyagodin.booksound.core.io.CancellationSignal
import com.zyagodin.booksound.core.io.ChannelSink
import com.zyagodin.booksound.core.io.FileChannelSource
import com.zyagodin.booksound.core.io.OperationCancelledException
import com.zyagodin.booksound.core.io.RandomAccessSource
import com.zyagodin.booksound.core.io.sha256
import com.zyagodin.booksound.core.metadata.AudioProbe
import com.zyagodin.booksound.core.metadata.CorruptedFileException
import com.zyagodin.booksound.core.metadata.UnsupportedFormatException
import com.zyagodin.booksound.core.metadata.mp4.Mp4Concat
import com.zyagodin.booksound.core.metadata.mp4.Mp4TagSpec
import com.zyagodin.booksound.core.metadata.mp4.Mp4TagWriter
import com.zyagodin.booksound.core.model.Chapter
import com.zyagodin.booksound.core.naming.FileNameSanitizer
import com.zyagodin.booksound.core.naming.LibraryLayout
import com.zyagodin.booksound.core.organize.ConversionStrategy
import com.zyagodin.booksound.cover.CoverImages
import com.zyagodin.booksound.cover.CoverStore
import com.zyagodin.booksound.data.library.LibraryRepository
import com.zyagodin.booksound.data.settings.SettingsRepository
import com.zyagodin.booksound.storage.DocEntry
import com.zyagodin.booksound.storage.DocumentStore
import com.zyagodin.booksound.storage.NotSeekableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * Executes a confirmed import:
 *
 * 1. PREPARING  – library and sources are accessible, enough temporary space.
 * 2. CONVERTING – (MP3 / multi-part) Media3 Transformer → temporary AAC file in app storage.
 *                 AAC files of one format (the book's own, or parts encoded side by side) are
 *                 joined without a temporary copy: [Mp4Concat] reads them as one file.
 * 3. WRITING    – metadata, chapters and cover are written while streaming the audio into a
 *                 hidden `.booksound-partial-<job>.m4b` file inside the target library folder.
 * 4. VERIFYING  – the written file is parsed back and hashed; anything unexpected aborts.
 * 5. FINISHING  – the partial file is atomically renamed to its final name and the database row is
 *                 written. Only now does the book appear in the library.
 *
 * Any failure or cancellation deletes the partial and temporary files and restores a book that was
 * being replaced. A crash at any point is undone on next start by [ImportJournal.recover].
 */
@OptIn(UnstableApi::class)
class ImportPipeline(
    private val documents: DocumentStore,
    private val library: LibraryRepository,
    private val covers: CoverStore,
    private val settings: SettingsRepository,
    private val journal: ImportJournal,
    private val transcoder: Transcoder,
    /** Codecs encoding at the same time across all imports (a setting, see [codecLimit]). */
    private val codecLimit: StateFlow<Int>,
) {
    fun interface Progress {
        fun report(stage: ImportStage, fraction: Float?)
    }

    private class Target(val directoryId: String, val directories: List<String>, val fileName: String, val existing: DocEntry?)

    private class Transaction {
        var partial: Uri? = null
        var aside: Uri? = null
        var asideName: String? = null
        var committed: Uri? = null
        var dbWritten = false
    }

    suspend fun run(request: ImportRequest, progress: Progress): String = withContext(Dispatchers.IO) {
        val workDir = journal.workDir(request.jobId).apply { mkdirs() }
        val tx = Transaction()
        var holdsLibrary = false
        var reservedTemp = 0L
        var stage = ImportStage.PREPARING
        val weights = StageWeights(request.strategy != ConversionStrategy.REMUX_SINGLE)
        val report = Progress { s, f ->
            stage = s
            progress.report(s, weights.overall(s, f))
        }
        try {
            report.report(ImportStage.PREPARING, null)
            val treeUri = settings.current().libraryTreeUri?.let(Uri::parse) ?: throw ImportFailure.LibraryUnavailable()
            if (!documents.hasPersistedPermission(treeUri, write = true)) throw ImportFailure.LibraryUnavailable()
            for (part in request.parts) {
                if (documents.describe(part.uri) == null) throw ImportFailure.SourceUnavailable(part.displayName)
            }
            val previousBook = request.replacesBookId?.let { library.book(it) }
            coroutineContext.ensureActive()

            var chapters = request.chapters
            val source: RandomAccessSource = when (request.strategy) {
                ConversionStrategy.REMUX_SINGLE -> openSource(request.parts.single().uri, request.parts.single().displayName)
                ConversionStrategy.CONCAT_COPY, ConversionStrategy.TRANSCODE -> {
                    reservedTemp = reserveTemporarySpace(workDir, treeUri, request)
                    val src = convert(request, workDir, report)
                    try {
                        val actualDuration = runCatching { AudioProbe.probe(src, "audio.m4a").durationMs }.getOrNull()
                        if (request.strictValidation) checkDecodedDuration(actualDuration, request.totalDurationMs)
                        if (actualDuration != null) chapters = alignChapters(chapters, actualDuration, request.totalDurationMs)
                    } catch (t: Throwable) {
                        src.close()
                        throw t
                    }
                    src
                }
            }

            // Several books convert side by side; writing into the library folder (choosing the file
            // name, copying, renaming) happens one book at a time so two books never claim one name.
            try {
                libraryWrites.lock()
            } catch (e: CancellationException) {
                source.close()
                throw e
            }
            holdsLibrary = true
            val (expectedDuration, target) = source.use { src ->
                val duration = runCatching { AudioProbe.probe(src, "source.m4b").durationMs }.getOrNull() ?: request.totalDurationMs
                val t = prepareTarget(treeUri, request, previousBook?.fileUri)
                val partialName = "${LibraryLayout.PARTIAL_PREFIX}${request.jobId}.m4b"
                val partial = documents.createFile(treeUri, t.directoryId, partialName, "application/octet-stream")
                tx.partial = partial
                journal.recordPartial(request.jobId, partial)
                write(src, partial, Mp4TagSpec(request.bookId, request.metadata, chapters, request.cover), report)
                duration to t
            }

            val sha = verify(tx.partial!!, request, chapters, expectedDuration, report)

            // ---- commit ----
            report.report(ImportStage.FINISHING, null)
            withContext(NonCancellable) {
                target.existing?.let { existing ->
                    val asideName = "${LibraryLayout.REPLACED_PREFIX}${request.jobId}.m4b"
                    tx.aside = documents.rename(existing.uri, asideName)
                    tx.asideName = existing.name
                    journal.recordAside(request.jobId, tx.aside!!, existing.name)
                }
                val finalUri = documents.rename(tx.partial!!, target.fileName)
                tx.committed = finalUri
                tx.partial = null
                journal.clearPartial(request.jobId)
                val finalName = documents.entry(treeUri, finalUri)?.name ?: target.fileName

                val coverFile = request.cover?.let { covers.save(request.bookId, it) }
                val fileSize = documents.describe(finalUri)?.second?.takeIf { it >= 0 } ?: 0L
                library.saveImportedBook(
                    bookId = request.bookId,
                    metadata = request.metadata,
                    durationMs = expectedDuration,
                    chapters = chapters,
                    fileUri = finalUri,
                    relativePath = (target.directories + finalName).joinToString("/"),
                    fileSize = fileSize,
                    fileSha256 = sha,
                    coverPath = coverFile?.absolutePath,
                    coverSha256 = request.cover?.let { CoverImages.sha256(it.bytes) },
                    keepAddedAt = request.isEdit,
                )
                tx.dbWritten = true

                // The new book is in place; now discard what it replaced.
                tx.aside?.let { documents.delete(it) }
                tx.aside = null
                if (previousBook != null) {
                    if (previousBook.id != request.bookId) library.forget(previousBook.id)
                    // The replaced/edited book's previous file, if it lived elsewhere, is now obsolete.
                    val oldUri = Uri.parse(previousBook.fileUri)
                    if (oldUri != finalUri && target.existing?.uri != oldUri) {
                        if (documents.delete(oldUri)) documents.pruneEmptyDirectories(treeUri, oldUri)
                    }
                }
                if (request.cover == null) covers.delete(request.bookId)
            }
            report.report(ImportStage.DONE, 1f)
            request.bookId
        } catch (t: Throwable) {
            withContext(NonCancellable) { rollback(tx) }
            if (t is CancellationException) throw t
            if (t is OperationCancelledException) throw CancellationException("Cancelled")
            Log.w(TAG, "Import ${request.jobId} failed at $stage", t)
            throw mapFailure(t, stage, request)
        } finally {
            if (holdsLibrary) libraryWrites.unlock()
            withContext(NonCancellable) {
                workDir.deleteRecursively()
                journal.remove(request.jobId)
            }
            // Only now is the space really free: an import waiting for it checks the disk right away.
            if (reservedTemp > 0) tempReserved.update { it - reservedTemp }
        }
    }

    // ---------------------------------------------------------------- steps

    private fun openSource(uri: Uri, name: String): RandomAccessSource = try {
        documents.openSource(uri, name)
    } catch (e: NotSeekableException) {
        throw ImportFailure.SourceUnavailable(name, e)
    } catch (e: FileNotFoundException) {
        throw ImportFailure.SourceUnavailable(name, e)
    } catch (e: SecurityException) {
        throw ImportFailure.SourceUnavailable(name, e)
    }

    /**
     * Checks there is temporary space for this import next to the ones already converting, and
     * reserves it until the import ends. Returns the bytes reserved. While other imports hold the
     * space this one needs, waits for them; fails only when this import alone doesn't fit.
     */
    private suspend fun reserveTemporarySpace(workDir: File, treeUri: Uri, request: ImportRequest): Long {
        val sameVolume = runCatching { documents.rootDocumentId(treeUri).startsWith("primary:") }.getOrDefault(true)
        // Temporary audio (plus the separately encoded parts while they are joined) and, when the
        // library is on the same volume, the final copy.
        val copies = 1 + (if (encodesInParallel(request)) 1 else 0) + (if (sameVolume) 1 else 0)
        val required = request.estimatedOutputBytes * copies + SAFETY_MARGIN
        while (true) {
            val others = tempReservations.withLock {
                val others = tempReserved.value
                val available = workDir.usableSpace
                // Other imports' files written so far are already missing from `available`, so
                // this errs on the side of waiting.
                if (available <= 0 || available >= required + others) {
                    tempReserved.value = others + required
                    return required
                }
                if (others == 0L) throw ImportFailure.InsufficientStorage(required, available, ImportFailure.Location.TEMPORARY)
                others
            }
            tempReserved.first { it < others }
        }
    }

    /** Converts the parts into one AAC/MP4 audio file in [workDir] (or reads them as one); returns it. */
    private suspend fun convert(request: ImportRequest, workDir: File, report: Progress): RandomAccessSource {
        val uris = request.parts.map { it.uri }
        val output = File(workDir, "audio.m4a")
        report.report(ImportStage.CONVERTING, 0f)
        if (encodesInParallel(request) && !parallelUnreliable) {
            try {
                val joined = transcodeInParallel(request, output, report)
                report.report(ImportStage.CONVERTING, 1f)
                return joined
            } catch (e: JoinFailed) {
                // Joining the encoded parts failed: encode everything in one pass instead.
                Log.i(TAG, "Joining encoded parts failed, transcoding in one pass", e.cause)
                output.delete()
                report.report(ImportStage.CONVERTING, 0f)
            } catch (e: CodecTrouble) {
                // Several codecs at once are too much for some phones (codec errors, reclaimed
                // instances): encode in one pass with a single codec, and stop trying in parallel.
                Log.w(TAG, "Codec failed while encoding parts in parallel, transcoding in one pass", e.cause)
                parallelUnreliable = true
                output.delete()
                report.report(ImportStage.CONVERTING, 0f)
            }
        }
        if (request.strategy == ConversionStrategy.CONCAT_COPY) {
            // One AAC format throughout: the files are read as one, and written to the library as is.
            join(openAll(request.parts.map { { openSource(it.uri, it.displayName) } }))?.let {
                report.report(ImportStage.CONVERTING, 1f)
                return it
            }
            // Stream copy uses no codec, so it doesn't wait for one.
            try {
                transcoder.run(uris, output, request.bitrateKbps, request.downmixToMono, transmux = true) {
                    report.report(ImportStage.CONVERTING, it)
                }
                report.report(ImportStage.CONVERTING, 1f)
                return openFile(output)
            } catch (e: ExportException) {
                // Stream copy can fail on unusual inputs; re-encoding is slower but robust.
                Log.i(TAG, "Concatenation without re-encoding failed, transcoding instead", e)
                output.delete()
                report.report(ImportStage.CONVERTING, 0f)
            }
        }
        transcodeInOnePass(request, uris, output, report)
        report.report(ImportStage.CONVERTING, 1f)
        return openFile(output)
    }

    private fun openFile(file: File): RandomAccessSource = FileChannelSource(FileInputStream(file).channel)

    /** Opens every source, or none: those already open are closed when one fails. */
    private fun openAll(openers: List<() -> RandomAccessSource>): List<RandomAccessSource> {
        val opened = ArrayList<RandomAccessSource>(openers.size)
        try {
            openers.mapTo(opened) { it() }
        } catch (t: Throwable) {
            opened.forEach { runCatching { it.close() } }
            throw t
        }
        return opened
    }

    /**
     * [parts] read as one file (see [Mp4Concat]), or null when they can't be joined as they are;
     * then they are closed.
     */
    private fun join(parts: List<RandomAccessSource>): RandomAccessSource? = try {
        Mp4Concat.join(parts)
    } catch (e: UnsupportedFormatException) {
        Log.i(TAG, "Parts can't be joined as they are", e)
        null
    } catch (e: CorruptedFileException) {
        Log.i(TAG, "Parts can't be joined as they are", e)
        null
    }

    private suspend fun transcodeInOnePass(request: ImportRequest, uris: List<Uri>, output: File, report: Progress) {
        suspend fun encode() = withCodec {
            transcoder.run(uris, output, request.bitrateKbps, request.downmixToMono, transmux = false) {
                report.report(ImportStage.CONVERTING, it)
            }
        }
        val alone = parallelUnreliable
        try {
            encode()
        } catch (e: ExportException) {
            // Codecs fail next to other encodes on some phones: try once more alone. (A codec that
            // broke for good was already retried in a fresh process by the transcoder.)
            if (alone || e.errorCode !in CODEC_ERRORS) throw e
            Log.w(TAG, "Codec failed next to other encodes, transcoding alone", e)
            parallelUnreliable = true
            output.delete()
            report.report(ImportStage.CONVERTING, 0f)
            encode()
        }
    }

    private class JoinFailed(cause: Throwable) : Exception(cause)

    /** A codec failed while several parts were encoded at once; one pass may still work. */
    private class CodecTrouble(cause: Throwable) : Exception(cause)

    /** Set after parallel encoding hit codec trouble on this phone; one pass is used from then on. */
    @Volatile
    private var parallelUnreliable = false

    /** Codecs in use across every import running side by side (parts of one book or several books). */
    private val codecs = AdjustableGate(codecLimit)

    /** After codec trouble, one codec at a time across all imports; see [withCodec]. */
    private val soloCodec = Mutex()

    /** Temporary space reserved by the imports converting now; see [reserveTemporarySpace]. */
    private val tempReserved = MutableStateFlow(0L)
    private val tempReservations = Mutex()

    /** Held while a book is written into the library folder; see [run]. */
    private val libraryWrites = Mutex()

    private suspend fun <T> withCodec(block: suspend () -> T): T {
        if (!parallelUnreliable) {
            codecs.acquire()
            // Codec trouble may have been noticed while this waited for a permit: then go alone too,
            // or every part already queued would still start side by side and fail again.
            if (!parallelUnreliable) {
                try {
                    return block()
                } finally {
                    codecs.release()
                }
            }
            codecs.release()
        }
        // Alone means alone: wait until codecs started before the trouble was noticed are done too.
        return soloCodec.withLock {
            codecs.acquireAll()
            try {
                block()
            } finally {
                codecs.releaseAll()
            }
        }
    }

    /** Parts are encoded side by side only when more than one codec may run at once. */
    private fun encodesInParallel(request: ImportRequest) =
        request.strategy == ConversionStrategy.TRANSCODE && request.parts.size > 1 && codecLimit.value > 1

    /**
     * Encodes the parts to AAC side by side (AAC encoding runs on one CPU core per file, so a
     * single sequential pass leaves most of the phone idle), in one common format, then joins
     * them without re-encoding: read as one file, or, failing that, copied into [output].
     */
    private suspend fun transcodeInParallel(request: ImportRequest, output: File, report: Progress): RandomAccessSource {
        val parts = request.parts
        val workDir = output.parentFile!!
        // The most common input rate, so that most parts need no resampling.
        val sampleRate = parts.mapNotNull { it.sampleRate }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val channels = if (request.downmixToMono || parts.all { it.channels == 1 }) 1 else 2
        val weights = parts.map { it.durationMs.coerceAtLeast(1).toDouble() }
        val total = weights.sum()
        val progress = DoubleArray(parts.size)
        fun publish() = report.report(ImportStage.CONVERTING, (ENCODE_SHARE * progress.indices.sumOf { progress[it] * weights[it] } / total).toFloat())

        val files = parts.indices.map { File(workDir, "part-%04d.m4a".format(it)) }
        try {
            encodeParts(parts, files, request, sampleRate, channels, progress, ::publish)
        } catch (t: Throwable) {
            // Free the temporary space before a one-pass retry (or the error).
            files.forEach(File::delete)
            throw t
        }
        // The part files stay in the work folder until the import ends, which deletes it.
        join(openAll(files.map { { openFile(it) } }))?.let { return it }
        try {
            transcoder.run(files.map(Uri::fromFile), output, request.bitrateKbps, downmixToMono = false, transmux = true) { p ->
                report.report(ImportStage.CONVERTING, (ENCODE_SHARE + (1 - ENCODE_SHARE) * p).toFloat())
            }
        } catch (e: ExportException) {
            throw JoinFailed(e)
        } finally {
            files.forEach(File::delete)
        }
        return openFile(output)
    }

    private suspend fun encodeParts(
        parts: List<SourcePart>,
        files: List<File>,
        request: ImportRequest,
        sampleRate: Int?,
        channels: Int,
        progress: DoubleArray,
        publish: () -> Unit,
    ) {
        coroutineScope {
            parts.mapIndexed { i, part ->
                async {
                    withCodec {
                        try {
                            transcoder.run(
                                listOf(part.uri), files[i], request.bitrateKbps, downmixToMono = false, transmux = false,
                                outputSampleRate = sampleRate, outputChannels = channels,
                            ) { p ->
                                progress[i] = p.toDouble()
                                publish()
                            }
                        } catch (e: ExportException) {
                            // Out of space is reported as such by mapFailure; codec trouble is retried in
                            // one pass by convert(); anything else names the part.
                            throw when {
                                isOutOfSpace(e) -> e
                                e.errorCode in CODEC_ERRORS -> CodecTrouble(e)
                                else -> exportFailure(e, part.displayName)
                            }
                        }
                        progress[i] = 1.0
                        publish()
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun prepareTarget(treeUri: Uri, request: ImportRequest, ownFileUri: String?): Target {
        val path = LibraryLayout.pathFor(request.metadata, request.sourceName)
        var directoryId = documents.rootDocumentId(treeUri)
        for (name in path.directories) directoryId = documents.ensureDirectory(treeUri, directoryId, name)
        coroutineContext.ensureActive()
        val siblings = documents.children(treeUri, directoryId).filter { !it.isDirectory }
        val wanted = FileNameSanitizer.collisionKey(path.fileName)
        val clash = siblings.firstOrNull { FileNameSanitizer.collisionKey(it.name) == wanted }
        return when {
            clash == null -> Target(directoryId, path.directories, path.fileName, null)
            // Overwrite only with explicit confirmation, or when it is the edited book's own file.
            request.conflictPolicy == ConflictPolicy.REPLACE || clash.uri.toString() == ownFileUri ->
                Target(directoryId, path.directories, path.fileName, clash)
            else -> Target(
                directoryId, path.directories,
                LibraryLayout.uniqueFileName(path.fileName, siblings.map { it.name }), null,
            )
        }
    }

    private suspend fun write(source: RandomAccessSource, partial: Uri, spec: Mp4TagSpec, report: Progress) {
        val job = coroutineContext[Job]
        val cancellation = CancellationSignal { job?.isActive == false }
        report.report(ImportStage.WRITING, 0f)
        documents.openForWrite(partial).use { pfd ->
            val required = source.size + (spec.cover?.bytes?.size ?: 0) + 1_048_576L
            val free = documents.freeBytes(pfd)
            if (free != null && free < required + SAFETY_MARGIN) {
                throw ImportFailure.InsufficientStorage(required + SAFETY_MARGIN, free, ImportFailure.Location.LIBRARY)
            }
            FileOutputStream(pfd.fileDescriptor).use { out ->
                var lastReported = 0L
                val sink = ChannelSink(out.channel) { written ->
                    if (written - lastReported > required / 100) {
                        lastReported = written
                        report.report(ImportStage.WRITING, (written.toFloat() / required).coerceAtMost(1f))
                    }
                }
                Mp4TagWriter.write(source, sink, spec, cancellation)
                out.flush()
                pfd.fileDescriptor.sync()
            }
        }
        report.report(ImportStage.WRITING, 1f)
    }

    private suspend fun verify(partial: Uri, request: ImportRequest, chapters: List<Chapter>, expectedDurationMs: Long, report: Progress): String {
        report.report(ImportStage.VERIFYING, 0f)
        val job = coroutineContext[Job]
        val cancellation = CancellationSignal { job?.isActive == false }
        return documents.openSource(partial, "partial.m4b").use { src ->
            val parsed = try {
                AudioProbe.probe(src, "partial.m4b")
            } catch (e: Exception) {
                throw ImportFailure.VerificationFailed(e.message)
            }
            if (parsed.bookId != request.bookId) throw ImportFailure.VerificationFailed("id mismatch")
            if (parsed.stream?.codec != "aac") throw ImportFailure.VerificationFailed("codec ${parsed.stream?.codec}")
            val duration = parsed.durationMs ?: 0L
            val tolerance = maxOf(5_000L, expectedDurationMs / 50)
            if (duration <= 0 || abs(duration - expectedDurationMs) > tolerance) {
                throw ImportFailure.VerificationFailed("duration $duration, expected $expectedDurationMs")
            }
            if (chapters.isNotEmpty() && parsed.chapters.size != chapters.size) {
                throw ImportFailure.VerificationFailed("chapters ${parsed.chapters.size}/${chapters.size}")
            }
            val size = src.size.coerceAtLeast(1)
            sha256(src, cancellation) { read -> report.report(ImportStage.VERIFYING, read.toFloat() / size) }
        }
    }

    private fun rollback(tx: Transaction) {
        try {
            tx.partial?.let { documents.delete(it) }
            tx.partial = null
            if (!tx.dbWritten) {
                // Rename failed after the old book was moved aside, or the db write failed.
                tx.committed?.let { documents.delete(it) }
                if (tx.aside != null && tx.asideName != null) runCatching { documents.rename(tx.aside!!, tx.asideName!!) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Rollback incomplete", e)
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Decoders skip damaged frames instead of failing, which would silently shorten the book.
     * The converted audio must therefore last as long as the source files say they do.
     */
    private fun checkDecodedDuration(actualMs: Long?, expectedMs: Long) {
        if (expectedMs <= 0) return
        val tolerance = maxOf(10_000L, expectedMs * 3 / 100)
        if (actualMs == null || actualMs <= 0 || abs(actualMs - expectedMs) > tolerance) {
            throw ImportFailure.CorruptedInput(null, "decoded ${actualMs ?: 0} ms of $expectedMs ms")
        }
    }

    /** Stretches chapter starts so they match the real duration of the converted audio. */
    private fun alignChapters(chapters: List<Chapter>, actualMs: Long, expectedMs: Long): List<Chapter> {
        if (chapters.isEmpty() || actualMs <= 0 || expectedMs <= 0) return chapters
        val ratio = actualMs.toDouble() / expectedMs
        return chapters.mapIndexed { i, c ->
            val start = (c.startMs * ratio).toLong()
            val end = if (i == chapters.lastIndex) actualMs else (chapters[i + 1].startMs * ratio).toLong()
            c.copy(startMs = start, endMs = end)
        }
    }

    private fun mapFailure(t: Throwable, stage: ImportStage, request: ImportRequest): ImportFailure {
        if (t is ImportFailure) return t
        if (isOutOfSpace(t)) {
            val location = if (stage == ImportStage.CONVERTING) ImportFailure.Location.TEMPORARY else ImportFailure.Location.LIBRARY
            return ImportFailure.InsufficientStorage(request.estimatedOutputBytes, null, location)
        }
        return when (t) {
            is ExportException -> exportFailure(t, null, request.parts.firstOrNull()?.displayName)
            is UnsupportedFormatException -> ImportFailure.UnsupportedFormat(null, t.message)
            is CorruptedFileException -> ImportFailure.CorruptedInput(null, t.message)
            is NotSeekableException -> ImportFailure.SourceUnavailable(t.message ?: "", t)
            is SecurityException -> ImportFailure.LibraryUnavailable()
            is FileNotFoundException -> if (stage == ImportStage.PREPARING) ImportFailure.SourceUnavailable(t.message ?: "", t) else ImportFailure.WriteFailed(t.message, t)
            is IOException -> ImportFailure.WriteFailed(t.message, t)
            else -> ImportFailure.Unexpected(t.message ?: t.javaClass.simpleName, t)
        }
    }

    /** [fileName] is the part that failed, when known. */
    private fun exportFailure(t: ExportException, fileName: String?, fallbackName: String? = fileName): ImportFailure = when (t.errorCode) {
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND, ExportException.ERROR_CODE_IO_NO_PERMISSION ->
            ImportFailure.SourceUnavailable(fileName ?: fallbackName ?: "", t)
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
            ImportFailure.UnsupportedFormat(fileName, t.errorCodeName)
        // A codec error reaching here survived conversions in fresh processes (see RemoteTranscoder):
        // for a decoder that points at the file, for an encoder at the phone's codecs.
        ExportException.ERROR_CODE_DECODING_FAILED -> ImportFailure.CorruptedInput(fileName, t.errorCodeName)
        else -> if (isCodecFailure(t)) {
            ImportFailure.CodecFailure(listOfNotNull(t.errorCodeName, underlyingCause(t)).joinToString(": "), t)
        } else {
            ImportFailure.ConversionFailed(listOfNotNull(fileName, t.errorCodeName, underlyingCause(t)).joinToString(": "), t)
        }
    }

    /** The phone's codecs or the conversion process failed, not the file. */
    private fun isCodecFailure(t: ExportException): Boolean = when (val cause = t.cause) {
        is ConverterDiedException -> true
        is RemoteConversionError -> cause.codecFailed && t.errorCode in CODEC_ERRORS
        else -> false
    }

    /**
     * The codec's own error behind an [ExportException] (e.g. "CodecException: Error 0xe"), which
     * the error code alone doesn't tell; shown to the user so the problem can be reported.
     */
    private fun underlyingCause(t: ExportException): String? {
        var root: Throwable = t.cause ?: return null
        while (true) root = root.cause?.takeIf { it !== root } ?: break
        if (root is RemoteConversionError) {
            // Already "Class: message" for each cause, from the conversion process.
            val codec = if (root.codecFailed) " [${root.codecDiagnostic}, code ${root.codecErrorCode}]" else ""
            return (root.message.orEmpty().substringAfterLast(" ← ") + codec).take(240)
        }
        val codec = (root as? MediaCodec.CodecException)?.let { " [${it.diagnosticInfo}, code ${it.errorCode}]" }.orEmpty()
        return (root.javaClass.simpleName + (root.message?.let { " — $it" } ?: "") + codec).take(240)
    }

    private fun isOutOfSpace(t: Throwable): Boolean {
        var e: Throwable? = t
        while (e != null) {
            if (e is ErrnoException && e.errno == OsConstants.ENOSPC) return true
            val m = e.message ?: ""
            if (m.contains("ENOSPC") || m.contains("No space left", ignoreCase = true)) return true
            e = e.cause
        }
        return false
    }

    private class StageWeights(private val converts: Boolean) {
        fun overall(stage: ImportStage, fraction: Float?): Float? {
            val f = fraction ?: return when (stage) {
                ImportStage.FINISHING -> 0.99f
                ImportStage.DONE -> 1f
                else -> null
            }
            return if (converts) when (stage) {
                ImportStage.CONVERTING -> 0.75f * f
                ImportStage.WRITING -> 0.75f + 0.15f * f
                ImportStage.VERIFYING -> 0.90f + 0.09f * f
                ImportStage.DONE -> 1f
                else -> null
            } else when (stage) {
                ImportStage.WRITING -> 0.70f * f
                ImportStage.VERIFYING -> 0.70f + 0.29f * f
                ImportStage.DONE -> 1f
                else -> null
            }
        }
    }

    companion object {
        private const val TAG = "ImportPipeline"
        private const val SAFETY_MARGIN = 32L * 1024 * 1024

        /**
         * Encodes at the same time across all imports when set to automatic: half the CPU cores
         * (AAC encoding uses one core each), at least 2, at most 4 (codec instances and memory are
         * limited).
         */
        val AUTO_CODECS = maxOf(2, Runtime.getRuntime().availableProcessors() / 2).coerceAtMost(4)

        /** The codec limit for the "parallel codecs" setting (0 = automatic). */
        fun codecLimit(setting: Int): Int = if (setting <= 0) AUTO_CODECS else setting

        /** Codec errors that may come from running several codecs at once rather than from the file. */
        private val CODEC_ERRORS = setOf(
            ExportException.ERROR_CODE_ENCODING_FAILED,
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
            // The phone's media service dropping every codec at once shows up on the decoder too.
            ExportException.ERROR_CODE_DECODING_FAILED,
        )

        /** Share of the conversion progress spent encoding; the rest is joining. */
        private const val ENCODE_SHARE = 0.95
    }
}
