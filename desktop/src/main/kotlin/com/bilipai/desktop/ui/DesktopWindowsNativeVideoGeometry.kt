package com.bilipai.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import com.bilipai.desktop.player.DesktopNativeVideoCanvasClip
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Full native render measurement and independent clip: moving beyond a host
 * edge must crop pixels, never shrink/stretch the video to the remaining space. */
internal data class DesktopWindowsNativeVideoPlacement(val left: Int, val top: Int, val width: Int, val height: Int)

private fun Rect.finiteNativeBounds(): Boolean =
    left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite()

internal fun resolveDesktopWindowsNativeVideoPlacement(renderBounds: Rect?, rootOrigin: Offset?): DesktopWindowsNativeVideoPlacement? {
    if (renderBounds == null || rootOrigin == null || !renderBounds.finiteNativeBounds() ||
        !rootOrigin.x.isFinite() || !rootOrigin.y.isFinite() || renderBounds.width <= 0f || renderBounds.height <= 0f) return null
    val width = renderBounds.width.roundToInt().coerceAtLeast(1)
    val height = renderBounds.height.roundToInt().coerceAtLeast(1)
    // Reject an unrepresentable Compose constraint before measuring the retained
    // child. Invalid transition metadata must not crash the whole layout pass.
    try { Constraints.fixed(width, height) } catch (_: IllegalArgumentException) { return null }
    return DesktopWindowsNativeVideoPlacement((renderBounds.left - rootOrigin.x).roundToInt(),
        (renderBounds.top - rootOrigin.y).roundToInt(), width, height)
}

internal fun resolveDesktopWindowsNativeVideoClip(
    clipBounds: Rect, rootBounds: Rect, windowSize: IntSize,
): DesktopNativeVideoCanvasClip? {
    if (!clipBounds.finiteNativeBounds() || !rootBounds.finiteNativeBounds() || windowSize.width <= 0 || windowSize.height <= 0) return null
    val left = max(0f, max(clipBounds.left, rootBounds.left))
    val top = max(0f, max(clipBounds.top, rootBounds.top))
    val right = min(windowSize.width.toFloat(), min(clipBounds.right, rootBounds.right))
    val bottom = min(windowSize.height.toFloat(), min(clipBounds.bottom, rootBounds.bottom))
    if (right <= left || bottom <= top) return DesktopNativeVideoCanvasClip.Empty
    val width = windowSize.width.toDouble()
    val height = windowSize.height.toDouble()
    return DesktopNativeVideoCanvasClip(
        (left / width).coerceIn(0.0, 1.0), (top / height).coerceIn(0.0, 1.0),
        (right / width).coerceIn(0.0, 1.0), (bottom / height).coerceIn(0.0, 1.0),
    )
}
