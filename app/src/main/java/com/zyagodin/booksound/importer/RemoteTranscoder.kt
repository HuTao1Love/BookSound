package com.zyagodin.booksound.importer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs conversions in [ConverterService]'s process.
 *
 * When a codec fails there in a way the file doesn't explain, or the process dies (crash, out of
 * memory), the process is killed, a fresh one is started and the conversion runs again, up to
 * [MAX_RESTARTS] times per conversion. Other books' conversions that were running in the killed
 * process start over too, without it counting against theirs. So the queue waits through a broken
 * codec instead of failing book after book. The process ends after a while without conversions.
 */
@OptIn(UnstableApi::class)
class RemoteTranscoder(private val context: Context) : Transcoder {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val replies = Messenger(Handler(Looper.getMainLooper()) { msg -> onReply(msg); true })

    // Everything below is touched on the main thread only.
    private var connection: Connection? = null
    private var nextConnection = 1
    private val calls = HashMap<Int, Call>()
    private var nextCall = 1
    private var idleStop: Job? = null

    private class Call(val connection: Connection, val onProgress: (Float) -> Unit, val result: CancellableContinuation<Transcoder.Result>)

    /** A failure a fresh process may not have: a codec error the file doesn't explain, or the process died. */
    private class ProcessTrouble(val error: ExportException) : Exception(error)

    private inner class Connection : ServiceConnection {
        val serial = nextConnection++

        /** Completes once the service told its process id. */
        val ready = CompletableDeferred<Messenger>()
        var service: Messenger? = null
        var pid = 0

        /** Unbound; no new calls go to it. */
        var closed = false

        /** Killed on purpose to get fresh codecs; calls it failed don't count as their own trouble. */
        var killed = false

        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            val messenger = Messenger(binder)
            service = messenger
            try {
                messenger.send(Message.obtain(null, ConverterProtocol.MSG_HELLO, serial, 0).apply { replyTo = replies })
            } catch (e: RemoteException) {
                lost(this)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) = lost(this)
        override fun onBindingDied(name: ComponentName?) = lost(this)
        override fun onNullBinding(name: ComponentName?) = lost(this)
    }

    override suspend fun run(
        inputs: List<Uri>,
        output: File,
        bitrateKbps: Int,
        downmixToMono: Boolean,
        transmux: Boolean,
        outputSampleRate: Int?,
        outputChannels: Int?,
        onProgress: (Float) -> Unit,
    ): Transcoder.Result = withContext(Dispatchers.Main.immediate) {
        val args = Bundle().apply {
            putStringArrayList(ConverterProtocol.KEY_INPUTS, ArrayList(inputs.map(Uri::toString)))
            putString(ConverterProtocol.KEY_OUTPUT, output.absolutePath)
            putInt(ConverterProtocol.KEY_BITRATE, bitrateKbps)
            putBoolean(ConverterProtocol.KEY_MONO, downmixToMono)
            putBoolean(ConverterProtocol.KEY_TRANSMUX, transmux)
            putInt(ConverterProtocol.KEY_SAMPLE_RATE, outputSampleRate ?: 0)
            putInt(ConverterProtocol.KEY_CHANNELS, outputChannels ?: 0)
        }
        try {
            runWithRestarts(args, output, onProgress)
        } finally {
            scheduleIdleStop()
        }
    }

    private suspend fun runWithRestarts(args: Bundle, output: File, onProgress: (Float) -> Unit): Transcoder.Result {
        var restarts = 0
        while (true) {
            var conn: Connection? = null
            try {
                conn = connect()
                return call(conn, args, onProgress)
            } catch (e: ProcessTrouble) {
                // A process another conversion killed for fresh codecs: just start over.
                if (conn?.killed != true) {
                    if (++restarts > MAX_RESTARTS) throw e.error
                    Log.w(TAG, "Conversion process trouble, restarting it (restart $restarts of $MAX_RESTARTS)", e.error)
                    conn?.let { kill(it) }
                }
                output.delete()
                onProgress(0f)
            }
        }
    }

