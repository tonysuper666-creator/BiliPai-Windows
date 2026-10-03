package com.bilipai.desktop.settings

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth

/** Keep the entire original Column in the actual client height at its real density/fonts.
 * The Column's own intrinsic calculation includes its 85% square QR, close button,
 * original spacers, and both original styled texts; no footer-height estimate is supplied.
 */
internal fun Modifier.desktopOriginalDonateFitClient(): Modifier = then(OriginalDonateClientFit)

private object OriginalDonateClientFit : LayoutModifier {
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val fittingWidth = if (constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
            largestFittingWidth(measurable, constraints.maxWidth, constraints.maxHeight)
        } else null
        // Some dimensions cannot hold even the original footer/close content. Retain
        // original measurement there; never relabel a clipped result as fitting content.
        // The owned application's ordinary minimum window is checked by the real UI fixture.
        val childConstraints = if (fittingWidth != null) Constraints(
            minWidth = 0, maxWidth = fittingWidth,
            minHeight = 0, maxHeight = constraints.maxHeight,
        ) else constraints
        val child = measurable.measure(childConstraints)
        val width = constraints.constrainWidth(child.width)
        val height = constraints.constrainHeight(child.height)
        return layout(width, height) {
            child.placeRelative((width - child.width) / 2, (height - child.height) / 2)
        }
    }

    private fun largestFittingWidth(measurable: Measurable, maxWidth: Int, maxHeight: Int): Int? {
        if (maxWidth <= 0 || maxHeight <= 0) return null
        val heights = HashMap<Int, Int>()
        fun fits(width: Int): Boolean = heights.getOrPut(width) {
            measurable.minIntrinsicHeight(width)
        } <= maxHeight
        if (fits(maxWidth)) return maxWidth

        // At height zero the original square QR contributes no intrinsic width; the
        // original texts and close button establish the actual unwrapped-content width.
        // Above it, text height stays constant and the original square's height grows.
        val unwrappedWidth = measurable.maxIntrinsicWidth(0).coerceAtLeast(1)
        if (unwrappedWidth <= maxWidth && fits(unwrappedWidth)) {
            var low = unwrappedWidth
            var high = maxWidth
            while (low < high) {
                val middle = low + (high - low + 1) / 2
                if (fits(middle)) low = middle else high = middle - 1
            }
            return low
        }

        // Narrow/very short clients can wrap the original text. That height is not
        // globally monotone; inspect descending widths instead of assuming it is.
        for (width in minOf(maxWidth, unwrappedWidth - 1) downTo 1) {
            if (fits(width)) return width
        }
        return null
    }
}
