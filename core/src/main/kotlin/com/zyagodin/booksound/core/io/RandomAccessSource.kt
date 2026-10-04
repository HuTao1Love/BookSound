package com.zyagodin.booksound.core.io

import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Positional, read-only access to a file. Implemented over a [FileChannel] on both Android
 * (from a ParcelFileDescriptor) and the JVM, and over byte arrays in tests.
 */
interface RandomAccessSource : Closeable {
    val size: Long

    /** Reads up to [length] bytes at [position]. Returns the number of bytes read, or -1 at EOF. */
    fun read(position: Long, buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset): Int

    /** Copies [length] bytes starting at [position] into [sink]. */
    fun copyTo(position: Long, length: Long, sink: ByteSink, cancellation: CancellationSignal = CancellationSignal.NONE) {
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        var copied = 0L
        while (copied < length) {
            cancellation.throwIfCancelled()
            val toRead = minOf(buffer.size.toLong(), length - copied).toInt()
            val read = read(position + copied, buffer, 0, toRead)
            if (read <= 0) throw EOFException("Unexpected end of file at ${position + copied}")
            sink.write(buffer, 0, read)
            copied += read
        }
    }

    companion object {
        const val COPY_BUFFER_SIZE = 1 shl 20
    }
}

fun RandomAccessSource.readFully(position: Long, length: Int): ByteArray {
    require(length >= 0) { "Negative length" }
    if (position < 0 || position + length > size) {
        throw EOFException("Range $position+$length is outside the file (size $size)")
    }
    val out = ByteArray(length)
    var done = 0
    while (done < length) {
        val read = read(position + done, out, done, length - done)
        if (read <= 0) throw EOFException("Unexpected end of file at ${position + done}")
        done += read
    }
    return out
}

/** Reads as many bytes as available up to [length]. */
fun RandomAccessSource.readUpTo(position: Long, length: Int): ByteArray {
    val available = (size - position).coerceAtLeast(0).coerceAtMost(length.toLong()).toInt()
    return if (available == 0) ByteArray(0) else readFully(position, available)
}

class FileChannelSource(
    private val channel: FileChannel,
    private val onClose: () -> Unit = {},
) : RandomAccessSource {
    override val size: Long = channel.size()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        return channel.read(ByteBuffer.wrap(buffer, offset, length), position)
    }

    override fun copyTo(position: Long, length: Long, sink: ByteSink, cancellation: CancellationSignal) {
        if (sink is ChannelSink) {
            var copied = 0L
            while (copied < length) {
                cancellation.throwIfCancelled()
                val chunk = minOf(RandomAccessSource.COPY_BUFFER_SIZE.toLong() * 8, length - copied)
                val transferred = channel.transferTo(position + copied, chunk, sink.channel)
                if (transferred <= 0) {
                    // transferTo may legitimately return 0 on some channels; fall back to buffered copy.
                    super.copyTo(position + copied, length - copied, sink, cancellation)
                    return
                }
                sink.onBytesWritten(transferred)
                copied += transferred
            }
        } else {
            super.copyTo(position, length, sink, cancellation)
        }
    }

    override fun close() {
        try {
            channel.close()
        } finally {
            onClose()
        }
    }
}

class ByteArraySource(private val data: ByteArray) : RandomAccessSource {
    override val size: Long get() = data.size.toLong()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= data.size) return -1
        val n = minOf(length.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, n)
        return n
    }

    override fun close() = Unit
}

/** Cooperative cancellation that works without depending on kotlinx.coroutines. */
fun interface CancellationSignal {
    fun isCancelled(): Boolean

    fun throwIfCancelled() {
        if (isCancelled()) throw OperationCancelledException()
    }

    companion object {
        val NONE = CancellationSignal { false }
    }
}

class OperationCancelledException : IOException("Operation cancelled")
