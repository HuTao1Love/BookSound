package com.zyagodin.booksound.importer

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.sin

/** Conversions run in the ":converter" process and survive that process being killed. */
@RunWith(AndroidJUnit4::class)
class RemoteTranscoderTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "remote-transcoder-test").apply { mkdirs() }

    @Test
    fun convertsInTheConverterProcess() = runBlocking {
        val input = sineWav(File(dir, "short.wav"), seconds = 20)
        val output = File(dir, "short.m4a")
        val transcoder = RemoteTranscoder(context)
        val result = withTimeout(120_000) {
            transcoder.run(listOf(Uri.fromFile(input)), output, bitrateKbps = 64, downmixToMono = false, transmux = false) {}
        }
        assertTrue("output written", output.length() > 0)
        assertTrue("duration ${result.durationMs}", result.durationMs in 19_000..21_000)
        assertTrue("converter process running", converterPid() != null)
    }

    @Test
    fun startsOverWhenTheConverterProcessDies() = runBlocking {
        val input = sineWav(File(dir, "long.wav"), seconds = 600)
        val output = File(dir, "long.m4a")
        val transcoder = RemoteTranscoder(context)
        val restartedFromZero = AtomicBoolean(false)
        val progress = AtomicReference(0f)
        val conversion = async {
            transcoder.run(listOf(Uri.fromFile(input)), output, bitrateKbps = 64, downmixToMono = false, transmux = false) {
                if (it == 0f && progress.get() > 0f) restartedFromZero.set(true)
                progress.set(it)
            }
        }
        // Kill the process mid-conversion, like a crash or the phone dropping it.
        val pid = withTimeout(60_000) {
            while (converterPid() == null || progress.get() <= 0f) delay(100)
            delay(2_000)
            converterPid()!!
        }
        Process.killProcess(pid)

        val result = withTimeout(300_000) { conversion.await() }
        assertTrue("started over", restartedFromZero.get())
        assertTrue("a new process converted it", converterPid().let { it != null && it != pid })
        assertTrue("output written", output.length() > 0)
        assertTrue("duration ${result.durationMs}", result.durationMs in 599_000..601_000)
    }

    private fun converterPid(): Int? = context.getSystemService(ActivityManager::class.java).runningAppProcesses
        ?.firstOrNull { it.processName == context.packageName + ":converter" }?.pid

    /** A 16-bit mono 44.1 kHz WAV file with a sine tone. */
    private fun sineWav(file: File, seconds: Int): File {
        val rate = 44_100
        val samples = rate * seconds
        val data = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) data.putShort((sin(2 * PI * 440 * i / rate) * 8_000).toInt().toShort())
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
        }
        file.outputStream().use { it.write(header.array()); it.write(data.array()) }
        return file
    }
}
