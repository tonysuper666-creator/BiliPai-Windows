package com.bilipai.desktop.ui.modal

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import com.bilipai.desktop.ui.DESKTOP_PLAYER_PANEL_DEFAULT_HIGH_DP
import com.bilipai.desktop.ui.DESKTOP_PLAYER_PANEL_DEFAULT_LOW_DP
import com.bilipai.desktop.ui.DESKTOP_PLAYER_PANEL_DEFAULT_PERCENT
import kotlin.math.roundToInt

internal enum class DesktopModalPurpose { CONFIRM, OPTIONS, READING_PANEL }

/** Anchor and result are in the owning window's physical pixels, including application zoom. */
internal fun desktopModalBounds(
    purpose: DesktopModalPurpose,
    viewport: IntSize,
    density: Float,
    contentHeight: Int = viewport.height,
    anchor: IntRect? = null,
    designWidthDp: Float = 480f,
): IntRect {
    require(density.isFinite() && density > 0)
    fun px(dp: Float) = (dp * density).roundToInt()
    val w = viewport.width.coerceAtLeast(0)
    val h = viewport.height.coerceAtLeast(0)
    val margin = minOf(px(16f), w / 2, h / 2)
    val width = when (purpose) {
        DesktopModalPurpose.CONFIRM -> minOf(px(designWidthDp.coerceAtLeast(0f)), (w - px(32f)).coerceAtLeast(0))
        DesktopModalPurpose.OPTIONS -> minOf(px(designWidthDp.coerceIn(168f, 260f)), (w - 2 * margin).coerceAtLeast(0))
        DesktopModalPurpose.READING_PANEL -> (w * DESKTOP_PLAYER_PANEL_DEFAULT_PERCENT / 100f).roundToInt()
            .coerceIn(px(DESKTOP_PLAYER_PANEL_DEFAULT_LOW_DP.toFloat()), px(DESKTOP_PLAYER_PANEL_DEFAULT_HIGH_DP.toFloat()))
            .coerceAtMost(w)
    }
    val height = when (purpose) {
        DesktopModalPurpose.CONFIRM -> contentHeight.coerceIn(0, (h - px(48f)).coerceAtLeast(0))
        DesktopModalPurpose.OPTIONS -> contentHeight.coerceIn(0, (h - 2 * margin).coerceAtLeast(0))
        DesktopModalPurpose.READING_PANEL -> h
    }
    val x: Int
    val y: Int
    when (purpose) {
        DesktopModalPurpose.CONFIRM -> { x = (w - width) / 2; y = (h - height) / 2 }
        DesktopModalPurpose.READING_PANEL -> { x = w - width; y = 0 }
        DesktopModalPurpose.OPTIONS -> {
            val at = requireNotNull(anchor) { "OPTIONS requires an anchor in owner-window coordinates" }
            val below = at.bottom + px(8f)
            val above = at.top - px(8f) - height
            x = at.left.coerceIn(margin, (w - margin - width).coerceAtLeast(margin))
            y = (if (below + height <= h - margin) below else above)
                .coerceIn(margin, (h - margin - height).coerceAtLeast(margin))
        }
    }
    return IntRect(x, y, x + width, y + height)
}
