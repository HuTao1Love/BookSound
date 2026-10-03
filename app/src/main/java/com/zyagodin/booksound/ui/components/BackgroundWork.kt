package com.zyagodin.booksound.ui.components

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.zyagodin.booksound.R
import com.zyagodin.booksound.ui.theme.Radii

/**
 * Battery optimization ("app sleeping") can stop torrent downloads and conversions in the
 * background, and some phones (Samsung, Xiaomi…) also put rarely opened apps to sleep. The app
 * asks once to be let run without restrictions; Settings shows the state and opens it again.
 */
object BackgroundWork {
    fun isUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    /** System dialog "Let the app always run in the background?". */
    // Not on Google Play: asking directly is fine, and it's what makes long downloads reliable.
    @SuppressLint("BatteryLife")
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))

    /** The app's system page; its Battery entry is where vendor sleep settings live. */
    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
}

/**
 * Returns an action that asks for unrestricted background work (or, when that is already
 * granted, opens the app's settings page). [onResult] gets the state after the user returns.
 */
@Composable
fun rememberBackgroundWorkRequest(onResult: (unrestricted: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onResult(BackgroundWork.isUnrestricted(context))
    }
    return {
        val first = if (BackgroundWork.isUnrestricted(context)) BackgroundWork.appSettingsIntent(context) else BackgroundWork.requestIntent(context)
        try {
            launcher.launch(first)
        } catch (e: ActivityNotFoundException) {
            // Some vendors remove the direct dialog; the app page still leads to Battery.
            runCatching { launcher.launch(BackgroundWork.appSettingsIntent(context)) }
        }
    }
}

/** Explains why background work matters for downloads and offers to allow it. */
@Composable
fun BackgroundWorkDialog(onAllow: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.background_title), style = MaterialTheme.typography.headlineSmall) },
        text = { Text(stringResource(R.string.background_message), style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onAllow, shape = Radii.pill) {
                Text(stringResource(R.string.background_allow), style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onLater, shape = Radii.pill) {
                Text(stringResource(R.string.background_later), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
