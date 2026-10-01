// Original source app/src/main/java/com/android/purebilibili/feature/home/HomeFeedGridPolicy.kt
// LF SHA256 0216d770c562728dc9cac6549bbbbb205ac10f79bb594437665a610ae17198ff
package com.android.purebilibili.feature.home

import androidx.compose.foundation.layout.Arrangement
import com.android.purebilibili.core.util.AppFoldPosture
import com.android.purebilibili.core.util.AppHingeOrientation
import com.android.purebilibili.core.util.AppWindowAdaptiveInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

internal data class HomeFeedBookHingeGridSpec(
    val enabled: Boolean,
    val centerGapDp: Dp = 0.dp,
)

internal fun resolveHomeFeedBookHingeGridSpec(
    adaptiveInfo: AppWindowAdaptiveInfo,
    density: Float,
): HomeFeedBookHingeGridSpec {
    val hingeBounds = adaptiveInfo.foldingFeature.hingeBounds
    if (
        adaptiveInfo.posture != AppFoldPosture.Book ||
        !adaptiveInfo.shouldAvoidHinge ||
        adaptiveInfo.foldingFeature.hingeOrientation != AppHingeOrientation.Vertical ||
        hingeBounds == null ||
        density <= 0f
    ) {
        return HomeFeedBookHingeGridSpec(enabled = false)
    }
    // Keep an 8dp safety inset on both sides even when the reported crease itself is 0px wide.
    val physicalGapDp = (hingeBounds.width / density).dp
    return HomeFeedBookHingeGridSpec(
        enabled = true,
        centerGapDp = physicalGapDp.coerceAtLeast(0.dp) + 16.dp,
    )
}

/**
 * Even-column arrangement that reserves one larger gap at the Book hinge.
 *
 * [spacing] exposes the averaged gap to LazyGrid so cell measurement already budgets for the
 * hinge. Placement then moves that budget to the middle instead of shrinking the outer padding.
 */

internal class HomeFeedBookHingeArrangement(
    private val columns: Int,
    private val baseSpacing: Dp,
    private val centerGap: Dp,
) : Arrangement.Horizontal {
    override val spacing: Dp = if (columns > 1) {
        baseSpacing + centerGap / (columns - 1).toFloat()
    } else {
        baseSpacing
    }

    override fun Density.arrange(
        totalSize: Int,
        sizes: IntArray,
        layoutDirection: LayoutDirection,
        outPositions: IntArray,
    ) {
        if (sizes.isEmpty()) return
        val baseSpacingPx = baseSpacing.roundToPx()
        val centerGapPx = centerGap.roundToPx()
        val centerAfterIndex = (columns / 2 - 1).coerceAtLeast(0)
        var position = 0
        sizes.indices.forEach { index ->
            val ltrPosition = position
            outPositions[index] = if (layoutDirection == LayoutDirection.Ltr) {
                ltrPosition
            } else {
                totalSize - ltrPosition - sizes[index]
            }
            position += sizes[index]
            if (index < sizes.lastIndex) {
                position += baseSpacingPx
                if (sizes.size == columns && index == centerAfterIndex) {
                    position += centerGapPx
                }
            }
        }
    }
}

internal fun resolveHomeFeedHorizontalArrangement(
    columns: Int,
    baseSpacing: Dp,
    hingeSpec: HomeFeedBookHingeGridSpec,
): Arrangement.Horizontal = if (hingeSpec.enabled && columns >= 2 && columns % 2 == 0) {
    HomeFeedBookHingeArrangement(
        columns = columns,
        baseSpacing = baseSpacing,
        centerGap = hingeSpec.centerGapDp,
    )
} else {
    Arrangement.spacedBy(baseSpacing)
}
