package com.zyagodin.booksound.importer

import android.app.Service
import android.content.Intent
import android.media.MediaCodec
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.ExportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Runs [AudioTranscoder] in its own process (":converter"), for [RemoteTranscoder].
 *
 * The phone's codecs can break for good inside a process: Codec2's buffer pool stops handing out
 * buffers, and every codec created afterwards fails within seconds. Only a new process recovers,
 * so the conversions run here, where [RemoteTranscoder] can kill the process and start a fresh one
 * without touching the app (player, downloads, UI). A muxer running out of memory on a very long
 * book only takes this process down too.
 */
@OptIn(UnstableApi::class)
class ConverterService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transcoder by lazy { AudioTranscoder(this) }

    /** Running conversions by the client's call id; touched on the main thread only. */
    private val jobs = HashMap<Int, Job>()

    private val messenger = Messenger(Handler(Looper.getMainLooper()) { msg -> handle(msg); true })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun handle(msg: Message) {
        val reply = msg.replyTo
        when (msg.what) {
            ConverterProtocol.MSG_HELLO -> send(reply, Message.obtain(null, ConverterProtocol.MSG_HELLO, Process.myPid(), msg.arg1))
            ConverterProtocol.MSG_RUN -> if (reply != null) start(msg.arg1, Bundle(msg.data), reply)
            ConverterProtocol.MSG_CANCEL -> jobs.remove(msg.arg1)?.cancel()
        }
    }

    private fun start(id: Int, args: Bundle, reply: Messenger) {
        // Registered before it starts, so that its own `finally` always finds it.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val result = try {
                val r = transcoder.run(
                    inputs = args.getStringArrayList(ConverterProtocol.KEY_INPUTS).orEmpty().map(Uri::parse),
                    output = File(args.getString(ConverterProtocol.KEY_OUTPUT)!!),
                    bitrateKbps = args.getInt(ConverterProtocol.KEY_BITRATE),
                    downmixToMono = args.getBoolean(ConverterProtocol.KEY_MONO),
                    transmux = args.getBoolean(ConverterProtocol.KEY_TRANSMUX),
                    outputSampleRate = args.getInt(ConverterProtocol.KEY_SAMPLE_RATE).takeIf { it > 0 },
                    outputChannels = args.getInt(ConverterProtocol.KEY_CHANNELS).takeIf { it > 0 },
                ) { progress ->
                    send(reply, Message.obtain(null, ConverterProtocol.MSG_PROGRESS, id, 0).apply { data = Bundle().apply { putFloat(ConverterProtocol.KEY_PROGRESS, progress) } })
                }
                Message.obtain(null, ConverterProtocol.MSG_DONE, id, 0).apply {
                    data = Bundle().apply {
                        putLong(ConverterProtocol.KEY_DURATION, r.durationMs)
                        putLong(ConverterProtocol.KEY_SIZE, r.sizeBytes)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExportException) {
                Log.w(TAG, "Conversion $id failed", e)
                Message.obtain(null, ConverterProtocol.MSG_ERROR, id, 0).apply { data = ConverterProtocol.encodeError(e) }
            } catch (e: Throwable) {
                Log.e(TAG, "Conversion $id failed unexpectedly", e)
                Message.obtain(null, ConverterProtocol.MSG_ERROR, id, 0).apply { data = ConverterProtocol.encodeError(ExportException.createForUnexpected(e)) }
            } finally {
                jobs.remove(id)
            }
            send(reply, result)
        }
        jobs[id] = job
        job.start()
    }

    private fun send(to: Messenger?, msg: Message) {
        try {
            to?.send(msg)
        } catch (e: RemoteException) {
            // The app process is gone; its conversions are cancelled when it unbinds.
            Log.w(TAG, "Could not reply", e)
        }
    }

    private companion object {
        const val TAG = "ConverterService"
    }
}

/** Messages between [RemoteTranscoder] (app process) and [ConverterService] (":converter"). */
@OptIn(UnstableApi::class)
internal object ConverterProtocol {
    /** Client → service: report the process id; the reply carries it in arg1 and echoes arg1 in arg2. */
    const val MSG_HELLO = 1

    /** Client → service: convert; arg1 is the call id, data the arguments. */
    const val MSG_RUN = 2

    /** Client → service: cancel the call in arg1. */
    const val MSG_CANCEL = 3

    /** Service → client, for the call in arg1. */
    const val MSG_PROGRESS = 4
    const val MSG_DONE = 5
    const val MSG_ERROR = 6

    const val KEY_INPUTS = "inputs"
    const val KEY_OUTPUT = "output"
    const val KEY_BITRATE = "bitrate"
    const val KEY_MONO = "mono"
    const val KEY_TRANSMUX = "transmux"
    const val KEY_SAMPLE_RATE = "sampleRate"
    const val KEY_CHANNELS = "channels"
    const val KEY_PROGRESS = "progress"
    const val KEY_DURATION = "duration"
    const val KEY_SIZE = "size"
    private const val KEY_ERROR_CODE = "errorCode"
    private const val KEY_MESSAGE = "message"
    private const val KEY_CODEC = "codec"
    private const val KEY_CODEC_ERROR = "codecError"
    private const val KEY_CODEC_DIAGNOSTIC = "codecDiagnostic"

    /** An [ExportException] can't cross processes: send its error code and what caused it. */
    fun encodeError(e: ExportException): Bundle = Bundle().apply {
        putInt(KEY_ERROR_CODE, e.errorCode)
        val chain = generateSequence<Throwable>(e) { it.cause.takeIf { c -> c !== it } }.toList()
        // Every message in the chain, so that e.g. "ENOSPC" is still recognised as out of space.
        putString(KEY_MESSAGE, chain.drop(1).joinToString(" ← ") { t -> t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "") }.take(2_000))
        chain.filterIsInstance<MediaCodec.CodecException>().lastOrNull()?.let { codec ->
            putBoolean(KEY_CODEC, true)
            putInt(KEY_CODEC_ERROR, codec.errorCode)
            putString(KEY_CODEC_DIAGNOSTIC, codec.diagnosticInfo)
        }
    }

    fun decodeError(data: Bundle): ExportException {
        val cause = RemoteConversionError(
            message = data.getString(KEY_MESSAGE),
            codecFailed = data.getBoolean(KEY_CODEC),
            codecErrorCode = data.getInt(KEY_CODEC_ERROR),
            codecDiagnostic = data.getString(KEY_CODEC_DIAGNOSTIC),
        )
        return ExportException.createForAssetLoader(cause, data.getInt(KEY_ERROR_CODE, ExportException.ERROR_CODE_UNSPECIFIED))
    }
}

/** What made a conversion in [ConverterService] fail, rebuilt in the app process. */
class RemoteConversionError(
    message: String?,
    /** A `MediaCodec.CodecException` was behind the failure: the phone's codec, not the file. */
    val codecFailed: Boolean,
    val codecErrorCode: Int,
    val codecDiagnostic: String?,
) : Exception(message)

/** [ConverterService]'s process ended while converting (crash, out of memory, or killed). */
class ConverterDiedException : Exception("The conversion process ended unexpectedly")
