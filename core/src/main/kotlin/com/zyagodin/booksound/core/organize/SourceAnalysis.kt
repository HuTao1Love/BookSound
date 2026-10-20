package com.zyagodin.booksound.core.organize

import com.zyagodin.booksound.core.io.RandomAccessSource
import com.zyagodin.booksound.core.metadata.AudioProbe
import com.zyagodin.booksound.core.metadata.CorruptedFileException
import com.zyagodin.booksound.core.metadata.UnsupportedFormatException
import com.zyagodin.booksound.core.model.EmbeddedPicture
import java.io.IOException

/** A file that was found but cannot be imported, shown to the user with the reason. */
data class SkippedFile(val name: String, val reason: SkipReason, val detail: String? = null)

enum class SkipReason { UNSUPPORTED, CORRUPTED, UNREADABLE }

/** A file found in an import source, before it is read. [id] is opaque (a content URI, a path). */
data class SourceCandidate(val id: String, val name: String, val relativeDir: List<String>, val sizeBytes: Long)

sealed interface SourceAnalysis {
    data class Ready(val draft: ImportDraft, val files: List<ImportSourceFile>, val skipped: List<SkippedFile>) : SourceAnalysis
    data class NothingReadable(val skipped: List<SkippedFile>) : SourceAnalysis
    data object NoAudio : SourceAnalysis
}

/** Reads every audio file of an import source and builds its [ImportDraft]. */
object SourceAnalyzer {
    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    const val MAX_IMAGE_BYTES = 15L * 1024 * 1024
    const val MAX_FOLDER_IMAGES = 6

    fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    /** Audio by extension, including formats that are then reported as unsupported. */
    fun isAudioName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AudioProbe.SUPPORTED_EXTENSIONS || ext in AudioProbe.KNOWN_UNSUPPORTED_EXTENSIONS
    }

    /**
     * [open] opens an audio file for reading; [readImage] reads an image of at most the given size, or null.
     * [beforeFile] is called before each audio file (with its index) and may throw to cancel. A failure to read
     * one file skips it; anything [open] throws other than an [IOException] or [SecurityException] propagates.
     */
    fun analyze(
        audio: List<SourceCandidate>,
        images: List<SourceCandidate>,
        sourceName: String,
        open: (SourceCandidate) -> RandomAccessSource,
        readImage: (SourceCandidate, Int) -> ByteArray?,
        untitledPart: (Int) -> String = { "Chapter $it" },
        onFileFailed: (SourceCandidate, Throwable) -> Unit = { _, _ -> },
        beforeFile: (Int, SourceCandidate) -> Unit = { _, _ -> },
    ): SourceAnalysis {
        if (audio.isEmpty()) return SourceAnalysis.NoAudio
        val parsed = mutableListOf<ImportSourceFile>()
        val skipped = mutableListOf<SkippedFile>()
        audio.forEachIndexed { i, c ->
            beforeFile(i, c)
            try {
                val result = open(c).use { AudioProbe.probe(it, c.name) }
                parsed += ImportSourceFile(c.id, c.name, c.relativeDir, c.sizeBytes, result)
            } catch (e: UnsupportedFormatException) {
                skipped += SkippedFile(c.name, SkipReason.UNSUPPORTED, e.message)
            } catch (e: CorruptedFileException) {
                skipped += SkippedFile(c.name, SkipReason.CORRUPTED, e.message)
            } catch (e: IOException) {
                skipped += SkippedFile(c.name, SkipReason.UNREADABLE, e.message)
            } catch (e: SecurityException) {
                skipped += SkippedFile(c.name, SkipReason.UNREADABLE, e.message)
            } catch (e: RuntimeException) {
                if (isCancellation(e)) throw e
                onFileFailed(c, e)
                skipped += SkippedFile(c.name, SkipReason.CORRUPTED, e.message)
            }
        }
        if (parsed.isEmpty()) return SourceAnalysis.NothingReadable(skipped)

        val folderImages = images
            .filter { it.sizeBytes in 1..MAX_IMAGE_BYTES }
            .take(MAX_FOLDER_IMAGES)
            .mapNotNull { img ->
                val bytes = readImage(img, MAX_IMAGE_BYTES.toInt()) ?: return@mapNotNull null
                val mime = EmbeddedPicture.sniffMimeType(bytes) ?: return@mapNotNull null
                img.name to EmbeddedPicture(bytes, mime)
            }
        val draft = ImportDraftBuilder.build(sourceName, parsed, folderImages, untitledPart)
        return SourceAnalysis.Ready(draft, parsed, skipped)
    }

    // Coroutine cancellation is an IllegalStateException; :core has no coroutines dependency to check the type.
    private fun isCancellation(e: RuntimeException) = e.javaClass.name.endsWith("CancellationException")
}
