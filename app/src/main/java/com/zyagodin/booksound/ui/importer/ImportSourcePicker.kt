package com.zyagodin.booksound.ui.importer

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.zyagodin.booksound.R
import com.zyagodin.booksound.importer.ImportSelection
import com.zyagodin.booksound.ui.AppNavigator
import com.zyagodin.booksound.ui.components.AppBottomSheet
import com.zyagodin.booksound.ui.navigation.appContainer
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * Where a new book comes from: files, a folder or a torrent. The pickers stay registered while
 * the sheet is hidden, since their results arrive after it has closed.
 */
@Composable
fun ImportSourcePicker(navigator: AppNavigator, visible: Boolean, onDismiss: () -> Unit) {
    val container = appContainer()
    val context = LocalContext.current
    fun startImport(selection: ImportSelection) {
        val session = container.importSessions.create(selection)
        session.analysisJob = container.appScope.launch { container.importAnalyzer.analyze(session) }
        navigator.openImportEditor(session.id)
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun ensureNotificationPermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) startImport(ImportSelection.Documents(uris))
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) startImport(ImportSelection.Folder(uri))
    }

    if (visible) {
        ImportSourceSheet(
            onDismiss = onDismiss,
            onPickFiles = {
                onDismiss()
                ensureNotificationPermission()
                pickFiles.launch(arrayOf("audio/*", "video/mp4", "application/octet-stream", "image/*"))
            },
            onPickFolder = {
                onDismiss()
                ensureNotificationPermission()
                pickFolder.launch(null)
            },
            onAddTorrent = {
                onDismiss()
                ensureNotificationPermission()
                navigator.addTorrent()
            },
        )
    }
}

@Composable
private fun ImportSourceSheet(onDismiss: () -> Unit, onPickFiles: () -> Unit, onPickFolder: () -> Unit, onAddTorrent: () -> Unit) {
    AppBottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl)) {
            Text(stringResource(R.string.import_sheet_title), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(Spacing.sm))
            Text(
                stringResource(R.string.import_sheet_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.xl))
            ImportOption(Icons.Rounded.AudioFile, stringResource(R.string.import_option_files), stringResource(R.string.import_option_files_hint), onPickFiles)
            Spacer(Modifier.height(Spacing.md))
            ImportOption(Icons.Rounded.FolderOpen, stringResource(R.string.import_option_folder), stringResource(R.string.import_option_folder_hint), onPickFolder)
            Spacer(Modifier.height(Spacing.md))
            ImportOption(Icons.Rounded.Download, stringResource(R.string.import_option_torrent), stringResource(R.string.import_option_torrent_hint), onAddTorrent)
        }
    }
}

@Composable
private fun ImportOption(icon: ImageVector, title: String, hint: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = Radii.card, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(52.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(Modifier.width(Spacing.lg))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
