package com.zyagodin.booksound.ui.onboarding

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyagodin.booksound.R
import com.zyagodin.booksound.ui.components.BookCover
import com.zyagodin.booksound.ui.components.PrimaryButton
import com.zyagodin.booksound.ui.navigation.appContainer
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing
import kotlinx.coroutines.launch

/** First run (or lost folder access): the user picks the folder that holds the library. */
@Composable
fun OnboardingScreen(folderLost: Boolean, onGranted: () -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    var failed by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching { container.documents.takePersistablePermission(uri, write = true) }.isSuccess
        failed = !ok
        if (ok) scope.launch {
            container.settings.setLibraryTree(uri.toString())
            onGranted()
        }
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 520.dp).verticalScroll(rememberScrollState()).padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BookStack()
            Spacer(Modifier.height(Spacing.xxl))
            Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.displaySmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Spacing.md))
            Text(
                stringResource(R.string.onboarding_message),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.xl))
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md), modifier = Modifier.fillMaxWidth()) {
                Feature(Icons.Rounded.Folder, stringResource(R.string.onboarding_feature_files))
                Feature(Icons.Rounded.AutoAwesome, stringResource(R.string.onboarding_feature_import))
                Feature(Icons.Rounded.Headphones, stringResource(R.string.onboarding_feature_player))
            }
            if (folderLost || failed) {
                Spacer(Modifier.height(Spacing.xl))
                Surface(shape = Radii.card, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                        Spacer(Modifier.width(Spacing.md))
                        Text(
                            stringResource(if (failed) R.string.onboarding_permission_failed else R.string.onboarding_folder_lost),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.xxl))
            PrimaryButton(
                stringResource(R.string.onboarding_choose_folder),
                onClick = {
                    // Suggest "Audiobooks" on internal storage as the starting location.
                    picker.launch(DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Audiobooks"))
                },
                icon = Icons.Rounded.Folder,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.md))
            Text(
                stringResource(R.string.onboarding_tip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BookStack() {
    Box(Modifier.size(width = 220.dp, height = 170.dp), contentAlignment = Alignment.Center) {
        BookCover(null, "Dune", "F. Herbert", Modifier.size(112.dp).offset(x = (-64).dp, y = 10.dp).rotate(-10f), elevation = 8.dp)
        BookCover(null, "Мастер и Маргарита", "М. Булгаков", Modifier.size(112.dp).offset(x = 64.dp, y = 10.dp).rotate(10f), elevation = 8.dp)
        BookCover(null, "BookSound", null, Modifier.size(132.dp), elevation = 14.dp)
    }
}

@Composable
private fun Feature(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(22.dp)) }
        }
        Spacer(Modifier.width(Spacing.lg))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
