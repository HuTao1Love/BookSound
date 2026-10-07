package com.zyagodin.booksound.watch.surfaces

import android.content.ComponentName
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.wear.protolayout.ActionBuilders.booleanExtra
import androidx.wear.protolayout.ActionBuilders.launchAction
import androidx.wear.protolayout.ActionBuilders.stringExtra
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.layout.androidImageResource
import androidx.wear.protolayout.layout.box
import androidx.wear.protolayout.layout.column
import androidx.wear.protolayout.layout.imageResource
import androidx.wear.protolayout.layout.spacer
import androidx.wear.protolayout.material3.ButtonDefaults.filledButtonColors
import androidx.wear.protolayout.material3.ButtonDefaults.filledTonalButtonColors
import androidx.wear.protolayout.material3.IconButtonStyle.Companion.defaultIconButtonStyle
import androidx.wear.protolayout.material3.IconButtonStyle.Companion.largeIconButtonStyle
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.buttonGroup
import androidx.wear.protolayout.material3.icon
import androidx.wear.protolayout.material3.iconButton
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders.ResourcesRequest
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import androidx.wear.tiles.tile
import androidx.wear.tiles.timeline
import androidx.wear.tiles.timelineEntry
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.zyagodin.booksound.watch.R
import com.zyagodin.booksound.watch.WatchApp
import com.zyagodin.booksound.watch.playback.WatchPlaybackService
import com.zyagodin.booksound.watch.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The book being listened to, with its controls. Pause and skip act in place; play opens the
 * player, as only a visible app may start playing in the background (and pick headphones).
 *
 * Icons are declared in [onTileResourcesRequest] rather than registered automatically by
 * Material3TileService: renderers that ask for resources separately got none that way.
 */
class BookTileService : TileService() {
    private val container by lazy { (application as WatchApp).container }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onTileRequest(requestParams: TileRequest): ListenableFuture<Tile> {
        val result = SettableFuture.create<Tile>()
        scope.launch {
            try {
                result.set(tile(requestParams))
            } catch (e: Exception) {
                result.setException(e)
                if (e is CancellationException) throw e
            }
        }
        return result
    }

    override fun onTileResourcesRequest(requestParams: ResourcesRequest): ListenableFuture<Resources> =
        Futures.immediateFuture(
            Resources.Builder()
                .setVersion(RESOURCES_VERSION)
                .apply { ICONS.forEach { (id, drawable) -> addIdToImageMapping(id, imageResource(androidImageResource(drawable))) } }
                .build(),
        )

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun tile(requestParams: TileRequest): Tile {
        var shown = container.surfaces.current()
        val clicked = requestParams.currentState.lastClickableId
        if (clicked.isNotEmpty() && clicked != handledClick) {
            handledClick = clicked
            val playing = when (clicked.substringBefore(':')) {
                ID_PAUSE -> control { it.pause() }
                ID_BACK -> control { it.seekBack() }
                ID_FORWARD -> control { it.seekForward() }
                else -> null
            }
            if (shown != null && playing != null) shown = shown.copy(playing = playing)
        }
        val layout = materialScope(this, requestParams.deviceConfiguration) { layout(shown) }
        return tile(timeline(timelineEntry(layout)), resourcesVersion = RESOURCES_VERSION)
    }

