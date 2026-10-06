package com.bilipai.desktop.ui

import java.awt.Dimension
import java.awt.Rectangle
import kotlin.test.*

/** Pure AWT geometry only: no window, Toolkit, Main, native player or screen access. */
class WindowsVideoActualResizeTargetTest {
    @Test fun largerWindowUsesTheOriginalShrinkAmounts() {
        assertEquals(Rectangle(100, 50, 1160, 800), actualWindowsVideoResizeTarget(
            Rectangle(100, 50, 1280, 900), Dimension(1024, 720), Rectangle(0, 0, 1920, 1080)))
    }
    @Test fun actualCloudMinimumGrowsOnlyIntoTheRemainingRealMonitorHeight() {
        assertEquals(Rectangle(0, 0, 1024, 768), actualWindowsVideoResizeTarget(
            Rectangle(0, 0, 1024, 720), Dimension(1024, 720), Rectangle(0, 0, 1024, 768)))
    }
    @Test fun growthRepositionsWithinAnOffsetMonitorInsteadOfLeavingTheScreen() {
        assertEquals(Rectangle(-1920, 0, 1024, 768), actualWindowsVideoResizeTarget(
            Rectangle(-1920, 48, 1024, 720), Dimension(1024, 720), Rectangle(-1920, 0, 1024, 768)))
    }
    @Test fun aSingleResizableDimensionStillProducesARealSizeChange() {
        assertEquals(Rectangle(0, 0, 1024, 720), actualWindowsVideoResizeTarget(
            Rectangle(0, 0, 1024, 800), Dimension(1024, 720), Rectangle(0, 0, 1024, 900)))
    }
    @Test fun exactMinimumMonitorFailsRatherThanSkippingOrFakingAResize() {
        assertFailsWith<IllegalStateException> { actualWindowsVideoResizeTarget(
            Rectangle(0, 0, 1024, 720), Dimension(1024, 720), Rectangle(0, 0, 1024, 720)) }
    }
    @Test fun unsupportedInitialGeometryCannotBorrowSpaceOutsideItsActualMonitor() {
        assertFailsWith<IllegalArgumentException> { actualWindowsVideoResizeTarget(
            Rectangle(0, 0, 1024, 720), Dimension(1024, 720), Rectangle(0, 0, 1000, 700)) }
        assertFailsWith<IllegalArgumentException> { actualWindowsVideoResizeTarget(
            Rectangle(0, 0, 1000, 700), Dimension(1024, 720), Rectangle(0, 0, 1920, 1080)) }
    }
}
