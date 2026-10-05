package com.zyagodin.booksound.core.organize

import com.zyagodin.booksound.core.metadata.AudioContainer
import com.zyagodin.booksound.core.metadata.ParsedAudioFile

enum class ConversionStrategy {
    /** Single AAC MP4: copy the audio unchanged, only rewrite metadata/chapters/cover. */
    REMUX_SINGLE,

    /** Several AAC MP4 parts with identical stream parameters: concatenate without re-encoding. */
    CONCAT_COPY,

    /** Anything else (MP3, mixed formats): decode and re-encode to AAC. */
    TRANSCODE,
}

object ConversionPlanner {

    fun plan(parts: List<ParsedAudioFile>): ConversionStrategy {
        require(parts.isNotEmpty())
        val allAacMp4 = parts.all { it.container == AudioContainer.MP4 && it.stream?.codec == "aac" && it.rewritable }
        if (!allAacMp4) return ConversionStrategy.TRANSCODE
        if (parts.size == 1) return ConversionStrategy.REMUX_SINGLE
        val first = parts.first().stream!!
        return if (parts.all { it.stream!!.isCompatibleWith(first) }) ConversionStrategy.CONCAT_COPY else ConversionStrategy.TRANSCODE
    }

    /**
     * The AAC bitrate to encode a book at: the [settingKbps], but not above what the source files
     * carry (their duration-weighted average, rounded to 8 kbps, as a 96 kbps MP3 measures a hair
     * over 96). More bits than the source has only make the file bigger: what its encoder threw
     * away doesn't come back.
     */
    fun outputBitrateKbps(settingKbps: Int, sources: List<ImportSourceFile>): Int {
        var bits = 0.0
        var durationMs = 0L
        for (file in sources) {
            val duration = file.parsed.durationMs?.takeIf { it > 0 } ?: continue
            val bitrate = file.parsed.stream?.bitrate?.takeIf { it > 0 }?.toDouble()
                ?: (file.sizeBytes * 8 * 1000.0 / duration).takeIf { file.sizeBytes > 0 }
                ?: continue
            bits += bitrate * duration
            durationMs += duration
        }
        if (durationMs == 0L) return settingKbps
        val sourceKbps = (Math.round(bits / durationMs / 1000 / 8) * 8).toInt()
        return minOf(settingKbps, sourceKbps.coerceAtLeast(MIN_BITRATE_KBPS))
    }

    private const val MIN_BITRATE_KBPS = 16

    /** Expected size of the final file, used for free-space checks before any work starts. */
    fun estimateOutputBytes(strategy: ConversionStrategy, inputBytes: Long, durationMs: Long, bitrateKbps: Int, coverBytes: Int): Long {
        val audio = when (strategy) {
            ConversionStrategy.REMUX_SINGLE, ConversionStrategy.CONCAT_COPY -> inputBytes
            // AAC at the requested bitrate plus ~3% container overhead.
            ConversionStrategy.TRANSCODE -> (durationMs / 1000.0 * bitrateKbps * 1000 / 8 * 1.03).toLong()
        }
        return audio + coverBytes + 512 * 1024
    }
}
