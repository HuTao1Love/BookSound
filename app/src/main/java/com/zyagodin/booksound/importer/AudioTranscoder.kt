package com.zyagodin.booksound.importer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.FrameworkMuxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Concatenates the input files into one AAC/MP4 file using Media3 Transformer (hardware codecs,
 * no native libraries), in this process. Imports use it through [RemoteTranscoder], which runs it
 * in [ConverterService]'s own process.
 */
@OptIn(UnstableApi::class)
class AudioTranscoder(private val context: Context) : Transcoder {

    override suspend fun run(
        inputs: List<Uri>,
        output: File,
        bitrateKbps: Int,
        downmixToMono: Boolean,
        transmux: Boolean,
        outputSampleRate: Int?,
        outputChannels: Int?,
        onProgress: (Float) -> Unit,
    ): Transcoder.Result = withContext(Dispatchers.Main) {
        output.delete()
        coroutineScope {
            var transformerRef: Transformer? = null
            val progressJob = launch {
                val holder = ProgressHolder()
                while (isActive) {
                    delay(400)
                    val t = transformerRef ?: continue
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                }
            }
            try {
                suspendCancellableCoroutine { cont ->
                    // Audio processors are stateful: each item gets its own instances.
                    fun processors() = buildList<AudioProcessor> {
                        if (transmux) return@buildList
                        val channels = if (downmixToMono) 1 else outputChannels
                        if (channels != null) add(channelMixer(channels))
                        if (outputSampleRate != null) add(SonicAudioProcessor().apply { setOutputSampleRateHz(outputSampleRate) })
                    }
                    val items = inputs.map { uri ->
                        val effects = processors()
                        EditedMediaItem.Builder(MediaItem.fromUri(uri))
                            .setRemoveVideo(true) // cover art exposed as a video track by some files
                            .apply { if (effects.isNotEmpty()) setEffects(Effects(effects, emptyList())) }
                            .build()
                    }
                    val composition = Composition.Builder(EditedMediaItemSequence.withAudioFrom(items))
                        .setTransmuxAudio(transmux)
                        .build()
                    val encoderFactory = DefaultEncoderFactory.Builder(context)
                        .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(bitrateKbps * 1000).build())
                        .setEnableFallback(true)
                        .build()
                    val transformer = Transformer.Builder(context)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(encoderFactory)
                        // The platform muxer (MediaMuxer) keeps its sample tables in native memory.
                        // Media3's own Mp4Muxer, the default (DefaultMuxer wraps it too), keeps
                        // objects per AAC frame on the Java heap and runs out of memory at ~50 h of
                        // audio (a whole series in one book).
                        .setMuxerFactory(FrameworkMuxer.Factory())
                        .setLooper(Looper.getMainLooper())
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(Transcoder.Result(exportResult.approximateDurationMs, exportResult.fileSizeBytes))
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) cont.resumeWithException(exportException)
                            }
                        })
                        .build()
                    transformerRef = transformer
                    cont.invokeOnCancellation {
                        // Transformer must be touched on its looper thread.
                        Handler(Looper.getMainLooper()).post { transformer.cancel() }
                    }
                    transformer.start(composition, output.absolutePath)
                }
            } finally {
                progressJob.cancel()
            }
        }
    }

    /** Mixes mono or stereo input to [channels] output channels. */
    private fun channelMixer(channels: Int) = ChannelMixingAudioProcessor().apply {
        putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(1, channels))
        putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(2, channels))
    }
}
