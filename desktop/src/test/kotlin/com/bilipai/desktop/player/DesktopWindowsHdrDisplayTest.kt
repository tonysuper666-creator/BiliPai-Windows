package com.bilipai.desktop.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopWindowsHdrDisplayTest {
    @Test fun hdrRequiresSupportUserOptInAndActualHdrMode() {
        assertTrue(decodeWindowsHdrDisplay(0x33, 2, 10).hdrEnabled)
        assertFalse(decodeWindowsHdrDisplay(0x23, 2, 10).hdrEnabled)
        assertFalse(decodeWindowsHdrDisplay(0x13, 2, 10).hdrEnabled)
        assertFalse(decodeWindowsHdrDisplay(0x31, 2, 10).hdrEnabled)
        assertFalse(decodeWindowsHdrDisplay(0x33, 0, 10).hdrEnabled)
    }

    @Test fun wideColorAloneDoesNotPermitAnSdrToHdrConversion() {
        val wideColor = decodeWindowsHdrDisplay(0x33, 1, 10)
        assertTrue(wideColor.hdrSupported)
        assertTrue(wideColor.hdrUserEnabled)
        assertFalse(wideColor.hdrActive)
        assertFalse(wideColor.hdrEnabled)
    }

    @Test fun missingVideoWindowDoesNotProbeSomeOtherMonitor() {
        val unknown = DesktopWindowsHdrDisplay.query(0)
        assertFalse(unknown.known)
        assertFalse(unknown.hdrEnabled)
    }
}
