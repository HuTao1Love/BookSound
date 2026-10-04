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

    /** Several M4B files in one book (e.g. in "CD1", "CD2" folders): unclear which one is the book. */
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
        /**
         * Name of the book inside a [Collection] (its folder, or its file name for a book that is a
         * single M4B), used to suggest its title. Null when the torrent is one book.
         */
        val volume: String? = null,
    ) : TorrentContentCheck {
        val wantedIndices: Set<Int> get() = (audio + images).mapTo(HashSet()) { it.index }
        val audioBytes: Long get() = audio.sumOf { it.sizeBytes }
        val wantedBytes: Long get() = audio.sumOf { it.sizeBytes } + images.sumOf { it.sizeBytes }
    }

    /** Several books in one torrent (MP3 folders "Series/Book 1", "Series/Book 2", or several M4B files). */
    data class Collection(
        /** The books in natural path order; images outside every book folder are shared by all of them. */
        val books: List<Valid>,
        val skipped: List<TorrentFile>,
    ) : TorrentContentCheck {
        val wantedIndices: Set<Int> get() = books.flatMapTo(HashSet()) { it.wantedIndices }
    }

    data class Invalid(
        val problem: TorrentContentProblem,
        /** Paths of the files that caused the problem (may be empty). */
        val files: List<String> = emptyList(),
    ) : TorrentContentCheck
}

