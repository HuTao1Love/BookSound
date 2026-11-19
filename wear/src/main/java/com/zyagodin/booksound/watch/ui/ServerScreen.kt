package com.zyagodin.booksound.watch.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
import com.zyagodin.booksound.api.ErrorCodes
import com.zyagodin.booksound.api.LibraryEntryDto
import com.zyagodin.booksound.api.WatchProfileDto
import com.zyagodin.booksound.shared.server.ServerException
import com.zyagodin.booksound.watch.R
import com.zyagodin.booksound.watch.WatchContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The user's BookSound server: signing the watch in (by itself, or with the phone's account), the books not yet on
 * the watch to download over Wi-Fi or LTE, the quality of the copies, and signing out.
 */
@Composable
fun ServerScreen(container: WatchContainer) {
    val server = container.server
    val account by server.account.collectAsStateWithLifecycle()
    val signedOutHere by server.signedOutHere.collectAsStateWithLifecycle()
    val books by container.library.books.collectAsStateWithLifecycle()
    val downloads by server.downloads.collectAsStateWithLifecycle()
    val profileId by server.profileId.collectAsStateWithLifecycle()
    var catalog by remember { mutableStateOf<List<LibraryEntryDto>?>(null) }
    var profiles by remember { mutableStateOf<List<WatchProfileDto>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState = rememberTransformingLazyColumnState()

    LaunchedEffect(account?.url, account?.deviceId) {
        catalog = null
        if (account == null) return@LaunchedEffect
        error = null
        catalog = runCatching { server.catalog() }.onFailure { error = it.message ?: "" }.getOrNull()
        profiles = runCatching { server.profiles() }.getOrDefault(emptyList())
    }

    // Ask the phone right away, unless the user signed out here: it answers with its account, or nothing.
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
    LaunchedEffect(Unit) { if (account == null && !signedOutHere) ask() }

    // Signing in on the watch itself.
    var form by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable { mutableStateOf("") }
    var user by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var signingIn by remember { mutableStateOf(false) }
    var signInError by remember { mutableStateOf<String?>(null) }
    val wrongPassword = stringResource(R.string.server_wrong_password)
    val tooManyAttempts = stringResource(R.string.server_too_many_attempts)
    val unreachable = stringResource(R.string.server_unreachable)
    val signIn: () -> Unit = {
        if (!signingIn && url.isNotBlank() && user.isNotBlank() && password.isNotEmpty()) {
            scope.launch {
                signingIn = true
                signInError = null
                try {
                    server.signIn(url, user, password)
                    password = ""
                    form = false
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: ServerException) {
                    signInError = when (e.code) {
                        ErrorCodes.INVALID_CREDENTIALS -> wrongPassword
                        ErrorCodes.TOO_MANY_ATTEMPTS -> tooManyAttempts
                        else -> e.message
                    }
                } catch (e: Exception) {
                    signInError = unreachable
                } finally {
                    signingIn = false
                }
            }
        }
    }

    // Sign out takes a second tap, so a stray touch doesn't drop the account.
    var confirmSignOut by remember { mutableStateOf(false) }
    LaunchedEffect(confirmSignOut) {
        if (confirmSignOut) {
            delay(4_000)
            confirmSignOut = false
        }
    }

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item { ListHeader { Text(stringResource(R.string.server_title)) } }
            if (account == null && form) {
                item { Field(url, { url = it }, stringResource(R.string.server_address), KeyboardType.Uri, ImeAction.Next) }
                item { Field(user, { user = it }, stringResource(R.string.server_user), KeyboardType.Text, ImeAction.Next) }
                item { Field(password, { password = it }, stringResource(R.string.server_password), KeyboardType.Password, ImeAction.Done, secret = true, onDone = signIn) }
                signInError?.let { message ->
                    item { Note(message) }
                }
                item {
                    Button(
                        onClick = signIn,
                        enabled = !signingIn && url.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        icon = { Icon(Icons.AutoMirrored.Rounded.Login, contentDescription = null) },
                        label = { Text(stringResource(if (signingIn) R.string.server_signing_in else R.string.server_sign_in)) },
                    )
                }
                item {
                    Button(
                        onClick = { form = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(),
                        border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                        label = { Text(stringResource(R.string.server_cancel)) },
                    )
                }
            } else if (account == null) {
                item {
                    Note(
                        stringResource(
                            when {
                                asking -> R.string.server_asking_phone
                                phoneMissing -> R.string.server_phone_missing
                                else -> R.string.server_not_connected
                            },
                        ),
                    )
                }
                item {
                    Button(
                        onClick = { form = true },
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(Icons.Rounded.Watch, contentDescription = null) },
                        label = { Text(stringResource(R.string.server_sign_in_watch)) },
                    )
                }
                item {
                    Button(
                        onClick = ask,
                        enabled = !asking,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.filledTonalButtonColors(),
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
                    item { Note(stringResource(R.string.server_all_here)) }
                }
                error?.let { message ->
                    item { Note(message) }
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
                if (profiles.isNotEmpty()) {
                    item {
                        // Cycles original → each profile → original.
                        val options = listOf<WatchProfileDto?>(null) + profiles
                        val current = options.indexOfFirst { it?.id == profileId }.coerceAtLeast(0)
                        Button(
                            onClick = { server.setProfile(options[(current + 1) % options.size]?.id) },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(),
                            border = ButtonDefaults.outlinedButtonBorder(enabled = true),
                            icon = { Icon(Icons.Rounded.HighQuality, contentDescription = null) },
                            label = { Text(stringResource(R.string.server_quality)) },
                            secondaryLabel = {
                                Text(options[current]?.let { "${it.name} · ${it.bitrateKbps} kbps" } ?: stringResource(R.string.server_quality_original), maxLines = 1)
                            },
                        )
                    }
                }
                item {
                    Button(
                        onClick = {
                            if (confirmSignOut) {
                                confirmSignOut = false
                                server.signOut()
                            } else {
                                confirmSignOut = true
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = if (confirmSignOut) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer) else ButtonDefaults.outlinedButtonColors(),
                        border = if (confirmSignOut) null else ButtonDefaults.outlinedButtonBorder(enabled = true),
                        icon = { Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = null) },
                        label = { Text(stringResource(if (confirmSignOut) R.string.server_sign_out_confirm else R.string.server_sign_out)) },
                        secondaryLabel = {
                            Text(
                                listOfNotNull(account?.userName?.takeIf { it.isNotBlank() }, account?.url?.removePrefix("https://")).joinToString(" · "),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
    )
}

/** A one-line text field; tapping it opens the watch's keyboard. */
@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    type: KeyboardType,
    ime: ImeAction,
    secret: Boolean = false,
    onDone: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = type, imeAction = ime, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth()
                    .background(colors.surfaceContainer, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1)
                inner()
            }
        },
    )
}
