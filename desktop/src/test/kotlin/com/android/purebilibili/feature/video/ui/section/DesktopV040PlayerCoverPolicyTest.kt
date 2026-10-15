package com.android.purebilibili.feature.video.ui.section

import com.android.purebilibili.core.ui.transition.VideoSharedTransitionTargetMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopV040PlayerCoverPolicyTest {
    @Test
    fun defaultCoverExitSharesTheOriginalSurfaceRevealDuration() {
        val reveal = resolveVideoPlayerRevealMotionSpec()
        val cover = resolveVideoPlayerCoverMotionSpec(forceCoverDuringReturnAnimation = false)
        assertEquals(140, reveal.surfaceRevealDurationMillis)
        assertEquals(reveal.surfaceRevealDurationMillis, cover.exitFadeDurationMillis)
    }

    @Test
    fun callerCanShareReducedMotionAndCustomDurationsWithoutNegativeTweenTimes() {
        val reduced = resolveVideoPlayerRevealMotionSpec(reducedMotion = true)
        assertEquals(80, reduced.surfaceRevealDurationMillis)
        for (duration in listOf(-1, 0, 1, reduced.surfaceRevealDurationMillis, 350)) {
            val cover = resolveVideoPlayerCoverMotionSpec(
                forceCoverDuringReturnAnimation = false,
                surfaceRevealDurationMillis = duration,
            )
            assertEquals(duration.coerceAtLeast(0), cover.exitFadeDurationMillis)
        }
    }

    @Test
    fun landscapeCoversFitNormalPlaybackAndPreserveSourceCropDuringReturn() {
        for (mode in VideoSharedTransitionTargetMode.entries) {
            val normal = resolveVideoPlayerEntryPresentationSpec(false, false, false, mode)
            val returning = resolveVideoPlayerEntryPresentationSpec(false, true, false, mode)
            assertEquals(VideoPlayerCoverContentScaleMode.Fit, normal.coverContentScaleMode)
            assertEquals(VideoPlayerCoverContentScaleMode.Crop, returning.coverContentScaleMode)
            assertFalse(returning.fillCoverViewport)
        }
    }

    @Test
    fun forcedReturnAndManualHoldStillUseAnOpaqueForegroundCover() {
        assertTrue(resolveVideoPlayerCoverMotionSpec(false, false).shouldAnimateFade)
        assertFalse(resolveVideoPlayerCoverMotionSpec(true, false).shouldAnimateFade)
        assertFalse(resolveVideoPlayerCoverMotionSpec(false, true).shouldAnimateFade)
        val manual = resolveVideoPlayerEntryPresentationSpec(
            true, false, false, VideoSharedTransitionTargetMode.InlinePlayer,
        )
        assertTrue(manual.showManualStartPlayButton)
        assertTrue(manual.enableManualStartCoverOverlay)
    }
}
