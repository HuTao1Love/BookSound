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
