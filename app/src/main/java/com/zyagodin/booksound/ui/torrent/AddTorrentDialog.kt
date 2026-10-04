package com.zyagodin.booksound.ui.torrent

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.torrent.TorrentContentProblem
import com.zyagodin.booksound.core.torrent.TorrentLinks
import com.zyagodin.booksound.torrent.AddError
import com.zyagodin.booksound.torrent.AddResult
import com.zyagodin.booksound.torrent.TorrentSource
import com.zyagodin.booksound.ui.components.LoadingDots
import com.zyagodin.booksound.ui.components.TonalButton
import com.zyagodin.booksound.ui.navigation.appContainer
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Adds a torrent from a magnet link, a link to a .torrent file, or a .torrent file. The file list
 * is checked before anything is downloaded; on success the review editor opens right away.
 *
 * Several links can be pasted at once, one per line: they are added one after another and the
 * Imports screen opens (each book is reviewed from there). Lines that could not be added stay in
 * the field with the reason, so they can be fixed and added again.
 *
 * [initial] is a link or file opened from another app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddTorrentDialog(
    initial: TorrentSource?,
    onDismiss: () -> Unit,
    onAdded: (torrentId: String) -> Unit,
    onAlreadyAdded: (torrentId: String) -> Unit,
    onAddedSeveral: () -> Unit,
) {
    val context = LocalContext.current
    val torrents = appContainer().torrents
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf((initial as? TorrentSource.Link)?.text.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    /** "3 of 5" while a list of links is being added. */
    var batchProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    /** Torrents a list added; closing the dialog then shows them on the Imports screen. */
    var batchAdded by rememberSaveable { mutableIntStateOf(0) }
    val links = TorrentLinks.splitLines(text)

    fun close() = if (batchAdded > 0) onAddedSeveral() else onDismiss()

    fun add(source: TorrentSource) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val result = try {
                torrents.add(source)
            } finally {
                busy = false
            }
            when (result) {
                // Several books in one torrent: each is reviewed from the Imports screen.
                is AddResult.Added -> if (result.books > 1) onAddedSeveral() else onAdded(result.torrentId)
                is AddResult.AlreadyAdded -> onAlreadyAdded(result.torrentId)
                is AddResult.Rejected -> error = addErrorMessage(context, result)
            }
        }
    }

    fun addAll(list: List<String>) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            val failed = mutableListOf<Pair<String, String>>()
            try {
                list.forEachIndexed { i, link ->
                    batchProgress = i + 1 to list.size
                    when (val result = torrents.add(TorrentSource.Link(link))) {
                        is AddResult.Added -> batchAdded++
                        is AddResult.AlreadyAdded -> Unit
                        is AddResult.Rejected -> failed += link to addErrorMessage(context, result)
                    }
                }
            } finally {
                busy = false
                batchProgress = null
            }
            if (failed.isEmpty()) {
                onAddedSeveral()
            } else {
                text = failed.joinToString("\n") { it.first }
                error = context.getString(R.string.torrent_batch_failed, list.size - failed.size, list.size) + "\n" +
                    failed.joinToString("\n") { (link, reason) -> "• ${shorten(link)} — $reason" }
            }
        }
    }

    fun submit() = when {
        links.size > 1 -> addAll(links)
        links.size == 1 -> add(TorrentSource.Link(links.single()))
        else -> Unit
    }

    fun paste() {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        val pasted = clip?.takeIf { it.itemCount > 0 }
            ?.let { c -> (0 until c.itemCount).joinToString("\n") { c.getItemAt(it).coerceToText(context).toString() } }
            ?.takeIf { it.isNotBlank() }
        if (pasted == null) {
            error = context.getString(R.string.torrent_clipboard_empty)
            return
        }
        error = null
        text = if (text.isBlank()) pasted.trim() else text.trimEnd() + "\n" + pasted.trim()
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) add(TorrentSource.File(uri))
    }
    // A .torrent file opened with BookSound: the user already chose it, check it right away.
    LaunchedEffect(initial) {
        if (initial is TorrentSource.File) add(initial)
    }

    AlertDialog(
        onDismissRequest = ::close,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.torrent_add_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    stringResource(R.string.torrent_requirements),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    label = { Text(stringResource(R.string.torrent_link_label)) },
                    placeholder = { Text("magnet:?xt=urn:btih:…") },
                    supportingText = { Text(stringResource(R.string.torrent_links_hint)) },
                    singleLine = false,
                    minLines = 2,
                    maxLines = 6,
                    enabled = !busy,
                    isError = error != null,
                    // Enter starts a new line: each line is one link.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    TonalButton(
                        stringResource(R.string.action_paste),
                        onClick = ::paste,
                        icon = Icons.Rounded.ContentPaste,
                        enabled = !busy,
                    )
                    TonalButton(
                        stringResource(R.string.action_choose_torrent_file),
                        onClick = { pickFile.launch(arrayOf("application/x-bittorrent", "application/octet-stream")) },
                        icon = Icons.Rounded.FileOpen,
                        enabled = !busy,
                    )
                }
                when {
                    busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingDots()
                        Spacer(Modifier.width(Spacing.md))
                        Text(
                            batchProgress?.let { (done, total) -> stringResource(R.string.torrent_adding_many, done, total) }
                                ?: stringResource(R.string.torrent_adding),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    error != null -> Text(error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit, enabled = links.isNotEmpty() && !busy, shape = Radii.pill) {
                Text(
                    if (links.size > 1) stringResource(R.string.action_add_count, links.size) else stringResource(R.string.action_add),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = ::close, shape = Radii.pill) {
                Text(stringResource(if (batchAdded > 0) R.string.action_close else R.string.action_cancel), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/** Keeps long magnet links readable in the error list. */
private fun shorten(link: String): String =
    TorrentLinks.parseMagnet(link)?.displayName ?: if (link.length > 48) link.take(45) + "…" else link

private fun addErrorMessage(context: Context, result: AddResult.Rejected): String = when (result.error) {
    AddError.NOT_A_LINK -> context.getString(R.string.torrent_error_not_a_link)
    AddError.INVALID_TORRENT -> context.getString(R.string.torrent_error_invalid)
    AddError.UNREADABLE_FILE -> context.getString(R.string.torrent_error_unreadable)
    AddError.DOWNLOAD_FAILED -> context.getString(R.string.torrent_error_download)
    AddError.OFFLINE -> context.getString(R.string.torrent_error_offline)
    AddError.CONTENT_INVALID -> problemMessage(context, result.problem ?: TorrentContentProblem.NO_AUDIO, result.files)
}

/** Why a torrent is not a single MP3 or M4B audiobook, naming the files concerned. */
fun problemMessage(context: Context, problem: TorrentContentProblem, files: List<String>): String {
    val names = files.take(3).joinToString(", ") { it.substringAfterLast('/') } + if (files.size > 3) ", …" else ""
    return when (problem) {
        TorrentContentProblem.EMPTY -> context.getString(R.string.torrent_problem_empty)
        TorrentContentProblem.UNSAFE_PATH -> context.getString(R.string.torrent_problem_unsafe)
        TorrentContentProblem.UNSUPPORTED_FILES -> context.getString(R.string.torrent_problem_unsupported, names)
        TorrentContentProblem.NO_AUDIO -> context.getString(R.string.torrent_problem_no_audio)
        TorrentContentProblem.MIXED_FORMATS -> context.getString(R.string.torrent_problem_mixed)
        TorrentContentProblem.MULTIPLE_M4B -> context.getString(R.string.torrent_problem_multiple_m4b)
        TorrentContentProblem.EMPTY_AUDIO_FILE -> context.getString(R.string.torrent_problem_empty_file, names)
    }
}
