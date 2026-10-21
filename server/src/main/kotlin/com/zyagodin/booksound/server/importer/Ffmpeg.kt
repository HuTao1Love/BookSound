package com.zyagodin.booksound.server.importer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException

class FfmpegException(message: String) : IOException(message)

/** Audio encoding with an ffmpeg executable: the server's counterpart of the phone's Media3 Transformer. */
class Ffmpeg(private val executable: String) {
    private val log = LoggerFactory.getLogger(Ffmpeg::class.java)

    val available: Boolean by lazy {
        runCatching {
            val p = ProcessBuilder(executable, "-hide_banner", "-version").redirectErrorStream(true).start()
            p.inputStream.readBytes()
            p.waitFor() == 0
        }.getOrDefault(false)
    }

    /**
     * Decodes [inputs] one after another and encodes them into one AAC file in an MP4 container, without
     * metadata, chapters or cover (those are written by `Mp4TagWriter`). [onProgress] gets 0..1 of
     * [expectedDurationMs]. Cancelling the coroutine stops ffmpeg.
     */
    suspend fun encodeAac(
        inputs: List<File>,
        output: File,
        bitrateKbps: Int,
        channels: Int?,
        sampleRate: Int?,
        expectedDurationMs: Long,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (!available) throw FfmpegException("ffmpeg is not installed")
        require(inputs.isNotEmpty())
        val list = File(output.parentFile, output.nameWithoutExtension + ".inputs.txt")
        // The concat demuxer reads the parts in order; a change of format between parts re-creates the filters.
        list.writeText(inputs.joinToString("\n") { "file '" + it.absolutePath.replace("\\", "/").replace("'", "'\\''") + "'" })
        val args = buildList {
            addAll(listOf(executable, "-hide_banner", "-nostdin", "-y", "-loglevel", "error", "-progress", "pipe:1", "-nostats"))
            addAll(listOf("-f", "concat", "-safe", "0", "-i", list.absolutePath))
            addAll(listOf("-map", "0:a:0", "-vn", "-sn", "-dn", "-map_metadata", "-1", "-map_chapters", "-1"))
            addAll(listOf("-c:a", "aac", "-b:a", "${bitrateKbps}k"))
            channels?.let { addAll(listOf("-ac", it.toString())) }
            sampleRate?.let { addAll(listOf("-ar", it.toString())) }
            addAll(listOf("-movflags", "+faststart", "-f", "mp4", output.absolutePath))
        }
        try {
            run(args, expectedDurationMs, onProgress)
        } finally {
            list.delete()
        }
    }

    private suspend fun run(args: List<String>, expectedDurationMs: Long, onProgress: (Float) -> Unit) {
        val process = ProcessBuilder(args).start()
        val errors = StringBuilder()
        val stderr = Thread {
            process.errorStream.bufferedReader().forEachLine { line -> if (errors.length < 8_000) errors.appendLine(line) }
        }.apply { isDaemon = true; start() }
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    currentCoroutineContext().ensureActive()
                    if (line.startsWith("out_time_us=") && expectedDurationMs > 0) {
                        val us = line.substringAfter('=').toLongOrNull() ?: continue
                        onProgress((us / 1000.0 / expectedDurationMs).toFloat().coerceIn(0f, 1f))
                    }
                }
            }
            val code = process.waitFor()
            stderr.join(1_000)
            if (code != 0) {
                val message = errors.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" ").ifBlank { "exit code $code" }
                log.warn("ffmpeg failed ({}): {}", code, errors)
                throw FfmpegException(message)
            }
        } catch (e: CancellationException) {
            process.destroyForcibly()
            throw e
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
