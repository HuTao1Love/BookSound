package com.zyagodin.booksound.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The player slid up over the screens below. It follows the finger all the way, from the mini
 * player up and from the top back down, and a released drag carries on to the end it was flung
 * towards (or the nearer one if let go slowly) instead of waiting for the finger.
 */
@Stable
class PlayerSheetState internal constructor(
    open: Boolean,
    private val scope: CoroutineScope,
    /** A release faster than this (px/s) goes the way it was moving, however far it got. */
    private val flingVelocity: Float,
) {
    /** 0: down behind the mini player, 1: covers the screen. */
    var fraction by mutableFloatStateOf(if (open) 1f else 0f)
        private set

    /** Where the player is headed or settled: open or closed. */
    var isOpen by mutableStateOf(open)
        private set

    /** The distance it travels, the height of the screen (px). */
    var travel by mutableFloatStateOf(0f)

    /** Drags anywhere (mini player, player) move it; downward deltas close it. */
    val draggable = DraggableState { delta ->
        if (travel > 0f) fraction = (fraction - delta / travel).coerceIn(0f, 1f)
    }

    fun expand() = animateTo(open = true)
    fun collapse() = animateTo(open = false)

    /** A drag ended with [velocity] px/s (positive is down). */
    fun settle(velocity: Float) {
        val open = when {
            velocity < -flingVelocity -> true
            velocity > flingVelocity -> false
            else -> fraction > 0.5f
        }
        animateTo(open, velocity)
    }

    /** Follows a back gesture: the player sinks with it, and closes unless it is cancelled. */
    suspend fun followBack(progress: Flow<Float>) {
        try {
            draggable.drag(MutatePriority.UserInput) {
                progress.collect { fraction = 1f - it }
            }
            collapse()
        } catch (e: CancellationException) {
            expand()
            throw e
        }
    }

    private fun animateTo(open: Boolean, velocity: Float = 0f) {
        isOpen = open
        scope.launch {
            // Through the drag mutex: touching the player mid-animation catches it under the finger.
            draggable.drag(MutatePriority.Default) {
                animate(
                    initialValue = fraction,
                    targetValue = if (open) 1f else 0f,
                    initialVelocity = if (travel > 0f) -velocity / travel else 0f,
                    animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow, visibilityThreshold = 0.001f),
                ) { value, _ -> fraction = value.coerceIn(0f, 1f) }
            }
        }
    }
}

@Composable
fun rememberPlayerSheetState(): PlayerSheetState {
    val scope = rememberCoroutineScope()
    val flingVelocity = with(LocalDensity.current) { 500.dp.toPx() }
    return rememberSaveable(saver = Saver({ it.isOpen }, { PlayerSheetState(it, scope, flingVelocity) })) {
        PlayerSheetState(false, scope, flingVelocity)
    }
}
