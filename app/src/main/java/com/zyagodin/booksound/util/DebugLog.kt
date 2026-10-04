package com.zyagodin.booksound.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.zyagodin.booksound.BuildConfigInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects the app's own log (Android lets an app read only its own entries) with device and
 * app details into a text file, to be shared when reporting a problem.
 */
object DebugLog {

    suspend fun export(context: Context): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        dir.listFiles()?.forEach(File::delete)
        val file = File(dir, "booksound-log-$stamp.txt")
        file.bufferedWriter().use { out ->
            out.appendLine("BookSound ${BuildConfigInfo.versionName(context)}")
            out.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}")
            out.appendLine("Collected: ${Date()}")
            out.appendLine()
            val process = ProcessBuilder("logcat", "-d", "-v", "threadtime").redirectErrorStream(true).start()
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { out.appendLine(it) } }
            process.waitFor()
        }
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    fun shareIntent(uri: Uri): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "BookSound debug log")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        null,
    )
}
