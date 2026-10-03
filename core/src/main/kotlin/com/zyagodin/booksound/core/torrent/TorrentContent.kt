package com.zyagodin.booksound.core.torrent

import com.zyagodin.booksound.core.naming.NaturalOrder

/** One file listed in a torrent's metadata, before anything is downloaded. */
data class TorrentFile(
    /** Index of the file inside the torrent (used for file priorities). */
    val index: Int,
    /** Path inside the torrent, '/'-separated, including the torrent's root folder if it has one. */
    val path: String,
    val sizeBytes: Long,
    /** BEP 47 padding file: never downloaded, never shown. */
    val isPadding: Boolean = false,
) {
    val name: String get() = path.substringAfterLast('/')
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
}

enum class TorrentFileKind {
    MP3,
    M4B,
    IMAGE,

    /** Harmless companion files (playlists, descriptions, checksums, OS junk). Not downloaded. */
    EXTRA,
    PADDING,

    /** Anything else: other audio/video formats, archives, executables, unknown files. */
    FOREIGN,
}

/** The two shapes of audiobook a torrent may contain. */
enum class AudiobookLayout {
    /** One or more MP3 files (optionally in sub-folders such as CD1, CD2), plus optional images. */
    MP3_PARTS,

    /** Exactly one M4B file, plus optional images. */
    SINGLE_M4B,
}

enum class TorrentContentProblem {
    /** The torrent lists no (non-padding) files. */
    EMPTY,

    /** A file path escapes the download folder ("..", absolute paths). */
    UNSAFE_PATH,

    /** Files that are neither MP3, M4B, images nor known harmless extras. */
    UNSUPPORTED_FILES,

    /** Neither MP3 nor M4B files. */
    NO_AUDIO,

    /** MP3 and M4B files together: it is unclear which one is the book. */
    MIXED_FORMATS,

    /** Several M4B files: usually several books in one torrent. */
    MULTIPLE_M4B,

    /** An audio file with a size of zero bytes. */
    EMPTY_AUDIO_FILE,
}

sealed interface TorrentContentCheck {
    data class Valid(
        val layout: AudiobookLayout,
        /** Audio files in natural path order. */
        val audio: List<TorrentFile>,
        /** Images to download as cover candidates. */
        val images: List<TorrentFile>,
        /** Files that are not downloaded (extras, padding, oversized images). */
        val skipped: List<TorrentFile>,
    ) : TorrentContentCheck {
        val wantedIndices: Set<Int> get() = (audio + images).mapTo(HashSet()) { it.index }
        val audioBytes: Long get() = audio.sumOf { it.sizeBytes }
        val wantedBytes: Long get() = audio.sumOf { it.sizeBytes } + images.sumOf { it.sizeBytes }
    }

    data class Invalid(
        val problem: TorrentContentProblem,
        /** Paths of the files that caused the problem (may be empty). */
        val files: List<String> = emptyList(),
    ) : TorrentContentCheck
}

/**
 * Decides from the torrent's file list alone — before downloading anything — whether it is a
 * single audiobook BookSound can import: a folder of MP3 files with an optional cover image, or a
 * single M4B file. Harmless extras (.nfo, .txt, .cue, playlists, OS junk) are allowed but skipped;
 * anything else rejects the torrent so unexpected content is never downloaded.
 */
object TorrentContentValidator {

    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    /** Companion files commonly found next to audiobooks; ignored, never downloaded. */
    val EXTRA_EXTENSIONS = setOf(
        "txt", "nfo", "diz", "cue", "m3u", "m3u8", "pls", "wpl", "log", "sfv", "md5", "sha1", "sha256", "ffp", "accurip",
        "url", "website", "webloc", "pdf", "epub", "fb2", "mobi", "djvu", "doc", "docx", "rtf", "odt", "htm", "html",
        "xml", "json", "ini", "db", "srt", "lrc", "gif", "bmp", "tif", "tiff", "torrent", "md",
    )

