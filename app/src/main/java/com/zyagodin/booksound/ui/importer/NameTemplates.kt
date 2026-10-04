package com.zyagodin.booksound.ui.importer

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zyagodin.booksound.R
import com.zyagodin.booksound.core.organize.NameField
import com.zyagodin.booksound.core.organize.NameTemplate
import com.zyagodin.booksound.ui.components.ConfirmDialog
import com.zyagodin.booksound.ui.components.SectionHeader
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing

/**
 * Saved name templates as chips. A chip that fits the source name fills the details when
 * tapped; holding a chip deletes the template.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun NameTemplateSection(ui: EditorUi, onApply: (String) -> Unit, onAdd: (String) -> Unit, onDelete: (String) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    Column(Modifier.padding(top = Spacing.xl)) {
        SectionHeader(
            stringResource(R.string.template_section),
            trailing = {
                IconButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, stringResource(R.string.template_add)) }
            },
        )
        Text(
            ui.sourceName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
        ) {
            ui.templates.forEach { option ->
                val fits = option.fields != null
                val selected = option.template == ui.appliedTemplate
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .combinedClickable(
                            enabled = true,
                            onClick = { if (fits) onApply(option.template) },
                            onLongClick = { deleting = option.template },
                        )
                        .alpha(if (fits) 1f else 0.45f),
                ) {
                    Text(
                        option.template,
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    )
                }
            }
        }
        Text(
            stringResource(R.string.template_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
    if (adding) {
        NameTemplateDialog(
            sourceName = ui.sourceName,
            onSave = {
                onAdd(it)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
    deleting?.let { template ->
        ConfirmDialog(
            title = stringResource(R.string.template_delete_title),
            message = template,
            confirmText = stringResource(R.string.action_remove),
            onConfirm = {
                onDelete(template)
                deleting = null
            },
            onDismiss = { deleting = null },
            dismissText = stringResource(R.string.action_cancel),
            destructive = true,
        )
    }
}

/**
 * Editing a new template with a live preview of what it reads from [sourceName]. It starts from
 * the source name itself: select a part and tap a field to replace that part with its placeholder.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameTemplateDialog(sourceName: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(sourceName, TextRange(sourceName.length)))
    }
    val text = value.text
    val problem = NameTemplate.validate(text)
    val fields = if (problem == null) NameTemplate.parse(text, sourceName) else null
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text(stringResource(R.string.template_dialog_title), style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.copy(text = it.text.replace('\n', ' ')) },
                    singleLine = true,
                    isError = problem != null,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.md),
                ) {
                    INSERTABLE.forEach { (field, token) ->
                        AssistChip(
                            onClick = { value = value.replacingSelection(token) },
                            label = {
                                Text(
                                    if (field == NameField.SKIP) stringResource(R.string.template_insert_skip) else fieldLabel(context, field),
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                        )
                    }
                }
                Text(
                    stringResource(R.string.template_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
                val preview = when {
                    problem != null -> problemText(context, problem)
                    fields == null -> context.getString(R.string.template_no_match)
                    else -> fields.entries.joinToString("\n") { (field, value) -> fieldLabel(context, field) + ": " + value }
                }
                Text(
                    preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (fields != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = Spacing.md),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = problem == null, shape = Radii.pill) {
                Text(stringResource(R.string.action_save), style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = Radii.pill) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

/** Fields offered as buttons in [NameTemplateDialog], with the placeholder each one inserts. */
private val INSERTABLE = listOf(
    NameField.AUTHOR to "%author%",
    NameField.TITLE to "%title%",
    NameField.SERIES to "%series%",
    NameField.NUMBER to "%number%",
    NameField.NARRATOR to "%narrator%",
    NameField.YEAR to "%year%",
    NameField.SKIP to "%*%",
)

/** Puts [token] in place of the selected text, or at the cursor, and moves the cursor after it. */
private fun TextFieldValue.replacingSelection(token: String): TextFieldValue {
    val start = minOf(selection.start, selection.end).coerceIn(0, text.length)
    val end = maxOf(selection.start, selection.end).coerceIn(0, text.length)
    return TextFieldValue(text.replaceRange(start, end, token), TextRange(start + token.length))
}

private fun fieldLabel(context: Context, field: NameField): String = context.getString(
    when (field) {
        NameField.AUTHOR -> R.string.field_author
        NameField.TITLE -> R.string.field_title
        NameField.SERIES -> R.string.field_series
        NameField.NUMBER -> R.string.field_series_number
        NameField.NARRATOR -> R.string.field_narrator
        NameField.YEAR -> R.string.field_year
        NameField.SKIP -> R.string.template_skipped
    },
)

private fun problemText(context: Context, problem: NameTemplate.Problem): String = when (problem) {
    NameTemplate.Problem.NoPlaceholders -> context.getString(R.string.template_problem_none, "%title%")
    is NameTemplate.Problem.UnknownPlaceholder -> context.getString(R.string.template_problem_unknown, "%" + problem.name + "%")
    is NameTemplate.Problem.Duplicate -> context.getString(R.string.template_problem_duplicate, fieldLabel(context, problem.field))
    NameTemplate.Problem.AdjacentPlaceholders -> context.getString(R.string.template_problem_adjacent)
}
