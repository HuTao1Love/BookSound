package com.zyagodin.booksound.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing

/** App-styled modal bottom sheet. On wide screens it is limited in width and centred. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    onDismiss: () -> Unit,
    skipPartiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        shape = Radii.sheet,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetMaxWidth = 640.dp,
        dragHandle = {
            Box(Modifier.padding(top = Spacing.md, bottom = Spacing.sm)) {
                Surface(
                    shape = Radii.pill,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                    modifier = Modifier.size(width = 36.dp, height = 4.dp),
                ) {}
            }
        },
        contentWindowInsets = { BottomSheetDefaults.windowInsets },
        content = content,
    )
}

/** App-styled confirmation dialog. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String?,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String,
    destructive: Boolean = false,
    extraContent: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            androidx.compose.foundation.layout.Column {
                if (message != null) Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                extraContent?.invoke()
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, shape = Radii.pill) {
                Text(
                    confirmText,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = Radii.pill) {
                Text(dismissText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/** Dialog offering several stacked choices (e.g. Replace / Keep both / Cancel). */
@Composable
fun ChoiceDialog(
    title: String,
    message: String?,
    choices: List<Choice>,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            androidx.compose.foundation.layout.Column(Modifier.padding(Spacing.xl)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                if (message != null) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.md),
                    )
                }
                androidx.compose.foundation.layout.Spacer(Modifier.size(Spacing.lg))
                choices.forEach { choice ->
                    TextButton(
                        onClick = choice.onClick,
                        shape = Radii.pill,
                        modifier = Modifier.align(androidx.compose.ui.Alignment.End),
                    ) {
                        Text(
                            choice.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = when (choice.style) {
                                ChoiceStyle.DESTRUCTIVE -> MaterialTheme.colorScheme.error
                                ChoiceStyle.PRIMARY -> MaterialTheme.colorScheme.primary
                                ChoiceStyle.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

enum class ChoiceStyle { PRIMARY, DESTRUCTIVE, NEUTRAL }

class Choice(val label: String, val style: ChoiceStyle = ChoiceStyle.PRIMARY, val onClick: () -> Unit)

/** Single text field dialog, e.g. to rename a chapter. */
@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirmText: String,
    dismissText: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(title, style = MaterialTheme.typography.headlineSmall) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank(), shape = Radii.pill) {
                Text(confirmText, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = Radii.pill) {
                Text(dismissText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}
