package com.zyagodin.booksound.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyagodin.booksound.R
import com.zyagodin.booksound.api.WatchProfileDto
import com.zyagodin.booksound.server.ServerOnlyBook
import com.zyagodin.booksound.server.ServerTransfer
import com.zyagodin.booksound.shared.server.ServerException
import com.zyagodin.booksound.ui.AppNavigator
import com.zyagodin.booksound.ui.components.SectionHeader
import com.zyagodin.booksound.ui.navigation.appContainer
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing
import com.zyagodin.booksound.util.formatDuration
import com.zyagodin.booksound.util.formatSize
import kotlinx.coroutines.launch
import java.io.IOException

/** The optional BookSound server: account, what goes to the server and the watch, and the server's books. */
@Composable
fun ServerScreen(navigator: AppNavigator) {
    val container = appContainer()
    val server = container.server
    val account by server.account.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val transfers by server.transfers.collectAsStateWithLifecycle()
    val serverBooks by remember { server.serverOnlyBooks() }.collectAsStateWithLifecycle(emptyList())
    val phoneBooks by remember { server.phoneOnlyBooks() }.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var profiles by remember { mutableStateOf<List<WatchProfileDto>>(emptyList()) }

    LaunchedEffect(account?.url) {
        if (account != null) {
            container.sync.requestSync()
            profiles = runCatching { server.watchProfiles() }.getOrDefault(emptyList())
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
            Text(stringResource(R.string.server_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = Spacing.sm))
        }
        LazyColumn(
            contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = bottom + Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                    val current = account
                    if (current == null) {
                        SignInForm { url, user, password, onResult ->
                            scope.launch {
                                val error = try {
                                    server.signIn(url, user, password)
                                    null
                                } catch (e: ServerException) {
                                    if (e.status == 401) context.getString(R.string.server_wrong_password) else e.message
                                } catch (e: IOException) {
                                    context.getString(R.string.server_unreachable, e.message ?: "")
                                }
                                onResult(error)
                            }
                        }
                        Text(stringResource(R.string.server_about), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Spacing.lg))
                    } else {
                        Card {
                            Column(Modifier.padding(Spacing.lg)) {
                                Text(current.userName, style = MaterialTheme.typography.titleMedium)
                                Text(current.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(Modifier.padding(top = Spacing.md), horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                                    OutlinedButton(onClick = { container.sync.requestSync() }) { Text(stringResource(R.string.server_sync_now)) }
                                    TextButton(onClick = { scope.launch { server.signOut() } }) { Text(stringResource(R.string.server_sign_out)) }
                                }
                            }
                        }
                        SectionHeader(stringResource(R.string.server_group_everything), Modifier.padding(top = Spacing.xl, start = Spacing.xs))
                        Card {
                            Column {
                                val moving = transfers.filter { it.isActive }
                                BulkRow(
                                    title = stringResource(R.string.server_upload_all),
                                    subtitle = if (phoneBooks.isEmpty()) stringResource(R.string.server_upload_all_none)
                                    else pluralStringResource(R.plurals.server_upload_all_count, phoneBooks.size, phoneBooks.size),
                                    action = stringResource(R.string.server_upload_all_action),
                                    enabled = phoneBooks.any { b -> moving.none { it.bookId == b.id && it.direction == ServerTransfer.Direction.UPLOAD } },
                                ) { server.uploadAll(phoneBooks) }
                                BulkRow(
                                    title = stringResource(R.string.server_download_all),
                                    subtitle = if (serverBooks.isEmpty()) stringResource(R.string.server_books_empty)
                                    else pluralStringResource(R.plurals.server_download_all_count, serverBooks.size, serverBooks.size, formatSize(context, serverBooks.sumOf { it.entity.sizeBytes })),
                                    action = stringResource(R.string.server_download_all_action),
                                    enabled = serverBooks.any { b -> moving.none { it.bookId == b.entity.id && it.direction == ServerTransfer.Direction.DOWNLOAD } },
                                ) { server.downloadAll(serverBooks) }
                            }
                        }
                        SectionHeader(stringResource(R.string.server_group_options), Modifier.padding(top = Spacing.xl, start = Spacing.xs))
                        Card {
                            Column {
                                ToggleRow(stringResource(R.string.server_auto_upload), stringResource(R.string.server_auto_upload_hint), settings.serverAutoUpload) {
                                    scope.launch { container.settings.setServerAutoUpload(it) }
                                }
                                WatchProfileRow(profiles, settings.serverWatchProfile) { id -> scope.launch { container.settings.setServerWatchProfile(id) } }
                                Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(stringResource(R.string.server_connect_watch), style = MaterialTheme.typography.bodyLarge)
                                        Text(stringResource(R.string.server_connect_watch_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    OutlinedButton(onClick = {
                                        scope.launch {
                                            val ok = runCatching { server.connectWatch(settings.serverWatchProfile) }.getOrDefault(false)
                                            Toast.makeText(context, if (ok) R.string.server_watch_connected else R.string.server_watch_not_connected, Toast.LENGTH_SHORT).show()
                                        }
                                    }) { Text(stringResource(R.string.server_connect)) }
                                }
                            }
                        }
                        if (transfers.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().padding(top = Spacing.xl), verticalAlignment = Alignment.CenterVertically) {
                                SectionHeader(stringResource(R.string.server_group_transfers), Modifier.weight(1f).padding(start = Spacing.xs))
                                if (transfers.any { !it.isActive }) TextButton(onClick = server::clearFinished) { Text(stringResource(R.string.server_clear)) }
                            }
                            Card {
                                Column { transfers.forEach { TransferRow(it) { server.cancel(it.bookId, it.direction) } } }
                            }
                        }
                        SectionHeader(stringResource(R.string.server_group_books, serverBooks.size), Modifier.padding(top = Spacing.xl, start = Spacing.xs))
                        if (serverBooks.isEmpty()) {
                            Text(stringResource(R.string.server_books_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Spacing.sm))
                        }
                    }
                }
            }
            if (account != null) {
                items(serverBooks.sortedWith(compareBy({ it.entity.author ?: "" }, { it.entity.series ?: "" }, { it.entity.seriesIndex?.toDoubleOrNull() ?: 0.0 }, { it.entity.title })), key = { it.entity.id }) { book ->
                    val transfer = transfers.firstOrNull { it.bookId == book.entity.id && it.direction == ServerTransfer.Direction.DOWNLOAD && it.isActive }
                    ServerBookRow(book, transfer) { server.download(book.entity) }
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = Radii.card, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) { content() }
}

@Composable
private fun SignInForm(onSignIn: (String, String, String, (String?) -> Unit) -> Unit) {
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Card {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Text(stringResource(R.string.server_sign_in), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(url, { url = it }, label = { Text(stringResource(R.string.server_address)) }, placeholder = { Text("abook.example.netcraze.link") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(user, { user = it }, label = { Text(stringResource(R.string.server_user)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.server_password)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = {
                    busy = true
                    error = null
                    onSignIn(url, user, password) { result ->
                        busy = false
                        error = result
                        if (result == null) password = ""
                    }
                },
                enabled = !busy && url.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
            ) { Text(stringResource(R.string.server_sign_in)) }
        }
    }
}

@Composable
private fun BulkRow(title: String, subtitle: String, action: String, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(Spacing.md))
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(action) }
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Surface(onClick = { onChange(!checked) }, color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(Spacing.md))
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun WatchProfileRow(profiles: List<WatchProfileDto>, selected: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = profiles.firstOrNull { it.id == selected }?.name ?: stringResource(R.string.server_watch_original)
    Surface(onClick = { open = true }, color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Spacing.lg, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.server_watch_copies), style = MaterialTheme.typography.bodyLarge)
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.server_watch_original)) }, onClick = { open = false; onSelect(null) })
                profiles.forEach { p ->
                    DropdownMenuItem(text = { Text(p.name) }, onClick = { open = false; onSelect(p.id) })
                }
            }
        }
    }
}

