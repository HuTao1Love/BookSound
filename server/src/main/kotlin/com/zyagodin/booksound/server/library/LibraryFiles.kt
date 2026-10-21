package com.zyagodin.booksound.server.library

import com.zyagodin.booksound.core.io.ChannelSink
import com.zyagodin.booksound.core.io.FileChannelSource
import com.zyagodin.booksound.core.io.RandomAccessSource
import com.zyagodin.booksound.core.metadata.mp4.Mp4TagSpec
import com.zyagodin.booksound.core.metadata.mp4.Mp4TagWriter
import com.zyagodin.booksound.core.model.BookMetadata
import com.zyagodin.booksound.core.naming.LibraryLayout
import com.zyagodin.booksound.core.io.CancellationSignal
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The library folder: book files laid out as `Author/Series/NN - Title.m4b`, the same as on the phone. */
class LibraryFiles(val root: File) {

    /** The file at a path relative to the library; refuses paths that would leave it. */
    fun resolve(relativePath: String): File {
        val file = File(root, relativePath).canonicalFile
        val base = root.canonicalFile
        if (!file.path.startsWith(base.path + File.separator)) throw IOException("Path outside the library: $relativePath")
        return file
    }

    fun relativePathOf(file: File): String = file.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath

    /**
     * The file a book with [metadata] goes to: the layout's path, or "Title (2).m4b" when another file has that
     * name, unless that file is [own] (the book's current file, which is being rewritten).
     */
    fun targetFor(metadata: BookMetadata, fallbackTitle: String?, own: File?): File {
        val path = LibraryLayout.pathFor(metadata, fallbackTitle)
        val dir = path.directories.fold(root) { d, name -> File(d, name) }
        return stillFree(File(dir, path.fileName), own)
    }

    /** [target], or "Title (2).m4b" beside it when another file took that name meanwhile; [own] may be replaced. */
    fun stillFree(target: File, own: File?): File {
        val ownCanonical = own?.canonicalFile
        val siblings = target.parentFile.listFiles()?.filter { it.isFile && it.canonicalFile != ownCanonical }?.map { it.name }.orEmpty()
        return File(target.parentFile, LibraryLayout.uniqueFileName(target.name, siblings))
    }

    /** A hidden temporary file next to where the book will go, renamed into place once verified. */
    fun partialFor(target: File, jobId: String): File {
        target.parentFile.mkdirs()
        return File(target.parentFile, "${LibraryLayout.PARTIAL_PREFIX}$jobId.m4b")
    }

    /** Writes the tagged book to [out], flushing it to disk. */
    fun writeTagged(source: RandomAccessSource, out: File, spec: Mp4TagSpec, cancellation: CancellationSignal, onProgress: (Long) -> Unit = {}) {
        FileOutputStream(out).use { stream ->
            Mp4TagWriter.write(source, ChannelSink(stream.channel, onProgress), spec, cancellation)
            stream.flush()
            stream.fd.sync()
        }
    }

    /** Moves [partial] to [target], replacing it; then removes folders the old file left empty. */
    fun commit(partial: File, target: File, previous: File?) {
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        if (previous != null && previous.canonicalFile != target.canonicalFile) {
            previous.delete()
            pruneEmptyDirectories(previous.parentFile)
        }
    }

    fun pruneEmptyDirectories(from: File?) {
        var dir = from?.canonicalFile ?: return
        val base = root.canonicalFile
        while (dir != base && dir.path.startsWith(base.path) && dir.list()?.isEmpty() == true) {
            dir.delete()
            dir = dir.parentFile ?: return
        }
    }

    fun open(file: File): RandomAccessSource = FileChannelSource(FileInputStream(file).channel)
}
