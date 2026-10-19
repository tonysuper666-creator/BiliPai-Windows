package com.bilipai.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.purebilibili.core.ui.animation.LiquidIndicatorMotion
import com.android.purebilibili.core.ui.animation.resolveDampedDragReleaseTargetIndex
import com.android.purebilibili.core.ui.animation.resolveDampedDragVelocityItemsPerSecond
import com.android.purebilibili.core.ui.animation.shouldEngageHorizontalDrag
import com.android.purebilibili.core.ui.components.AppCardColors
import com.android.purebilibili.core.ui.components.AppCardDefaults
import com.android.purebilibili.core.ui.components.AppChoiceOption
import com.android.purebilibili.core.ui.components.AppSingleChoicePresentation
import com.android.purebilibili.core.ui.components.resolveAppSliderDialogLayoutPolicy
import com.android.purebilibili.core.ui.components.resolveAppSliderDialogValue
import com.android.purebilibili.core.ui.components.shouldDispatchAppChoiceSelection
import com.android.purebilibili.core.ui.motion.resolveBottomBarMotionSpec
import com.android.purebilibili.core.ui.renderer.material3.resolveTonalPillTabCornerRadius
import com.android.purebilibili.core.ui.renderer.material3.resolveTonalPillTabDefaultHeight
import com.android.purebilibili.core.ui.renderer.material3.resolveTonalPillTabDefaultLabelFontSize
import com.android.purebilibili.core.ui.renderer.material3.resolveTonalPillTabItemHorizontalPadding
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Compile and behaviour check of the v0.4.6 design-system leaf files carried whole by the
 * v046-design-system-leaves-b archive (AppPreferenceComponents, AppSelectionPreferenceComponents, AppCard,
 * AppDesktopInteraction, AppTonalPillTabPolicy, DampedDragAnimation). Pure values and policies: no window, no GPU,
 * no network. The upstream AppTonalPillTabPolicyTest and DampedDragAnimationPolicyTest are not emitted (the first was
 * deleted upstream together with the removed inset function, the second reads the main source by relative path);
 * their value and policy assertions live here instead.
 */
class DesktopDesignSystemLeavesBTest {
    @Test fun tonalPillTabPolicyKeepsTheFourGeometryValuesAndDropsTheVerticalInset() {
        assertEquals(13.sp, resolveTonalPillTabDefaultLabelFontSize())
        assertEquals(20.dp, resolveTonalPillTabCornerRadius())
        assertEquals(12.dp, resolveTonalPillTabItemHorizontalPadding())
        assertEquals(40.dp, resolveTonalPillTabDefaultHeight())
    }

    @Test fun horizontalDragNeedsClearHorizontalDominanceAndAMinimumDistance() {
        assertTrue(shouldEngageHorizontalDrag(horizontalDeltaPx = 30f, verticalDeltaPx = 10f))
        assertFalse(shouldEngageHorizontalDrag(horizontalDeltaPx = 30f, verticalDeltaPx = 26f))
        assertFalse(shouldEngageHorizontalDrag(horizontalDeltaPx = 6f, verticalDeltaPx = 0f))
        assertTrue(shouldEngageHorizontalDrag(horizontalDeltaPx = -40f, verticalDeltaPx = 10f))
        // a ratio below one is raised to one, a negative minimum distance to zero
        assertTrue(shouldEngageHorizontalDrag(10f, 9f, dominanceRatio = 0.1f, minimumHorizontalDistancePx = -5f))
    }

    @Test fun dragReleaseProjectionGuardsInvalidWidthAndLimitsTheStepToTheMotionSpec() {
        assertEquals(0f, resolveDampedDragVelocityItemsPerSecond(velocityPxPerSecond = 1200f, itemWidthPx = 0f))
        assertEquals(12f, resolveDampedDragVelocityItemsPerSecond(velocityPxPerSecond = 1200f, itemWidthPx = 100f))
        val spec = resolveBottomBarMotionSpec()
        val step = spec.drag.maxReleaseStepCount.coerceAtLeast(1)
        assertEquals(0, resolveDampedDragReleaseTargetIndex(1f, 5000f, 100f, 0, spec))
        // a hard fling right and left is clamped to the maximum release step and to the item range
        assertEquals((1 + step).coerceIn(0, 3), resolveDampedDragReleaseTargetIndex(1f, 100_000f, 100f, 4, spec))
        assertEquals((1 - step).coerceIn(0, 3), resolveDampedDragReleaseTargetIndex(1f, -100_000f, 100f, 4, spec))
        assertEquals(3, resolveDampedDragReleaseTargetIndex(3f, 100_000f, 100f, 4, spec))
        // no velocity settles on the nearest item
        assertEquals(2, resolveDampedDragReleaseTargetIndex(2.4f, 0f, 100f, 4, spec))
    }

    @Test fun liquidIndicatorMotionCarriesTheSettleBudgetsTheV046DragStateWaitsOn() {
        assertEquals(300L, LiquidIndicatorMotion.BloomWaitMillis)
        assertEquals(450L, LiquidIndicatorMotion.SettleWaitMillis)
        assertEquals(0.015f, LiquidIndicatorMotion.SettleTolerance)
        assertEquals(0.001f, LiquidIndicatorMotion.positionSpec().visibilityThreshold)
        assertEquals(1f, LiquidIndicatorMotion.pressSpec().dampingRatio)
        assertEquals(0.6f, LiquidIndicatorMotion.scaleXSpec().dampingRatio)
        assertEquals(0.7f, LiquidIndicatorMotion.scaleYSpec().dampingRatio)
    }

    @Test fun choiceDispatchSliderSnappingAndDialogLayoutFollowTheSelectionFile() {
        assertFalse(shouldDispatchAppChoiceSelection("a", "a"))
        assertTrue(shouldDispatchAppChoiceSelection("a", "b"))
        assertEquals(0.25f, resolveAppSliderDialogValue(0.37f, 0f..1f, steps = 3))
        assertEquals(1f, resolveAppSliderDialogValue(0.9f, 0f..1f, steps = 3))
        assertEquals(1f, resolveAppSliderDialogValue(5f, 0f..1f, steps = 0))
        assertEquals(2f, resolveAppSliderDialogValue(3f, 2f..2f, steps = 2))
        val layout = resolveAppSliderDialogLayoutPolicy()
        assertFalse(layout.usePlatformDefaultWidth)
        assertEquals(24, layout.horizontalPaddingDp)
        assertEquals(280, layout.minWidthDp)
        assertEquals(420, layout.maxWidthDp)
        val option = AppChoiceOption(value = 7, label = "seven")
        assertEquals(null, option.description)
        assertEquals(AppSingleChoicePresentation.WINDOW_POPUP, AppSingleChoicePresentation.fromStorageValue(null))
        assertEquals(AppSingleChoicePresentation.CENTERED_DIALOG, AppSingleChoicePresentation.fromStorageValue("centered_dialog"))
    }

    @Test fun cardColoursKeepTheExplicitContainerAndLeaveTheContentColourUnspecifiedByDefault() {
        val colors = AppCardDefaults.colors(containerColor = Color(0xFF123456))
        assertEquals(AppCardColors(Color(0xFF123456), Color.Unspecified), colors)
        assertNotEquals(colors, AppCardDefaults.colors(Color(0xFF123456), Color.White))
    }
}
