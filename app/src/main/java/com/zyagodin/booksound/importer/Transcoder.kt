package com.zyagodin.booksound.importer

import android.net.Uri
import java.io.File

/** Concatenates audio files into one AAC/MP4 file. */
interface Transcoder {

    data class Result(val durationMs: Long, val sizeBytes: Long)

    /**
     * With [transmux] the AAC stream is copied instead of re-encoded. [outputSampleRate] /
     * [outputChannels] force one output format (for parts encoded separately that are joined
     * afterwards); null keeps the input's. Fails with a Media3 `ExportException`.
     */
    suspend fun run(
        inputs: List<Uri>,
        output: File,
        bitrateKbps: Int,
        downmixToMono: Boolean,
        transmux: Boolean,
        outputSampleRate: Int? = null,
        outputChannels: Int? = null,
        onProgress: (Float) -> Unit,
    ): Result
}
