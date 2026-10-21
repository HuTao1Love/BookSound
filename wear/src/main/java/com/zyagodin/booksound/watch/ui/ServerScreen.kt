package com.zyagodin.booksound.watch.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PhoneAndroid
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
import com.zyagodin.booksound.api.LibraryEntryDto
import com.zyagodin.booksound.watch.R
import com.zyagodin.booksound.watch.WatchContainer
import kotlinx.coroutines.delay
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
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberTransformingLazyColumnState()

    LaunchedEffect(account?.url) {
        if (account == null) return@LaunchedEffect
        error = null
        catalog = runCatching { server.catalog() }.onFailure { error = it.message ?: "" }.getOrNull()
    }

    // Ask the phone right away: it answers with its account, or with nothing when it isn't signed in.
    var asking by remember { mutableStateOf(false) }
    var phoneMissing by remember { mutableStateOf(false) }
    val ask: () -> Unit = {
        scope.launch {
            asking = true
            phoneMissing = !server.requestFromPhone()
            delay(8_000)
            asking = false
        }
    }
    LaunchedEffect(Unit) { if (account == null) ask() }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(stringResource(R.string.server_title)) } }
            if (account == null) {
                item {
                    Text(
                        stringResource(
                            when {
                                phoneMissing -> R.string.server_phone_missing
                                asking -> R.string.server_asking_phone
                                else -> R.string.server_not_connected
                            },
                        ),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item {
                    Button(
                        onClick = ask,
                        enabled = !asking,
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(Icons.Rounded.PhoneAndroid, contentDescription = null) },
                        label = { Text(stringResource(R.string.server_sign_in_phone)) },
                    )
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
