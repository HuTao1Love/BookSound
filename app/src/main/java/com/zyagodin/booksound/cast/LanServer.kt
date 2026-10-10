package com.zyagodin.booksound.cast

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A tiny HTTP server that lends files to devices on the same Wi-Fi network, e.g. a book to a
 * Chromecast. Each file gets an unguessable link; GET and HEAD with byte ranges, so the device
 * can seek. Only shared files are served, and only while the server runs.
 */
class LanServer(private val context: Context) {

    /** Something to serve: [open] gives a stream positioned at the start of the file. */
    private class Shared(val mimeType: String, val size: Long, val open: () -> InputStream)

    private val shared = ConcurrentHashMap<String, Shared>()
    private val random = SecureRandom()
    @Volatile private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null

    /**
     * Shares a document or file [uri] and returns its link, or null without a Wi-Fi (or other
     * local) network address to give out.
     */
    @Synchronized
    fun share(uri: Uri, mimeType: String, size: Long, name: String): String? {
        val host = localAddress() ?: return null
        val server = socket ?: start() ?: return null
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val resolvedSize = size.takeIf { it > 0 } ?: sizeOf(uri)
        shared["/$token/$name"] = Shared(mimeType, resolvedSize) {
            context.contentResolver.openInputStream(uri) ?: throw IOException("Can't open $uri")
        }
        return "http://${host.hostAddress}:${server.localPort}/$token/$name"
    }

    /** Forgets every shared file and stops listening. */
    @Synchronized
    fun stop() {
        shared.clear()
        runCatching { socket?.close() }
        socket = null
        pool?.shutdownNow()
        pool = null
    }

    private fun start(): ServerSocket? = try {
        val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
        }
        val executor = Executors.newCachedThreadPool { r -> Thread(r, "LanServer").apply { isDaemon = true } }
        executor.execute { acceptLoop(server, executor) }
        socket = server
        pool = executor
        server
    } catch (e: IOException) {
        Log.w(TAG, "Can't start the server", e)
        null
    }

    private fun acceptLoop(server: ServerSocket, executor: ExecutorService) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: SocketException) {
                break // closed by stop()
            } catch (e: IOException) {
                Log.w(TAG, "Accept failed", e)
                continue
            }
            runCatching { executor.execute { handle(client) } }.onFailure { client.close() }
        }
    }

    private fun handle(client: Socket) = client.use {
        try {
            it.soTimeout = READ_TIMEOUT_MS
            val input = BufferedInputStream(it.getInputStream())
            val output = it.getOutputStream()
            val requestLine = readLine(input) ?: return
            val headers = generateSequence { readLine(input)?.takeIf { line -> line.isNotEmpty() } }
                .mapNotNull { line -> line.split(':', limit = 2).takeIf { p -> p.size == 2 }?.let { p -> p[0].trim().lowercase() to p[1].trim() } }
                .toMap()
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1)?.substringBefore('?').orEmpty()
            val file = shared[path]
            when {
                method != "GET" && method != "HEAD" -> respond(output, "405 Method Not Allowed")
                file == null -> respond(output, "404 Not Found")
                else -> serve(output, file, headers["range"], body = method == "GET")
            }
        } catch (e: IOException) {
            // The device closed the connection, typically after reading what it needed.
        }
    }

    private fun serve(output: OutputStream, file: Shared, range: String?, body: Boolean) {
        val size = file.size
        val requested = range?.let { parseRange(it, size) }
        if (range != null && requested == null) {
            respond(output, "416 Range Not Satisfiable", "Content-Range: bytes */$size")
            return
        }
        val (start, end) = requested ?: (0L to size - 1)
        val length = (end - start + 1).coerceAtLeast(0)
        val head = buildList {
            add(if (requested != null) "HTTP/1.1 206 Partial Content" else "HTTP/1.1 200 OK")
            add("Content-Type: ${file.mimeType}")
            add("Content-Length: $length")
            add("Accept-Ranges: bytes")
            if (requested != null) add("Content-Range: bytes $start-$end/$size")
            add("Access-Control-Allow-Origin: *")
            add("Connection: close")
        }.joinToString("\r\n", postfix = "\r\n\r\n")
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        if (body && length > 0) {
            file.open().use { input ->
                skipFully(input, start)
                val buffer = ByteArray(BUFFER_SIZE)
                var left = length
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    left -= n
                }
            }
        }
        output.flush()
    }

    private fun respond(output: OutputStream, status: String, extra: String? = null) {
        val head = listOfNotNull("HTTP/1.1 $status", extra, "Content-Length: 0", "Connection: close").joinToString("\r\n", postfix = "\r\n\r\n")
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    /** The IPv4 address of the current network (Wi-Fi, Ethernet or the phone's own hotspot). */
    private fun localAddress(): Inet4Address? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        return connectivity.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
    }

    private fun sizeOf(uri: Uri): Long =
        runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } }.getOrNull() ?: -1L

    internal companion object {
        private const val TAG = "LanServer"
        private const val BUFFER_SIZE = 64 * 1024
        private const val READ_TIMEOUT_MS = 30_000

        /** "bytes=100-", "bytes=100-199" or "bytes=-500" → inclusive (start, end), or null. */
        fun parseRange(header: String, size: Long): Pair<Long, Long>? {
            val spec = header.removePrefix("bytes=").substringBefore(',').trim()
            val (from, to) = spec.split('-', limit = 2).takeIf { it.size == 2 } ?: return null
            val range = when {
                from.isEmpty() -> to.toLongOrNull()?.let { suffix -> (size - suffix).coerceAtLeast(0) to size - 1 }
                else -> from.toLongOrNull()?.let { start -> start to (to.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)) }
            }
            return range?.takeIf { (start, end) -> start in 0..end && end < size }
        }

        private fun readLine(input: InputStream): String? {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0) return line.takeIf { it.isNotEmpty() }?.toString()
                if (c == '\n'.code) return line.toString().trimEnd('\r')
                if (line.length > 8 * 1024) throw IOException("Header too long")
                line.append(c.toChar())
            }
        }

        private fun skipFully(input: InputStream, count: Long) {
            if (input is FileInputStream) {
                input.channel.position(count)
                return
            }
            var left = count
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped <= 0) {
                    if (input.read() < 0) throw IOException("Unexpected end of file")
                    left--
                } else {
                    left -= skipped
                }
            }
        }
    }
}
