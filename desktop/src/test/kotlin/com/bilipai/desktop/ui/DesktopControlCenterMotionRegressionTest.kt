package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.ui.components.ControlCenterMotionSpec
import com.android.purebilibili.feature.video.ui.components.DanmakuSettingsPanelAnchor
import com.android.purebilibili.feature.video.ui.components.VideoControlCenterMotion
import com.android.purebilibili.feature.video.ui.components.useDanmakuControlCenterMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Job

/**
 * Pure checks of the v0.4.6 control-center drawer motion (roadmap slice plr-control-center-motion). No window, no GPU,
 * no player and no network: only the decision functions and the gesture clock the carried files expose. The two upstream
 * VideoControlCenterMotion cases that assume the spec's 0.35 resistance are recomputed in the emitted original test;
 * the clamp of the carried clock is pinned here.
 */
class DesktopControlCenterMotionRegressionTest {
    private val tolerance = 1e-5f

    @Test
    fun dragResistanceStartsOnlyAfterTheFullyOpenPoint() {
        assertEquals(0.7f, ControlCenterMotionSpec.dragProgress(0.5f, 0.2f), absoluteTolerance = tolerance)
        // 0.9 + 0.3 crosses 1: only the 0.2 beyond it is resisted (1 + 0.2 * 0.35).
        assertEquals(1.07f, ControlCenterMotionSpec.dragProgress(0.9f, 0.3f), absoluteTolerance = tolerance)
        // Already fully open: the whole delta is resisted (1 + 0.4 * 0.35).
        assertEquals(1.14f, ControlCenterMotionSpec.dragProgress(1f, 0.4f), absoluteTolerance = tolerance)
    }

    @Test
    fun closingDragIsNeverResistedAndNeverGoesNegative() {
        assertEquals(0.3f, ControlCenterMotionSpec.dragProgress(0.5f, -0.2f), absoluteTolerance = tolerance)
        assertEquals(0f, ControlCenterMotionSpec.dragProgress(0.1f, -0.5f), absoluteTolerance = tolerance)
        assertEquals(1f, ControlCenterMotionSpec.dragProgress(1f, 0f), absoluteTolerance = tolerance)
    }

    @Test
    fun overPullIsCappedAtAQuarterBeyondOpen() {
        assertEquals(0.25f, ControlCenterMotionSpec.MaxOverExpansion)
        assertEquals(1.25f, ControlCenterMotionSpec.dragProgress(1.2f, 1f), absoluteTolerance = tolerance)
        assertEquals(1.25f, ControlCenterMotionSpec.dragProgress(1.25f, 0.1f), absoluteTolerance = tolerance)
    }

    @Test
    fun releaseDecisionUsesVelocityBeforeDistance() {
        assertTrue(ControlCenterMotionSpec.shouldClose(0.9f, -2.5f))
        assertFalse(ControlCenterMotionSpec.shouldClose(0.1f, 2.5f))
        assertTrue(ControlCenterMotionSpec.shouldClose(0.64f, 0f))
        assertFalse(ControlCenterMotionSpec.shouldClose(0.65f, 0f))
    }

    @Test
    fun releaseVelocityIsBoundedBothWaysAndPassesSmallValues() {
        assertEquals(4f, ControlCenterMotionSpec.releaseVelocity(4.5f))
        assertEquals(-4f, ControlCenterMotionSpec.releaseVelocity(-4.5f))
        assertEquals(1.5f, ControlCenterMotionSpec.releaseVelocity(1.5f))
    }

    @Test
    fun springIsCriticallyDampedWhenClosingAndBouncyWhenOpening() {
        val closing = ControlCenterMotionSpec.springSpec(closing = true)
        val opening = ControlCenterMotionSpec.springSpec(closing = false)
        assertEquals(1f, closing.dampingRatio)
        assertEquals(0.72f, opening.dampingRatio)
        assertEquals(300f, closing.stiffness)
        assertEquals(300f, opening.stiffness)
        assertEquals(0.001f, opening.visibilityThreshold)
    }

    @Test
    fun settingsClockClampsBetweenClosedAndOneAndAHalf() {
        val motion = VideoControlCenterMotion()
        motion.dragBy(-1f)
        assertEquals(0f, motion.progress)
        motion.dragBy(20f)
        assertEquals(1.5f, motion.progress)
        motion.dragBy(-0.5f)
        assertEquals(1f, motion.progress)
    }

    @Test
    fun beginningADragCancelsTheRunningSettleAndLeavesTheClosingState() {
        val motion = VideoControlCenterMotion()
        val running = Job()
        motion.animationJob = running
        motion.beginDrag()
        assertTrue(running.isCancelled)
        assertFalse(motion.closing)
    }

    @Test
    fun danmakuPanelMotionIsOnlyForTheFullscreenGlassEndPanel() {
        assertTrue(useDanmakuControlCenterMotion(true, DanmakuSettingsPanelAnchor.End, true, true, false))
        assertFalse(useDanmakuControlCenterMotion(true, DanmakuSettingsPanelAnchor.End, true, true, true))
        assertFalse(useDanmakuControlCenterMotion(false, DanmakuSettingsPanelAnchor.End, true, true, false))
    }
}