/**
 * Decides from the torrent's file list alone — before downloading anything — whether it holds
 * audiobooks BookSound can import: a folder of MP3 files with an optional cover image, a single
 * M4B file, or several such books side by side (see [TorrentContentCheck.Collection]). Harmless
 * extras (.nfo, .txt, .cue, playlists, OS junk) are allowed but skipped; anything else rejects the
 * torrent so unexpected content is never downloaded.
 *
 * Sub-folders below the folder that holds all audio are separate books ("Book 1", "Том 2",
 * "01. Title"), unless one of them is named like a disc or part of one book ("CD1", "Disc 2",
 * "Часть 3", "02"): then the whole torrent is one book.
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
        if (mp3.isEmpty() && m4b.isEmpty()) return TorrentContentCheck.Invalid(TorrentContentProblem.NO_AUDIO)
        val audio = (mp3 + m4b).sortedWith(byPath)
        val empty = audio.filter { it.sizeBytes <= 0 }
        if (empty.isNotEmpty()) return TorrentContentCheck.Invalid(TorrentContentProblem.EMPTY_AUDIO_FILE, empty.map { it.path })

        val (images, bigImages) = byKind[TorrentFileKind.IMAGE].orEmpty().sortedWith(byPath).partition { it.sizeBytes in 1..MAX_IMAGE_BYTES }
        val skipped = byKind[TorrentFileKind.EXTRA].orEmpty() + bigImages
        val groups = splitBooks(audio)
        groups.firstOrNull { g -> g.files.any { it.extension == "m4b" } && g.files.any { it.extension == "mp3" } }?.let { g ->
            val (groupM4b, groupMp3) = g.files.partition { it.extension == "m4b" }
            return TorrentContentCheck.Invalid(TorrentContentProblem.MIXED_FORMATS, groupM4b.map { it.path } + groupMp3.take(3).map { it.path })
        }
        groups.firstOrNull { it.layout == AudiobookLayout.SINGLE_M4B && it.files.size > 1 }?.let { g ->
            return TorrentContentCheck.Invalid(TorrentContentProblem.MULTIPLE_M4B, g.files.map { it.path })
        }
        if (groups.size == 1) return TorrentContentCheck.Valid(groups.single().layout, audio, images, skipped)
        val books = groups.map { g ->
            // The book's own images first: they are the better cover candidates.
            val (own, shared) = images.filter { g.owns(it, groups) }.partition { g.isOwner(it, groups) }
            TorrentContentCheck.Valid(g.layout, g.files, own + shared, emptyList(), g.name)
        }
        return TorrentContentCheck.Collection(books, skipped)
    }

    /** The audio files of one book: everything below [folder], or the single M4B [file]. */
    private class BookGroup(val name: String, val folder: String?, val file: TorrentFile?, val files: List<TorrentFile>) {
        val layout: AudiobookLayout get() = if (files.first().extension == "m4b") AudiobookLayout.SINGLE_M4B else AudiobookLayout.MP3_PARTS

        /**
         * An image belongs to the book whose folder it is in, or to the M4B of the same name next
         * to it ("2.jpg" beside "2.m4b"). Images that belong to no book are shared by all of them.
         */
        fun owns(image: TorrentFile, all: List<BookGroup>): Boolean = ownerOf(image, all).let { it == null || it === this }

        fun isOwner(image: TorrentFile, all: List<BookGroup>): Boolean = ownerOf(image, all) === this

        private fun ownerOf(image: TorrentFile, all: List<BookGroup>): BookGroup? =
            all.firstOrNull { it.folder != null && image.path.startsWith(it.folder + "/") }
                ?: all.firstOrNull { it.file != null && it.file.dir == image.dir && stem(it.file.name).equals(stem(image.name), ignoreCase = true) }
    }

    private fun splitBooks(audio: List<TorrentFile>): List<BookGroup> {
        val one = listOf(BookGroup("", null, null, audio))
        if (audio.size < 2) return one
        // The deepest folder that holds all audio files.
        val base = audio.map { it.dir.split('/') }.reduce { acc, dir -> acc.zip(dir).takeWhile { (a, b) -> a == b }.map { it.first } }
            .joinToString("/")
        val prefix = if (base.isEmpty()) "" else "$base/"
        val (loose, nested) = audio.partition { it.dir == base }
        val groups = when {
            // Files side by side: several M4B files are several books, MP3 files are parts of one.
            nested.isEmpty() -> if (loose.all { it.extension == "m4b" }) loose.map(::singleFileBook) else return one
            // Parts of the book next to sub-folders: one book.
            loose.isNotEmpty() -> return one
            else -> {
                val byFolder = nested.groupBy { it.path.removePrefix(prefix).substringBefore('/') }
                if (byFolder.keys.any(::isPartOfOneBook)) return one
                byFolder.map { (folder, files) -> BookGroup(folder, prefix + folder, null, files) }
            }
        }
        // A book folder holding nothing but several M4B files holds several books.
        return groups.flatMap { g ->
            if (g.folder != null && g.files.size > 1 && g.files.all { it.extension == "m4b" }) g.files.map(::singleFileBook) else listOf(g)
        }
    }

    private fun singleFileBook(file: TorrentFile) = BookGroup(stem(file.name), null, file, listOf(file))

    /** "CD1", "Disc 02 (tracks 1-12)", "Диск 3", "Часть 2", "Part 1", "02": a piece of one book, not a book of its own. */
    fun isPartOfOneBook(folder: String): Boolean {
        val name = folder.trim()
        return DISC_FOLDER.matches(name) || PART_FOLDER.matches(name) || NUMBER_FOLDER.matches(name)
    }

    private val DISC_FOLDER = Regex("""(?:cd|disc|disk|диск|сд)\s*[-_.#№]?\s*\d{1,3}(?![\p{L}\p{N}]).*""", RegexOption.IGNORE_CASE)
    private val PART_FOLDER = Regex("""(?:part|pt\.?|часть|ч\.)\s*[-_.#№]?\s*\d{1,3}[^\p{L}]*""", RegexOption.IGNORE_CASE)
    private val NUMBER_FOLDER = Regex("""\d{1,3}""")

    private val byPath = Comparator<TorrentFile> { a, b -> NaturalOrder.compare(a.path, b.path) }

    private val TorrentFile.dir: String get() = path.substringBeforeLast('/', "")

    private fun stem(name: String) = name.substringBeforeLast('.', name)

    private fun isSafePath(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/") || path.startsWith("\\")) return false
        if (path.length >= 2 && path[1] == ':') return false // "C:\..."
        return path.split('/', '\\').none { it == ".." || it == "." }
    }
}
