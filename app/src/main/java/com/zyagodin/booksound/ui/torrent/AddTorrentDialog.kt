package com.zyagodin.booksound.ui.torrent

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.torrent.TorrentContentProblem
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
 * [initial] is a link or file opened from another app.
 */
@Composable
fun AddTorrentDialog(
    initial: TorrentSource?,
    onDismiss: () -> Unit,
    onAdded: (torrentId: String) -> Unit,
    onAlreadyAdded: (torrentId: String) -> Unit,
) {
    val context = LocalContext.current
    val torrents = appContainer().torrents
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf((initial as? TorrentSource.Link)?.text.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

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
                is AddResult.Added -> onAdded(result.torrentId)
                is AddResult.AlreadyAdded -> onAlreadyAdded(result.torrentId)
                is AddResult.Rejected -> error = addErrorMessage(context, result)
            }
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) add(TorrentSource.File(uri))
    }
    // A .torrent file opened with BookSound: the user already chose it, check it right away.
    LaunchedEffect(initial) {
        if (initial is TorrentSource.File) add(initial)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.torrent_add_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
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
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) add(TorrentSource.Link(text)) }),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                TonalButton(
                    stringResource(R.string.action_choose_torrent_file),
                    onClick = { pickFile.launch(arrayOf("application/x-bittorrent", "application/octet-stream")) },
                    icon = Icons.Rounded.FileOpen,
                    enabled = !busy,
                )
                when {
                    busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        LoadingDots()
                        Spacer(Modifier.width(Spacing.md))
                        Text(stringResource(R.string.torrent_adding), style = MaterialTheme.typography.bodyMedium)
                    }
                    error != null -> Text(error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { add(TorrentSource.Link(text)) }, enabled = text.isNotBlank() && !busy, shape = Radii.pill) {
                Text(stringResource(R.string.action_add), style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = Radii.pill) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

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
