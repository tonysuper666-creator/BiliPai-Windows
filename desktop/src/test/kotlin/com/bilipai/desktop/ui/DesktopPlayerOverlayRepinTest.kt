package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.ui.overlay.resolveVideoProgressBarLayoutPolicy
import com.android.purebilibili.feature.video.ui.overlay.shouldShowBufferingIndicator
import java.awt.Insets
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure checks of the v0.4.6 player overlay re-pin (roadmap slice plr-overlay-and-controlbar-repin). No window, no
 * GPU, no player and no network: only the policies the carried files expose and the owned dialog geometry.
 */
class DesktopPlayerOverlayRepinTest {
    @Test
    fun bufferingCircleWaitsForTheFirstRenderedFrame() {
        assertFalse(
            shouldShowBufferingIndicator(
                isBuffering = true,
                isQualitySwitching = false,
                playWhenReady = true,
                hasRenderedFirstFrame = false,
            ),
        )
        assertTrue(
            shouldShowBufferingIndicator(
                isBuffering = true,
                isQualitySwitching = false,
                playWhenReady = true,
                hasRenderedFirstFrame = true,
            ),
        )
    }

    @Test
    fun bufferingCircleHidesWhileTheTransitionSuppressesOverlays() {
        assertFalse(
            shouldShowBufferingIndicator(
                isBuffering = true,
                isQualitySwitching = false,
                playWhenReady = true,
                hasRenderedFirstFrame = true,
                suppressTransientOverlays = true,
            ),
        )
    }

    @Test
    fun callersThatPassNothingNewKeepTheOldBufferingBehaviour() {
        assertTrue(shouldShowBufferingIndicator(isBuffering = true, isQualitySwitching = false, playWhenReady = true))
        assertFalse(shouldShowBufferingIndicator(isBuffering = true, isQualitySwitching = true, playWhenReady = true))
        assertFalse(shouldShowBufferingIndicator(isBuffering = true, isQualitySwitching = false, playWhenReady = false))
        assertFalse(shouldShowBufferingIndicator(isBuffering = false, isQualitySwitching = false, playWhenReady = true))
    }

    @Test
    fun progressBarHeightsAreTheCompactV046Values() {
        val expected = listOf(
            // width, idle height, height with chapter, dragging container, touch row
            listOf(393, 12, 28, 100, 12),
            listOf(720, 15, 32, 110, 14),
            listOf(1024, 18, 36, 120, 16),
            listOf(1920, 24, 44, 140, 20),
        )
        for ((width, idle, withChapter, dragging, touch) in expected) {
            val policy = resolveVideoProgressBarLayoutPolicy(width)
            assertEquals(idle, policy.baseHeightWithoutChapterDp, "idle height at $width")
            assertEquals(withChapter, policy.baseHeightWithChapterDp, "height with chapter at $width")
            assertEquals(dragging, policy.draggingContainerHeightDp, "dragging container at $width")
            assertEquals(touch, policy.touchContainerHeightDp, "touch row at $width")
            assertEquals(2f, policy.trackHeightDp, "track height at $width")
        }
    }

    @Test
    fun composerDialogFitsAFullscreenOwnerWindowAtDefaultScale() {
        val bounds = desktopWindowsPlayerDialogInitialBounds(
            ownerClient = Rectangle(0, 0, 1920, 1080),
            decoration = Insets(0, 0, 0, 0),
            parentDensity = 1f,
            ownerScaleX = 1.0,
            ownerScaleY = 1.0,
            preferredHeightDp = DESKTOP_WINDOWS_DANMAKU_COMPOSER_DIALOG_HEIGHT_DP,
        )
        assertEquals(DESKTOP_WINDOWS_DANMAKU_COMPOSER_DIALOG_HEIGHT_DP, bounds.height)
        assertEquals(640, bounds.width)
        assertTrue(bounds.height < 720, "the composer dialog is compact next to the 720 dp default")
    }
}
