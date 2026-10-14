package com.bilipai.desktop.danmaku

import com.bilipai.desktop.player.OwnedPlaybackSourceSnapshot
import com.bilipai.desktop.player.PlaybackSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopDanmakuPresentationTimeTest {
    private fun source(version: Long, url: String = "https://example.test/a") =
        OwnedPlaybackSourceSnapshot(version, PlaybackSource(url))
    @Test fun transitionPauseRetainsTheSameSourceFrameAndResumeUsesActualMediaTime() {
        assertEquals(12.5, resolveDesktopDanmakuPresentationTime(source(7), source(7), 12.5, 15.0, true))
        assertEquals(15.0, resolveDesktopDanmakuPresentationTime(source(7), source(7), 12.5, 15.0, false))
    }

    @Test fun successorSourceCannotPaintAPredecessorFrameDuringPause() {
        assertNull(resolveDesktopDanmakuPresentationTime(source(8), source(7), 12.5, 0.0, true))
        assertEquals(0.0, resolveDesktopDanmakuPresentationTime(source(8), source(7), 12.5, 0.0, false))
    }

    @Test fun seekDuringTransitionDoesNotAdvanceTheRetainedFrameUntilResume() {
        assertEquals(12.5, resolveDesktopDanmakuPresentationTime(source(7), source(7), 12.5, 80.0, true))
        assertEquals(80.0, resolveDesktopDanmakuPresentationTime(source(7), source(7), 12.5, 80.0, false))
    }

    @Test fun unboundSourceAndUnpaintedSourceHaveNoRetainedFrame() {
        assertNull(resolveDesktopDanmakuPresentationTime(null, null, 0.0, 0.0, true))
        assertNull(resolveDesktopDanmakuPresentationTime(source(7), null, 0.0, 0.0, true))
    }

    @Test fun sameNumericVersionWithDifferentImmutableSourceCannotBorrowAFrame() {
        assertNull(resolveDesktopDanmakuPresentationTime(source(7, "https://example.test/b"),
            source(7), 12.5, 0.0, true))
    }

    @Test fun invalidTimesCannotBecomePresentedFrames() {
        assertNull(resolveDesktopDanmakuPresentationTime(source(7), source(7), Double.NaN, 1.0, true))
        assertNull(resolveDesktopDanmakuPresentationTime(source(7), source(7), 1.0, Double.POSITIVE_INFINITY, false))
        assertNull(resolveDesktopDanmakuPresentationTime(source(7), source(7), 1.0, -1.0, false))
    }
}
