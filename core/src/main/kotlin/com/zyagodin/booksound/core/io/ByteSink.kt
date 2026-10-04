package com.zyagodin.booksound.core.io

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel
import java.security.MessageDigest

/** Sequential output that tracks how many bytes were written. */
interface ByteSink {
    val bytesWritten: Long
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset)
}

class ChannelSink(
    val channel: WritableByteChannel,
    private val onProgress: (Long) -> Unit = {},
) : ByteSink {
    override var bytesWritten: Long = 0
        private set

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        val buffer = ByteBuffer.wrap(bytes, offset, length)
        while (buffer.hasRemaining()) channel.write(buffer)
        onBytesWritten(length.toLong())
    }

    internal fun onBytesWritten(count: Long) {
        bytesWritten += count
        onProgress(bytesWritten)
    }
}

class StreamSink(private val stream: OutputStream) : ByteSink {
    override var bytesWritten: Long = 0
        private set

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        stream.write(bytes, offset, length)
        bytesWritten += length
    }
}

/** Computes a SHA-256 over everything readable from [source]. */
fun sha256(source: RandomAccessSource, cancellation: CancellationSignal = CancellationSignal.NONE, onProgress: (Long) -> Unit = {}): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(RandomAccessSource.COPY_BUFFER_SIZE)
    var position = 0L
    while (position < source.size) {
        cancellation.throwIfCancelled()
        val read = source.read(position, buffer, 0, buffer.size)
        if (read <= 0) break
        digest.update(buffer, 0, read)
        position += read
        onProgress(position)
    }
    return digest.digest().toHex()
}

fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        chars[i * 2] = HEX[v ushr 4]
        chars[i * 2 + 1] = HEX[v and 0x0F]
    }
    return String(chars)
}

private val HEX = "0123456789abcdef".toCharArray()
