package com.bilipai.desktop.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import kotlin.test.*

/** Original generated expression and the actual measured-geometry consumer.
 * These tests do not create a peer or prove click-through/modal/native pixels. */
class DesktopWindowsVideoFeedbackPlacementTest {
    @Test fun originalFormulaUsesActualRightEdgeAndClampsToClient() {
        assertEquals(IntOffset(832, 188), desktopWindowsOriginalLikeFeedbackOffset(
            Rect(880f, 320f, 900f, 340f), 144f, 1000f, 700f, 0f, 0f, 0f, 4f, 12f))
        assertEquals(IntOffset(856, 0), desktopWindowsOriginalLikeFeedbackOffset(
            Rect(950f, 10f, 999f, 30f), 144f, 1000f, 700f, 0f, 0f, 0f, 4f, 12f))
        assertEquals(IntOffset(0, 556), desktopWindowsOriginalLikeFeedbackOffset(
            Rect(0f, 690f, 10f, 700f), 144f, 1000f, 700f, 0f, 0f, 0f, 4f, 12f))
    }

    @Test fun originalFormulaKeepsPhysicalPixelsAtBothSystemAndAppScale() {
        // Already measured pixels, including the actual app layout. Passing
        // these to the actual generated original formula needs no second DPI.
        assertEquals(IntOffset(1248, 282), desktopWindowsOriginalLikeFeedbackOffset(
            Rect(1320f, 480f, 1350f, 510f), 216f, 1500f, 1050f, 0f, 0f, 0f, 6f, 18f))
        assertNull(desktopWindowsOriginalLikeFeedbackOffset(null, 180f, 1500f, 1050f, 0f, 0f, 0f, 7.5f, 22.5f))
    }

    @Test fun oldLikeMountDisposeCannotClearReopenedSuccessor() {
        val projection = DesktopWindowsVideoFeedbackBounds()
        val a = Any(); val b = Any()
        projection.mountLike(a); projection.reportLike(a, Rect(50f, 100f, 90f, 130f))
        projection.mountLike(b); projection.reportLike(b, Rect(150f, 100f, 190f, 130f))
        projection.releaseLike(a); projection.reportLike(a, Rect(1f, 1f, 2f, 2f))
        assertEquals(Rect(150f, 100f, 190f, 130f), projection.like)
        projection.releaseLike(b); assertNull(projection.like)
    }

    @Test fun sourceBindingUsesItsOwnProjectionAndInvalidVideoCannotInventFallback() {
        val old = DesktopWindowsVideoFeedbackBounds(); val successor = DesktopWindowsVideoFeedbackBounds()
        old.reportVideo(Rect(30f, 50f, 700f, 500f))
        successor.reportVideo(Rect(80f, 90f, 900f, 600f))
        old.reportVideo(Rect(Float.NaN, 1f, 2f, 3f))
        assertNull(old.video); assertEquals(Rect(80f, 90f, 900f, 600f), successor.video)
        assertEquals(Rect(0f, 20f, 400f, 300f), desktopWindowsFeedbackClippedRect(Rect(-5f,20f,450f,400f),400,300))
        assertNull(desktopWindowsFeedbackClippedRect(Rect(500f,20f,550f,50f),400,300))
    }
}