@Composable
private fun TransferRow(transfer: ServerTransfer, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(transfer.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val stage = when (transfer.stage) {
                    ServerTransfer.Stage.QUEUED -> stringResource(R.string.server_stage_queued)
                    ServerTransfer.Stage.RUNNING -> stringResource(
                        if (transfer.direction == ServerTransfer.Direction.UPLOAD) R.string.server_uploading else R.string.server_downloading,
                    ) + (transfer.progress?.let { " · ${(it * 100).toInt()}%" } ?: "")
                    ServerTransfer.Stage.FINISHING -> stringResource(R.string.server_stage_finishing)
                    ServerTransfer.Stage.DONE -> stringResource(R.string.server_stage_done)
                    ServerTransfer.Stage.FAILED -> stringResource(R.string.server_stage_failed, transfer.error ?: "")
                    ServerTransfer.Stage.CANCELLED -> stringResource(R.string.server_stage_cancelled)
                }
                Text(stage, style = MaterialTheme.typography.bodySmall, color = if (transfer.stage == ServerTransfer.Stage.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (transfer.isActive) IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, stringResource(R.string.action_cancel)) }
        }
        if (transfer.stage == ServerTransfer.Stage.FINISHING) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (transfer.stage == ServerTransfer.Stage.RUNNING) {
            transfer.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) } ?: LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ServerBookRow(book: ServerOnlyBook, transfer: ServerTransfer?, onDownload: () -> Unit) {
    val e = book.entity
    val context = LocalContext.current
    Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(vertical = Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(listOfNotNull(e.seriesIndex?.takeIf { e.series != null }?.let { "$it." }, e.title).joinToString(" "), style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val details = listOfNotNull(e.author, e.series, formatDuration(context, e.durationMs), formatSize(context, e.sizeBytes),
                book.positionMs?.takeIf { it > 0 && !book.finished }?.let { "▶ " + formatDuration(context, it) })
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (transfer != null) {
                transfer.progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs)) }
                    ?: LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Spacing.xs))
            }
        }
        IconButton(onClick = onDownload, enabled = transfer == null) { Icon(Icons.Rounded.CloudDownload, stringResource(R.string.server_download)) }
    }
}