    private fun MaterialScope.layout(shown: Shown?): LayoutElement {
        val open = launch(ID_OPEN, play = null)
        if (shown == null) {
            return primaryLayout(
                titleSlot = { text(getString(R.string.app_name).layoutString) },
                mainSlot = { text(getString(R.string.tile_no_books).layoutString, typography = Typography.BODY_MEDIUM, maxLines = 3) },
                bottomSlot = { textEdgeButton(onClick = open) { text(getString(R.string.open).layoutString) } },
            )
        }
        val controls = if (shown.loaded) {
            buttonGroup(height = expand()) {
                buttonGroupItem { controlButton(ICON_BACK, R.string.skip_back, load(ID_BACK), tonal = true) }
                buttonGroupItem {
                    if (shown.playing) controlButton(ICON_PAUSE, R.string.pause, load(ID_PAUSE))
                    else controlButton(ICON_PLAY, R.string.play, launch(ID_PLAY, play = shown.bookId))
                }
                buttonGroupItem { controlButton(ICON_FORWARD, R.string.skip_forward, load(ID_FORWARD), tonal = true) }
            }
        } else {
            // Not in the player yet: nothing to skip, a single round button starts it.
            box(
                controlButton(ICON_PLAY, R.string.play, launch(ID_PLAY, play = shown.bookId), size = PLAY_SIZE_DP),
                width = expand(),
                height = expand(),
            )
        }
        val subtitle = shown.subtitle?.let {
            text(it.layoutString, typography = Typography.BODY_SMALL, color = colorScheme.onSurfaceVariant, maxLines = 1)
        }
        return primaryLayout(
            titleSlot = { text(shown.title.layoutString) },
            mainSlot = {
                if (subtitle == null) controls
                else column(subtitle, spacer(height = dp(SPACING_DP)), controls, width = expand(), height = expand(), horizontalAlignment = HORIZONTAL_ALIGN_CENTER)
            },
            bottomSlot = {
                val progress = if (shown.finished) getString(R.string.book_finished) else getString(R.string.tile_percent, shown.percent)
                text(progress.layoutString, typography = Typography.LABEL_MEDIUM, color = colorScheme.onSurfaceVariant)
            },
            onClick = open,
        )
    }

    /** Fills its share of the button group, or is a circle of [size] dp. */
    @Suppress("DEPRECATION") // icon(id): the resources are declared in onTileResourcesRequest.
    private fun MaterialScope.controlButton(iconId: String, @StringRes label: Int, onClick: Clickable, tonal: Boolean = false, size: Float? = null) =
        iconButton(
            onClick = onClick,
            iconContent = { icon(iconId) },
            modifier = LayoutModifier.contentDescription(getString(label)),
            width = size?.let { dp(it) } ?: expand(),
            height = size?.let { dp(it) } ?: expand(),
            colors = if (tonal) filledTonalButtonColors() else filledButtonColors(),
            style = if (size != null) largeIconButtonStyle() else defaultIconButtonStyle(),
        )

    /** Refreshes the tile, which then runs the command; a new id each time, so it runs once. */
    private fun load(command: String) = clickable(id = "$command:${System.nanoTime()}")

    /** Opens the player, and starts [play] in it. */
    private fun launch(id: String, play: String?) = clickable(
        action = launchAction(
            ComponentName(this, MainActivity::class.java),
            buildMap {
                put(MainActivity.EXTRA_OPEN_PLAYER, booleanExtra(true))
                play?.let { put(MainActivity.EXTRA_PLAY_BOOK, stringExtra(it)) }
            },
        ),
        id = id,
    )

    /** Sends [command] to the playback service; returns whether it then plays. */
    private suspend fun control(command: (MediaController) -> Unit): Boolean? {
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, WatchPlaybackService::class.java))).buildAsync()
        try {
            val controller = future.await()
            command(controller)
            return controller.playWhenReady
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        } finally {
            MediaController.releaseFuture(future)
        }
    }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { cont ->
        addListener({ cont.resumeWith(runCatching { get() }) }, ContextCompat.getMainExecutor(this@BookTileService))
        cont.invokeOnCancellation { cancel(false) }
    }

    private companion object {
        const val ID_OPEN = "open"
        const val ID_PLAY = "play"
        const val ID_PAUSE = "pause"
        const val ID_BACK = "back"
        const val ID_FORWARD = "forward"
        const val SPACING_DP = 4f
        const val PLAY_SIZE_DP = 64f

        /** Change when the icons change, so the renderer fetches them again. */
        const val RESOURCES_VERSION = "1"
        const val ICON_PLAY = "play"
        const val ICON_PAUSE = "pause"
        const val ICON_BACK = "back"
        const val ICON_FORWARD = "forward"
        val ICONS = mapOf(
            ICON_PLAY to R.drawable.tile_play,
            ICON_PAUSE to R.drawable.tile_pause,
            ICON_BACK to R.drawable.tile_back,
            ICON_FORWARD to R.drawable.tile_forward,
        )

        /** The tile's last command; a refresh of the tile repeats its id, which must not run it again. */
        @Volatile var handledClick: String? = null
    }
}
