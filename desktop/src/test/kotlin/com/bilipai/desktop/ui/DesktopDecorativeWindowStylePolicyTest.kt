package com.bilipai.desktop.ui

import kotlin.test.*

/** Pure native-style selection only. No HWND or input/rendering success claim. */
class DesktopDecorativeWindowStylePolicyTest {
    @Test fun missingLayeredPeerIsRejectedRatherThanClaimingTransparentOutput() {
        assertNull(DesktopDecorativeWindowStylePolicy.requested(0))
        assertNull(DesktopDecorativeWindowStylePolicy.requested(DesktopDecorativeWindowStylePolicy.TRANSPARENT))
    }
    @Test fun proposedFlagsPreserveEveryExistingUnrelatedBit() {
        val old = DesktopDecorativeWindowStylePolicy.LAYERED or 0x00000008 or 0x00000100
        val selected = assertNotNull(DesktopDecorativeWindowStylePolicy.requested(old))
        assertEquals(old, selected and old)
        assertTrue(DesktopDecorativeWindowStylePolicy.acknowledged(selected))
    }
    @Test fun readbackMissingAnyRequiredFlagCannotOpenTheAvailabilityGate() {
        val flags = DesktopDecorativeWindowStylePolicy.LAYERED or DesktopDecorativeWindowStylePolicy.TRANSPARENT or DesktopDecorativeWindowStylePolicy.NO_ACTIVATE
        for (removed in listOf(DesktopDecorativeWindowStylePolicy.LAYERED, DesktopDecorativeWindowStylePolicy.TRANSPARENT, DesktopDecorativeWindowStylePolicy.NO_ACTIVATE))
            assertFalse(DesktopDecorativeWindowStylePolicy.acknowledged(flags and removed.inv()))
    }
    @Test fun acknowledgedSelectionIsIdempotent() {
        val flags = DesktopDecorativeWindowStylePolicy.LAYERED or DesktopDecorativeWindowStylePolicy.TRANSPARENT or DesktopDecorativeWindowStylePolicy.NO_ACTIVATE
        assertEquals(flags, DesktopDecorativeWindowStylePolicy.requested(flags))
    }
}
