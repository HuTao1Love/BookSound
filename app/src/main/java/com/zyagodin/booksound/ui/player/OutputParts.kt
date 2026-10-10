package com.zyagodin.booksound.ui.player

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BluetoothAudio
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.SpeakerGroup
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.zyagodin.booksound.R
import com.zyagodin.booksound.cast.CastProblem
import com.zyagodin.booksound.cast.CastSessionState
import com.zyagodin.booksound.cast.CastTarget
import com.zyagodin.booksound.cast.CastUiState
import com.zyagodin.booksound.playback.AudioOutput
import com.zyagodin.booksound.ui.components.AppBottomSheet
import com.zyagodin.booksound.ui.components.CircleIconButton
import com.zyagodin.booksound.ui.components.PrimaryButton
import com.zyagodin.booksound.ui.components.QuietButton
import com.zyagodin.booksound.ui.theme.LocalGradients
import com.zyagodin.booksound.ui.theme.Radii
import com.zyagodin.booksound.ui.theme.Spacing

// ------------------------------------------------------------------ the bar under the controls

/**
 * Where the book is heard: the phone's output (Bluetooth headphones, a speaker) with the system
 * picker behind it, and Cast beside it. While casting, the whole bar turns into the cast.
 */
@Composable
internal fun OutputBar(
    output: AudioOutput,
    cast: CastUiState,
    onPickOutput: () -> Unit,
    onCast: () -> Unit,
    onStopCast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = cast.session,
        contentKey = { it != null },
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        modifier = modifier.fillMaxWidth(),
        label = "output",
    ) { session ->
        if (session != null) CastingBar(session, onCast, onStopCast) else LocalOutputBar(output, cast.supported, onPickOutput, onCast)
    }
}

