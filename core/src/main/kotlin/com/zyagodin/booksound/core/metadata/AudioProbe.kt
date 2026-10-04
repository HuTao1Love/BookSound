package com.zyagodin.booksound.core.metadata

import com.zyagodin.booksound.core.io.RandomAccessSource
import com.zyagodin.booksound.core.io.readUpTo
import com.zyagodin.booksound.core.metadata.id3.Id3Reader
import com.zyagodin.booksound.core.metadata.mp3.Mp3Reader
import com.zyagodin.booksound.core.metadata.mp4.Mp4Parser
import com.zyagodin.booksound.core.metadata.mp4.Mp4Reader
import java.io.IOException

/** Format detection and metadata extraction for supported audiobook inputs. */
object AudioProbe {

    val SUPPORTED_EXTENSIONS = setOf("m4b", "m4a", "mp4", "aac", "mp3")

    /** Extensions that are audio but not supported; used to produce a helpful message. */
    val KNOWN_UNSUPPORTED_EXTENSIONS = setOf("flac", "ogg", "opus", "wma", "wav", "ape", "aax", "aaxc", "mka", "webm")

    fun isSupportedName(fileName: String): Boolean = fileName.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS

    /**
     * Parses [source]. Throws [UnsupportedFormatException] for unknown formats and
     * [CorruptedFileException] for damaged files; I/O errors propagate as [IOException].
     */
    fun probe(source: RandomAccessSource, fileName: String): ParsedAudioFile {
        if (source.size == 0L) throw CorruptedFileException("File is empty")
        val header = source.readUpTo(0, 16)
        return when {
            Mp4Reader.looksLikeMp4(header) -> Mp4Parser.parse(source)
            Mp3Reader.looksLikeMp3(header) -> probeMp3(source)
            else -> {
                val ext = fileName.substringAfterLast('.', "").lowercase()
                when {
                    ext == "aax" || ext == "aaxc" -> throw UnsupportedFormatException("DRM protected Audible files (.$ext) are not supported")
                    ext in KNOWN_UNSUPPORTED_EXTENSIONS -> throw UnsupportedFormatException("The .$ext format is not supported")
                    ext in SUPPORTED_EXTENSIONS -> throw CorruptedFileException("File content does not match its .$ext extension")
                    else -> throw UnsupportedFormatException("Unrecognized file format")
                }
            }
        }
    }

    private fun probeMp3(source: RandomAccessSource): ParsedAudioFile {
        val id3 = Id3Reader.read(source)
        val info = Mp3Reader.read(source, id3.audioStart, source.size - id3.trailingTagBytes)
        return ParsedAudioFile(
            container = AudioContainer.MP3,
            stream = AudioStreamInfo("mp3", info.sampleRate, info.channels, info.bitrate),
            durationMs = info.durationMs,
            tags = id3.tags,
            chapters = emptyList(),
            cover = id3.cover,
            warnings = id3.warnings,
            rewritable = false,
        )
    }
}
