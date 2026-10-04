package com.zyagodin.booksound.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

/**
 * Drag-to-reorder for items of a LazyColumn, keyed by item key. Only items accepted by
 * [canSwap] take part, so headers and other content in the same list stay in place.
 */
@Stable
class ReorderState internal constructor(
    val listState: LazyListState,
    private val canSwap: (Any) -> Boolean,
) {
    internal var onMove: (from: Any, to: Any) -> Unit = { _, _ -> }

    var draggingKey by mutableStateOf<Any?>(null)
        private set
    private var initialOffset by mutableIntStateOf(0)
    private var delta by mutableFloatStateOf(0f)
    internal var autoScroll by mutableFloatStateOf(0f)

    private fun info(key: Any?) = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    /** Translation to apply to the dragged item so it follows the finger. */
    fun offsetOf(key: Any): Float = if (key != draggingKey) 0f else info(key)?.let { initialOffset + delta - it.offset } ?: 0f

    internal fun start(key: Any) {
        val item = info(key) ?: return
        draggingKey = key
        initialOffset = item.offset
        delta = 0f
    }

    internal fun drag(dy: Float) {
        delta += dy
        val current = info(draggingKey) ?: return
        val top = initialOffset + delta
        val middle = (top + current.size / 2f).toInt()
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull {
            it.key != draggingKey && canSwap(it.key) && middle in it.offset..(it.offset + it.size)
        }
        if (target != null) onMove(current.key, target.key)
        val start = listState.layoutInfo.viewportStartOffset
        val end = listState.layoutInfo.viewportEndOffset
        autoScroll = when {
            top < start + EDGE -> -SCROLL_STEP
            top + current.size > end - EDGE -> SCROLL_STEP
            else -> 0f
        }
    }

    internal fun end() {
        draggingKey = null
        delta = 0f
        autoScroll = 0f
    }

    private companion object {
        const val EDGE = 120
        const val SCROLL_STEP = 18f
    }
}

@Composable
fun rememberReorderState(listState: LazyListState, canSwap: (Any) -> Boolean, onMove: (from: Any, to: Any) -> Unit): ReorderState {
    val state = remember(listState) { ReorderState(listState, canSwap) }
    val latestMove by rememberUpdatedState(onMove)
    state.onMove = { a, b -> latestMove(a, b) }
    LaunchedEffect(state) {
        while (true) {
            if (state.draggingKey != null && state.autoScroll != 0f) state.listState.scrollBy(state.autoScroll)
            delay(16)
        }
    }
    return state
}

/** Attach to a drag handle inside the item with [key]. */
fun Modifier.reorderHandle(state: ReorderState, key: Any): Modifier = pointerInput(state, key) {
    detectDragGestures(
        onDragStart = { state.start(key) },
        onDragEnd = { state.end() },
        onDragCancel = { state.end() },
        onDrag = { change, amount ->
            change.consume()
            state.drag(amount.y)
        },
    )
}