@Composable
private fun LocalOutputBar(output: AudioOutput, castSupported: Boolean, onPickOutput: () -> Unit, onCast: () -> Unit) {
    val external = output.kind != AudioOutput.Kind.PHONE
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        Surface(
            onClick = onPickOutput,
            shape = Radii.pill,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
        ) {
            Row(Modifier.padding(start = Spacing.xs, end = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
                val badge by animateColorAsState(
                    if (external) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    label = "badge",
                )
                Box(Modifier.padding(Spacing.xs).size(36.dp).clip(CircleShape).background(badge), contentAlignment = Alignment.Center) {
                    Icon(
                        outputIcon(output.kind),
                        contentDescription = null,
                        tint = if (external) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(Spacing.sm))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.output_listening_on),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AnimatedContent(outputName(output), label = "outputName") { name ->
                        Text(name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Icon(
                    Icons.Rounded.UnfoldMore,
                    contentDescription = stringResource(R.string.output_choose),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        if (castSupported) {
            CircleIconButton(
                Icons.Rounded.Cast,
                stringResource(R.string.cast_title),
                onCast,
                containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                iconSize = 22.dp,
            )
        }
    }
}

@Composable
private fun CastingBar(session: CastSessionState, onOpen: () -> Unit, onStop: () -> Unit) {
    val gradients = LocalGradients.current
    val busy = session.stage != CastSessionState.Stage.CASTING
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .shadow(16.dp, Radii.pill, ambientColor = gradients.accentColors.last(), spotColor = gradients.accentColors.first())
            .clip(Radii.pill)
            .background(gradients.accent)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(start = Spacing.md, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimary) {
            Icon(Icons.Rounded.CastConnected, contentDescription = null, modifier = Modifier.size(22.dp).then(if (busy) Modifier.alpha(pulse()) else Modifier))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f).padding(vertical = Spacing.sm)) {
                Text(
                    stringResource(sessionStatus(session)),
                    style = MaterialTheme.typography.labelSmall,
                    color = LocalContentColor.current.copy(alpha = 0.8f),
                )
                Text(session.deviceName, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CircleIconButton(
                Icons.Rounded.Close,
                stringResource(R.string.cast_stop),
                onStop,
                size = 40.dp,
                iconSize = 20.dp,
                containerColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f),
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

// ------------------------------------------------------------------ the cast sheet

@Composable
internal fun CastSheet(
    cast: CastUiState,
    bookTitle: String,
    onConnect: (CastTarget) -> Unit,
    onStop: () -> Unit,
    onTogglePlay: () -> Unit,
    onDiscovery: (Boolean) -> Unit,
    onClearProblem: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // Android 17+ asks before an app may see devices on the local network.
    val needsPermission = Build.VERSION.SDK_INT >= 37
    var permitted by remember {
        mutableStateOf(!needsPermission || ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }

    DisposableEffect(permitted) {
        if (permitted) onDiscovery(true)
        onDispose {
            onDiscovery(false)
            onClearProblem()
        }
    }

    AppBottomSheet(onDismiss = onDismiss) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl),
        ) {
            SheetHeader()
            Spacer(Modifier.height(Spacing.xl))

            when {
                !cast.supported -> Notice(Icons.Rounded.ErrorOutline, stringResource(R.string.cast_unsupported))
                !permitted -> {
                    Notice(Icons.Rounded.Cast, stringResource(R.string.cast_permission))
                    Spacer(Modifier.height(Spacing.md))
                    PrimaryButton(
                        stringResource(R.string.cast_permission_action),
                        onClick = { askPermission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {
                    AnimatedVisibility(cast.session != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        val session = cast.session
                        if (session != null) {
                            Column {
                                SessionCard(session, bookTitle, kindOf(session, cast.devices), onTogglePlay)
                                QuietButton(
                                    stringResource(R.string.cast_stop),
                                    onStop,
                                    icon = Icons.Rounded.Close,
                                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = Spacing.sm),
                                )
                                Spacer(Modifier.height(Spacing.lg))
                            }
                        }
                    }
                    cast.problem?.let {
                        Notice(Icons.Rounded.ErrorOutline, stringResource(problemText(it)), error = true)
                        Spacer(Modifier.height(Spacing.lg))
                    }
                    DeviceList(cast, onConnect)
                }
            }
        }
    }
}

@Composable
private fun SheetHeader() {
    val gradients = LocalGradients.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(52.dp)
                .shadow(12.dp, CircleShape, ambientColor = gradients.accentColors.last(), spotColor = gradients.accentColors.first())
                .clip(CircleShape)
                .background(gradients.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Cast, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(Spacing.lg))
        Column {
            Text(stringResource(R.string.cast_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.cast_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The device being cast to, big and bright, with its play button. */
@Composable
private fun SessionCard(session: CastSessionState, bookTitle: String, kind: CastTarget.Kind?, onTogglePlay: () -> Unit) {
    val gradients = LocalGradients.current
    val busy = session.stage != CastSessionState.Stage.CASTING
    Row(
        Modifier
            .fillMaxWidth()
            .shadow(20.dp, Radii.card, ambientColor = gradients.accentColors.last(), spotColor = gradients.accentColors.first())
            .clip(Radii.card)
            .background(gradients.accent)
            .padding(Spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onPrimary) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(kind?.let(::deviceIcon) ?: Icons.Rounded.CastConnected, contentDescription = null, modifier = Modifier.size(28.dp).then(if (busy) Modifier.alpha(pulse()) else Modifier))
            }
            Spacer(Modifier.width(Spacing.lg))
            Column(Modifier.weight(1f)) {
                Text(session.deviceName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(Spacing.xxs))
                Text(
                    stringResource(sessionStatus(session)),
                    style = MaterialTheme.typography.labelMedium,
                    color = LocalContentColor.current.copy(alpha = 0.85f),
                )
                if (!busy) {
                    Text(bookTitle, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(Spacing.md))
            if (busy) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
            } else {
                Surface(onClick = onTogglePlay, shape = CircleShape, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(52.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (session.playing || session.buffering) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(if (session.playing) R.string.action_pause else R.string.action_play),
                            tint = gradients.accentColors.last(),
                            modifier = Modifier.size(30.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceList(cast: CastUiState, onConnect: (CastTarget) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = Spacing.xs, bottom = Spacing.sm)) {
        Text(
            stringResource(R.string.cast_devices).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (cast.devices.isEmpty()) {
        Searching()
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        cast.devices.forEach { device ->
            val session = cast.session
            val current = session != null && session.deviceName == device.name
            DeviceRow(device, current, busy = current && session?.stage != CastSessionState.Stage.CASTING) { if (!current) onConnect(device) }
        }
    }
}

@Composable
private fun DeviceRow(device: CastTarget, current: Boolean, busy: Boolean, onClick: () -> Unit) {
    val container by animateColorAsState(
        if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        label = "device",
    )
    Surface(onClick = onClick, shape = Radii.card, color = container, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = Spacing.md, vertical = Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(deviceIcon(device.kind), contentDescription = null, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                device.description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            when {
                busy -> CircularProgressIndicator(strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
                current -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Waves spreading from the Cast icon while nothing is found yet. */
@Composable
private fun Searching() {
    val gradients = LocalGradients.current
    val transition = rememberInfiniteTransition(label = "radar")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "phase")
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.lg), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(140.dp)) {
                val maxRadius = size.minDimension / 2
                repeat(3) { i ->
                    val t = (phase + i / 3f) % 1f
                    drawCircle(
                        brush = Brush.linearGradient(gradients.accentColors),
                        radius = maxRadius * (0.3f + 0.7f * t),
                        alpha = (1f - t) * 0.35f,
                    )
                }
            }
            Box(Modifier.size(48.dp).clip(CircleShape).background(gradients.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Cast, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
            }
        }
        Text(stringResource(R.string.cast_searching), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            stringResource(R.string.cast_searching_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Notice(icon: ImageVector, text: String, error: Boolean = false) {
    Surface(
        shape = Radii.card,
        color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(Spacing.md))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ------------------------------------------------------------------ helpers

@Composable
private fun pulse(): Float {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "alpha")
    return alpha
}

private fun outputIcon(kind: AudioOutput.Kind): ImageVector = when (kind) {
    AudioOutput.Kind.PHONE -> Icons.Rounded.Smartphone
    AudioOutput.Kind.BLUETOOTH -> Icons.Rounded.BluetoothAudio
    AudioOutput.Kind.WIRED -> Icons.Rounded.Headphones
    AudioOutput.Kind.OTHER -> Icons.Rounded.Speaker
}

@Composable
private fun outputName(output: AudioOutput): String = output.name ?: stringResource(
    when (output.kind) {
        AudioOutput.Kind.PHONE -> R.string.output_this_phone
        AudioOutput.Kind.BLUETOOTH -> R.string.output_bluetooth
        AudioOutput.Kind.WIRED -> R.string.output_headphones
        AudioOutput.Kind.OTHER -> R.string.output_other
    },
)

private fun deviceIcon(kind: CastTarget.Kind): ImageVector = when (kind) {
    CastTarget.Kind.TV -> Icons.Rounded.Tv
    CastTarget.Kind.SPEAKER -> Icons.Rounded.Speaker
    CastTarget.Kind.GROUP -> Icons.Rounded.SpeakerGroup
}

private fun kindOf(session: CastSessionState, devices: List<CastTarget>): CastTarget.Kind? =
    devices.firstOrNull { it.name == session.deviceName }?.kind

private fun sessionStatus(session: CastSessionState): Int = when (session.stage) {
    CastSessionState.Stage.CONNECTING -> R.string.cast_connecting
    CastSessionState.Stage.LOADING -> R.string.cast_loading
    CastSessionState.Stage.CASTING -> if (session.playing || session.buffering) R.string.cast_playing else R.string.cast_paused
}

private fun problemText(problem: CastProblem): Int = when (problem) {
    CastProblem.NO_NETWORK -> R.string.cast_problem_network
    CastProblem.CONNECTION_FAILED -> R.string.cast_problem_connect
    CastProblem.PLAYBACK_FAILED -> R.string.cast_problem_playback
}
