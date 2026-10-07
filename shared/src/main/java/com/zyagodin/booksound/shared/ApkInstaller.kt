package com.zyagodin.booksound.shared

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast
import androidx.core.content.IntentCompat
import java.io.File

/**
 * Installs an update of this app from an APK file through the system installer, which asks the
 * user to confirm (and, the first time, to allow installs from BookSound). Works on phones and on
 * Wear OS watches.
 */
object ApkInstaller {
    private const val TAG = "ApkInstaller"

    /** The version name inside [apk] if it is a build of this app, else null. */
    fun versionOf(context: Context, apk: File): String? =
        context.packageManager.getPackageArchiveInfo(apk.path, 0)
            ?.takeIf { it.packageName == context.packageName }
            ?.versionName

    /** Starts installing [apk]; the confirmation and any error arrive via [InstallResultReceiver]. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val result = PendingIntent.getBroadcast(
                    context, id,
                    Intent(context, InstallResultReceiver::class.java),
                    // Mutable: the installer adds the status and the confirmation intent.
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session.commit(result.intentSender)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Install session failed", e)
            installer.abandonSession(id)
            throw e
        }
    }
}

/** Shows the system's install confirmation, or why the install failed. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // The user said no.
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("ApkInstaller", "Install failed: $status $message")
                val reason = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT) context.getString(R.string.update_install_conflict) else message ?: "$status"
                Toast.makeText(context, context.getString(R.string.update_install_failed, reason), Toast.LENGTH_LONG).show()
            }
        }
    }
}
