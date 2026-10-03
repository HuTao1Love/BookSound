package com.zyagodin.booksound.importer

import android.net.Uri
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
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
    private val transcoder: AudioTranscoder,
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
                    checkTemporarySpace(workDir, treeUri, request)
                    val audio = File(workDir, "audio.m4a")
                    convert(request, audio, report)
                    val src = FileChannelSource(FileInputStream(audio).channel)
                    val actualDuration = runCatching { AudioProbe.probe(src, audio.name).durationMs }.getOrNull()
                    if (actualDuration != null) chapters = alignChapters(chapters, actualDuration, request.totalDurationMs)
                    src
                }
            }

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
            withContext(NonCancellable) {
                workDir.deleteRecursively()
                journal.remove(request.jobId)
            }
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

    private fun checkTemporarySpace(workDir: File, treeUri: Uri, request: ImportRequest) {
        val available = workDir.usableSpace
        val sameVolume = runCatching { documents.rootDocumentId(treeUri).startsWith("primary:") }.getOrDefault(true)
        // Temporary audio + (when the library is on the same volume) the final copy.
        val required = request.estimatedOutputBytes * (if (sameVolume) 2 else 1) + SAFETY_MARGIN
        if (available in 1 until required) {
            throw ImportFailure.InsufficientStorage(required, available, ImportFailure.Location.TEMPORARY)
        }
    }

    private suspend fun convert(request: ImportRequest, output: File, report: Progress) {
        val uris = request.parts.map { it.uri }
        report.report(ImportStage.CONVERTING, 0f)
        val copyFirst = request.strategy == ConversionStrategy.CONCAT_COPY
        try {
            transcoder.run(uris, output, request.bitrateKbps, request.downmixToMono, transmux = copyFirst) {
                report.report(ImportStage.CONVERTING, it)
            }
        } catch (e: ExportException) {
            if (!copyFirst) throw e
            // Stream copy can fail on unusual inputs; re-encoding is slower but robust.
            Log.i(TAG, "Concatenation without re-encoding failed, transcoding instead", e)
            output.delete()
            report.report(ImportStage.CONVERTING, 0f)
            transcoder.run(uris, output, request.bitrateKbps, request.downmixToMono, transmux = false) {
                report.report(ImportStage.CONVERTING, it)
            }
        }
        report.report(ImportStage.CONVERTING, 1f)
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
            is ExportException -> when (t.errorCode) {
                ExportException.ERROR_CODE_IO_FILE_NOT_FOUND, ExportException.ERROR_CODE_IO_NO_PERMISSION ->
                    ImportFailure.SourceUnavailable(request.parts.firstOrNull()?.displayName ?: "", t)
                ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED ->
                    ImportFailure.UnsupportedFormat(null, t.errorCodeName)
                ExportException.ERROR_CODE_DECODING_FAILED -> ImportFailure.CorruptedInput(null, t.errorCodeName)
                else -> ImportFailure.ConversionFailed(t.errorCodeName, t)
            }
            is UnsupportedFormatException -> ImportFailure.UnsupportedFormat(null, t.message)
            is CorruptedFileException -> ImportFailure.CorruptedInput(null, t.message)
            is NotSeekableException -> ImportFailure.SourceUnavailable(t.message ?: "", t)
            is SecurityException -> ImportFailure.LibraryUnavailable()
            is FileNotFoundException -> if (stage == ImportStage.PREPARING) ImportFailure.SourceUnavailable(t.message ?: "", t) else ImportFailure.WriteFailed(t.message, t)
            is IOException -> ImportFailure.WriteFailed(t.message, t)
            else -> ImportFailure.Unexpected(t.message ?: t.javaClass.simpleName, t)
        }
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
    }
}
