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
    @Test fun hiddenPreparationPreservesUnrelatedBitsWithoutInventingLayeredOrReadiness() {
        val before = 0x00000008 or 0x00000100
        val prepared = assertNotNull(DesktopDecorativeWindowStylePolicy.requestedBeforeShow(before, 0, false))
        assertEquals(before, prepared and before)
        assertEquals(0, prepared and DesktopDecorativeWindowStylePolicy.LAYERED)
        assertTrue(DesktopDecorativeWindowStylePolicy.inputPolicyAcknowledged(prepared))
        assertFalse(DesktopDecorativeWindowStylePolicy.acknowledged(prepared))
        assertNull(DesktopDecorativeWindowStylePolicy.requested(prepared))
    }
    @Test fun awtShowHideAndRestoreMustRegainActualLayeredBeforeBecomingAvailable() {
        val prepared = assertNotNull(DesktopDecorativeWindowStylePolicy.requestedBeforeShow(0, 0, false))
        val shown = prepared or DesktopDecorativeWindowStylePolicy.LAYERED
        assertTrue(DesktopDecorativeWindowStylePolicy.acknowledged(shown))
        val hidden = shown and DesktopDecorativeWindowStylePolicy.LAYERED.inv()
        assertTrue(DesktopDecorativeWindowStylePolicy.inputPolicyAcknowledged(hidden))
        assertFalse(DesktopDecorativeWindowStylePolicy.acknowledged(hidden))
        assertEquals(prepared, DesktopDecorativeWindowStylePolicy.requestedBeforeShow(hidden, 0, false))
        assertTrue(DesktopDecorativeWindowStylePolicy.acknowledged(hidden or DesktopDecorativeWindowStylePolicy.LAYERED))
    }
    @Test fun opaqueOrUnknownTransparencyCannotPrepareEvenWithExistingNativeFlags() {
        val existing = DesktopDecorativeWindowStylePolicy.LAYERED or DesktopDecorativeWindowStylePolicy.TRANSPARENT or DesktopDecorativeWindowStylePolicy.NO_ACTIVATE
        for (alpha in listOf(null, -1, 255, 256))
            assertNull(DesktopDecorativeWindowStylePolicy.requestedBeforeShow(existing, alpha, false))
        assertNull(DesktopDecorativeWindowStylePolicy.requestedBeforeShow(existing, 0, true))
        for (removed in listOf(DesktopDecorativeWindowStylePolicy.TRANSPARENT, DesktopDecorativeWindowStylePolicy.NO_ACTIVATE))
            assertFalse(DesktopDecorativeWindowStylePolicy.inputPolicyAcknowledged(existing and removed.inv()))
    }
}
