package com.android.purebilibili.core.ui.transition

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoCardSnapshotSourceExclusionTest {
    @Test
    fun sourceHoleUsesSnapshotCanvasCoordinatesOnSplitScreens() {
        assertEquals(
            Rect(20f, 300f, 440f, 800f),
            resolveVideoCardSnapshotSourceExclusionBounds(
                Rect(820f, 380f, 1240f, 880f), Offset(800f, 80f), Size(600f, 1600f),
            ),
        )
    }

    @Test
    fun exclusionClipsToCanvasAndNeverHidesUnrelatedPane() {
        assertEquals(Rect(0f, 0f, 200f, 300f),
            resolveVideoCardSnapshotSourceExclusionBounds(
                Rect(-20f, -80f, 200f, 300f), Offset.Zero, Size(600f, 1600f)))
        assertEquals(null, resolveVideoCardSnapshotSourceExclusionBounds(
            Rect(0f, 0f, 420f, 500f), Offset(800f, 80f), Size(600f, 1600f)))
        assertEquals(null, resolveVideoCardSnapshotSourceExclusionBounds(
            null, Offset.Zero, Size(600f, 1600f)))
    }

    @Test
    fun originalSlotStaysEmptyThroughSettleEvenAtZeroDepth() {
        for (exposure in listOf(VideoCardTransitionExposure.BackPreview,
            VideoCardTransitionExposure.Returning, VideoCardTransitionExposure.Restoring)) {
            assertTrue(shouldHideStationarySourceCard(
                true, VideoCardTransitionBackgroundPhase.RETURNING, 0f, false, exposure))
            assertFalse(shouldHideStationarySourceCard(
                false, VideoCardTransitionBackgroundPhase.RETURNING, 0f, false, exposure))
        }
        assertFalse(shouldHideStationarySourceCard(true,
            VideoCardTransitionBackgroundPhase.IDLE, 0f, false, VideoCardTransitionExposure.Idle))
        assertFalse(shouldHideStationarySourceCard(true,
            VideoCardTransitionBackgroundPhase.OPENING, 0f, false, VideoCardTransitionExposure.Opening))
    }
}
