package com.zyagodin.booksound.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyagodin.booksound.R
import com.zyagodin.booksound.ui.AppNavigator
import com.zyagodin.booksound.ui.components.BookCover
import com.zyagodin.booksound.ui.components.MessageState
import com.zyagodin.booksound.ui.components.QuietButton
import com.zyagodin.booksound.ui.navigation.appContainer
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing
import kotlinx.coroutines.launch

/** Books removed from the library whose files were kept; they can be restored or forgotten. */
@Composable
fun RemovedBooksScreen(navigator: AppNavigator) {
    val container = appContainer()
    val removed by container.library.removed.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = navigator::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back)) }
            Text(stringResource(R.string.settings_removed_books), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = Spacing.sm))
        }
        if (removed.isEmpty()) {
            MessageState(
                icon = Icons.Rounded.DeleteSweep,
                title = stringResource(R.string.removed_empty_title),
                message = stringResource(R.string.removed_empty_message),
                iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
                iconBackground = MaterialTheme.colorScheme.surfaceContainerHigh,
            )
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = Spacing.lg, end = Spacing.lg, bottom = bottom + Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Text(
                        stringResource(R.string.removed_explanation),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 720.dp).padding(bottom = Spacing.sm),
                    )
                }
                items(removed, key = { it.id }) { book ->
                    Surface(shape = Radii.card, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                        Row(Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                            BookCover(null, book.title, book.author, Modifier.size(52.dp))
                            Spacer(Modifier.width(Spacing.md))
                            Column(Modifier.weight(1f)) {
                                Text(book.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(book.relativePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            QuietButton(stringResource(R.string.action_forget), {
                                scope.launch { container.library.forget(book.id) }
                            }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            QuietButton(stringResource(R.string.action_restore), {
                                scope.launch {
                                    val ok = container.library.restore(book.id)
                                    if (ok) container.scanner.scan()
                                    snackbar.showSnackbar(context.getString(if (ok) R.string.restored else R.string.restore_failed))
                                }
                            })
                        }
                    }
                }
            }
        }
    }
    SnackbarHost(snackbar)
}