    /** OS metadata files that look like media by name but are not (e.g. "._01.mp3" AppleDouble files). */
    private val JUNK_NAMES = setOf("thumbs.db", "desktop.ini", ".ds_store")
    private const val JUNK_FOLDER = "__MACOSX"

    /** Larger images are not downloaded; covers are never that big. */
    const val MAX_IMAGE_BYTES = 15L * 1024 * 1024

    fun kindOf(file: TorrentFile): TorrentFileKind {
        if (file.isPadding) return TorrentFileKind.PADDING
        val segments = file.path.split('/')
        if (segments.any { it == JUNK_FOLDER || it == ".pad" }) return TorrentFileKind.PADDING
        val name = file.name
        if (name.startsWith(".") || name.lowercase() in JUNK_NAMES) return TorrentFileKind.EXTRA
        return when (val ext = file.extension) {
            "mp3" -> TorrentFileKind.MP3
            "m4b" -> TorrentFileKind.M4B
            in IMAGE_EXTENSIONS -> TorrentFileKind.IMAGE
            in EXTRA_EXTENSIONS -> TorrentFileKind.EXTRA
            else -> if (ext.isEmpty() && file.sizeBytes == 0L) TorrentFileKind.EXTRA else TorrentFileKind.FOREIGN
        }
    }

    fun validate(files: List<TorrentFile>): TorrentContentCheck {
        val real = files.filter { kindOf(it) != TorrentFileKind.PADDING }
        if (real.isEmpty()) return TorrentContentCheck.Invalid(TorrentContentProblem.EMPTY)

        val unsafe = real.filter { !isSafePath(it.path) }
        if (unsafe.isNotEmpty()) return TorrentContentCheck.Invalid(TorrentContentProblem.UNSAFE_PATH, unsafe.map { it.path })

        val byKind = real.groupBy(::kindOf)
        val foreign = byKind[TorrentFileKind.FOREIGN].orEmpty()
        if (foreign.isNotEmpty()) {
            return TorrentContentCheck.Invalid(TorrentContentProblem.UNSUPPORTED_FILES, foreign.map { it.path }.sortedWith(NaturalOrder))
        }
        val mp3 = byKind[TorrentFileKind.MP3].orEmpty()
        val m4b = byKind[TorrentFileKind.M4B].orEmpty()
        val layout = when {
            mp3.isEmpty() && m4b.isEmpty() -> return TorrentContentCheck.Invalid(TorrentContentProblem.NO_AUDIO)
            mp3.isNotEmpty() && m4b.isNotEmpty() ->
                return TorrentContentCheck.Invalid(TorrentContentProblem.MIXED_FORMATS, m4b.map { it.path } + mp3.take(3).map { it.path })
            m4b.size > 1 -> return TorrentContentCheck.Invalid(TorrentContentProblem.MULTIPLE_M4B, m4b.map { it.path }.sortedWith(NaturalOrder))
            m4b.size == 1 -> AudiobookLayout.SINGLE_M4B
            else -> AudiobookLayout.MP3_PARTS
        }
        val audio = (mp3 + m4b).sortedWith { a, b -> NaturalOrder.compare(a.path, b.path) }
        val empty = audio.filter { it.sizeBytes <= 0 }
        if (empty.isNotEmpty()) return TorrentContentCheck.Invalid(TorrentContentProblem.EMPTY_AUDIO_FILE, empty.map { it.path })

        val (images, bigImages) = byKind[TorrentFileKind.IMAGE].orEmpty().partition { it.sizeBytes in 1..MAX_IMAGE_BYTES }
        val skipped = byKind[TorrentFileKind.EXTRA].orEmpty() + bigImages
        return TorrentContentCheck.Valid(layout, audio, images.sortedWith { a, b -> NaturalOrder.compare(a.path, b.path) }, skipped)
    }

    private fun isSafePath(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/") || path.startsWith("\\")) return false
        if (path.length >= 2 && path[1] == ':') return false // "C:\..."
        return path.split('/', '\\').none { it == ".." || it == "." }
    }
}
