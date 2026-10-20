package com.zyagodin.booksound.watch.ui

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Login
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.zyagodin.booksound.api.DeviceCodeResponse
import com.zyagodin.booksound.api.LibraryEntryDto
import com.zyagodin.booksound.watch.R
import com.zyagodin.booksound.watch.WatchContainer
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Books of the user's BookSound server that are not on the watch, to download over Wi-Fi. */
@Composable
fun ServerScreen(container: WatchContainer) {
    val server = container.server
    val account by server.account.collectAsStateWithLifecycle()
    val books by container.library.books.collectAsStateWithLifecycle()
    val downloads by server.downloads.collectAsStateWithLifecycle()
    var catalog by remember { mutableStateOf<List<LibraryEntryDto>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf<DeviceCodeResponse?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberTransformingLazyColumnState()

    LaunchedEffect(account?.url) {
        if (account == null) return@LaunchedEffect
        error = null
        catalog = runCatching { server.catalog() }.onFailure { error = it.message ?: "" }.getOrNull()
    }

    // The server's address typed in with the watch's keyboard or voice, for signing in with a code.
    val askAddress = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val url = result.data?.let { RemoteInput.getResultsFromIntent(it) }?.getCharSequence(KEY_ADDRESS)?.toString()?.trim().orEmpty()
        if (url.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val started = server.startDeviceCode(url)
                code = started
                if (!server.awaitDeviceCode(started)) error = context.getString(R.string.server_code_expired)
            } catch (e: Exception) {
                error = e.message ?: ""
            } finally {
                code = null
            }
        }
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(stringResource(R.string.server_title)) } }
            if (account == null) {
                val pending = code
                item {
                    Text(
                        if (pending != null) stringResource(R.string.server_code, pending.userCode) else stringResource(R.string.server_not_connected),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                        textAlign = TextAlign.Center,
                        style = if (pending != null) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodySmall,
                    )
                }
                if (pending == null) {
                    item {
                        Button(
                            onClick = { askAddress.launch(addressInput(context.getString(R.string.server_address))) },
                            modifier = Modifier.fillMaxWidth(),
                            icon = { Icon(Icons.Rounded.Login, contentDescription = null) },
                            label = { Text(stringResource(R.string.server_sign_in_code)) },
                        )
                    }
                }
            } else {
                items(downloads, key = { "dl-" + it.bookId }) { d ->
                    Button(
                        onClick = { server.cancel(d.bookId) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                        icon = { Icon(if (d.error != null) Icons.Rounded.ErrorOutline else Icons.Rounded.Downloading, contentDescription = null) },
                        secondaryLabel = {
                            Text(
                                d.error ?: d.progress?.let { stringResource(R.string.receiving, (it * 100).roundToInt()) } ?: stringResource(R.string.server_preparing),
                                maxLines = 2,
                            )
                        },
                        label = { Text(d.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    )
                }
                val here = books.map { it.id }.toSet()
                val busy = downloads.map { it.bookId }.toSet()
                val offered = catalog?.filter { it.book.id !in here && it.book.id !in busy }.orEmpty()
                if (catalog != null && offered.isEmpty() && downloads.isEmpty()) {
                    item {
                        Text(stringResource(R.string.server_all_here), modifier = Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall)
                    }
                }
                error?.let { message ->
                    item { Text(message, modifier = Modifier.fillMaxWidth().padding(8.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall) }
                }
                items(offered, key = { it.book.id }) { entry ->
                    Button(
                        onClick = { server.download(entry) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                        icon = { Icon(Icons.Rounded.CloudDownload, contentDescription = null) },
                        secondaryLabel = { Text(listOfNotNull(entry.book.metadata.author, Formatter.formatShortFileSize(context, entry.book.sizeBytes)).joinToString(" · "), maxLines = 1) },
                        label = { Text(entry.book.metadata.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    )
                }
                item {
                    Button(
                        onClick = { server.signOut() },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                        label = { Text(stringResource(R.string.server_sign_out)) },
                        secondaryLabel = { Text(account?.url?.removePrefix("https://").orEmpty(), maxLines = 1) },
                    )
                }
            }
        }
    }
}

private const val KEY_ADDRESS = "address"

/** The system's text input on Wear OS (keyboard, voice, phone), as RemoteInputIntentHelper builds it. */
private fun addressInput(label: String): Intent {
    val input = RemoteInput.Builder(KEY_ADDRESS).setLabel(label).build()
    return Intent("android.support.wearable.input.action.REMOTE_INPUT").apply {
        putExtra("android.support.wearable.input.extra.REMOTE_INPUTS", arrayOf(input))
        putExtra("android.support.wearable.input.extra.TITLE", label)
    }
}