    /** The current process, started if needed. */
    private suspend fun connect(): Connection {
        idleStop?.cancel()
        val conn = connection?.takeIf { !it.closed } ?: Connection().also { conn ->
            connection = conn
            val bound = try {
                context.bindService(Intent(context, ConverterService::class.java), conn, Context.BIND_AUTO_CREATE)
            } catch (e: SecurityException) {
                false
            }
            if (!bound) {
                close(conn)
                throw ExportException.createForUnexpected(IllegalStateException("Could not start the conversion process"))
            }
        }
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { conn.ready.await() }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Conversion process did not answer")
            kill(conn)
            throw ProcessTrouble(died())
        } catch (e: ConverterDiedException) {
            throw ProcessTrouble(died())
        }
        return conn
    }

    private suspend fun call(conn: Connection, args: Bundle, onProgress: (Float) -> Unit): Transcoder.Result =
        suspendCancellableCoroutine { cont ->
            val id = nextCall++
            calls[id] = Call(conn, onProgress, cont)
            try {
                conn.service!!.send(Message.obtain(null, ConverterProtocol.MSG_RUN, id, 0).apply { data = args; replyTo = replies })
            } catch (e: RemoteException) {
                calls.remove(id)
                cont.resumeWithException(ProcessTrouble(died()))
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                mainHandler.post {
                    if (calls.remove(id) != null && !conn.closed) {
                        runCatching { conn.service?.send(Message.obtain(null, ConverterProtocol.MSG_CANCEL, id, 0)) }
                    }
                }
            }
        }

    private fun onReply(msg: Message) {
        when (msg.what) {
            ConverterProtocol.MSG_HELLO -> {
                val conn = connection?.takeIf { it.serial == msg.arg2 && !it.ready.isCompleted } ?: return
                conn.pid = msg.arg1
                conn.ready.complete(conn.service!!)
            }
            ConverterProtocol.MSG_PROGRESS -> calls[msg.arg1]?.onProgress?.invoke(msg.data.getFloat(ConverterProtocol.KEY_PROGRESS))
            ConverterProtocol.MSG_DONE -> calls.remove(msg.arg1)?.result?.resume(
                Transcoder.Result(msg.data.getLong(ConverterProtocol.KEY_DURATION), msg.data.getLong(ConverterProtocol.KEY_SIZE)),
            )
            ConverterProtocol.MSG_ERROR -> calls.remove(msg.arg1)?.result?.let { cont ->
                val error = ConverterProtocol.decodeError(msg.data)
                val codecFailed = (error.cause as? RemoteConversionError)?.codecFailed == true
                cont.resumeWithException(if (codecFailed && error.errorCode in RESTART_ERRORS) ProcessTrouble(error) else error)
            }
        }
    }

    /** The process went away (crash, out of memory, or killed). */
    private fun lost(conn: Connection) {
        close(conn)
        failCalls(conn)
        if (!conn.ready.isCompleted) conn.ready.completeExceptionally(ConverterDiedException())
    }

    /** Ends [conn]'s process, so the next conversion starts a fresh one with working codecs. */
    private suspend fun kill(conn: Connection) {
        if (conn.killed) return
        conn.killed = true
        close(conn)
        failCalls(conn)
        val pid = conn.pid
        if (pid > 0 && pid != Process.myPid()) {
            Process.killProcess(pid)
            // Wait until it is gone, or binding again could land in the dying process.
            withTimeoutOrNull(KILL_TIMEOUT_MS) { while (File("/proc/$pid").exists()) delay(50) }
        }
    }

    private fun close(conn: Connection) {
        if (conn.closed) return
        conn.closed = true
        if (connection === conn) connection = null
        runCatching { context.unbindService(conn) }
    }

    private fun failCalls(conn: Connection) {
        val failed = calls.filterValues { it.connection === conn }
        failed.keys.forEach(calls::remove)
        failed.values.forEach { it.result.resumeWithException(ProcessTrouble(died())) }
    }

    private fun died() = ExportException.createForUnexpected(ConverterDiedException())

    /** Ends the process once nothing has been converted for a while; it holds the codecs and memory. */
    private fun scheduleIdleStop() {
        if (calls.isNotEmpty()) return
        idleStop?.cancel()
        idleStop = scope.launch {
            delay(IDLE_STOP_MS)
            if (calls.isEmpty()) connection?.let { kill(it) }
        }
    }

    private companion object {
        const val TAG = "RemoteTranscoder"

        /** Fresh processes tried for one conversion before its error is reported. */
        const val MAX_RESTARTS = 2
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val KILL_TIMEOUT_MS = 3_000L
        const val IDLE_STOP_MS = 30_000L

        /** Errors that, coming from a codec, are the phone's codecs rather than the file. */
        val RESTART_ERRORS = setOf(
            ExportException.ERROR_CODE_ENCODING_FAILED,
            ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
            ExportException.ERROR_CODE_DECODING_FAILED,
            ExportException.ERROR_CODE_DECODER_INIT_FAILED,
        )
    }
}
