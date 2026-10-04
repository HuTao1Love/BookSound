package com.zyagodin.booksound.ui.components

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Rect

enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

/**
 * Current window shape. Recomputed on every resize, fold/unfold and posture change, so layouts
 * switch instantly when the Fold is opened or closed.
 */
@Immutable
data class WindowLayout(
    val width: WidthClass,
    val isShort: Boolean,
    val isTabletop: Boolean,
    /** Horizontal hinge in window coordinates (px) when the device is half-folded. */
    val hingeBounds: Rect?,
) {
    val isCompact: Boolean get() = width == WidthClass.COMPACT
    val isWide: Boolean get() = width != WidthClass.COMPACT
}

@Composable
fun rememberWindowLayout(): WindowLayout {
    val info = currentWindowAdaptiveInfoV2()
    val sizeClass = info.windowSizeClass
    val width = when {
        sizeClass.isWidthAtLeastBreakpoint(840) -> WidthClass.EXPANDED
        sizeClass.isWidthAtLeastBreakpoint(600) -> WidthClass.MEDIUM
        else -> WidthClass.COMPACT
    }
    val hinge = info.windowPosture.hingeList.firstOrNull { !it.isVertical && !it.isFlat }
    return WindowLayout(
        width = width,
        isShort = !sizeClass.isHeightAtLeastBreakpoint(480),
        isTabletop = info.windowPosture.isTabletop,
        hingeBounds = hinge?.bounds,
    )
}
