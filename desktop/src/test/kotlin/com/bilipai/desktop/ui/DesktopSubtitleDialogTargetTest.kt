package com.bilipai.desktop.ui

import kotlin.test.*

class DesktopSubtitleDialogTargetTest {
    @Test fun `a dialog accepts only its captured BV CID native source and account owner`() {
        val target = DesktopSubtitleDialogTarget("BV-one", 17, 41, 7)
        assertTrue(target.matches("BV-one", 17, 41, 7, true))
        assertFalse(target.matches("BV-two", 17, 41, 7, true))
        assertFalse(target.matches("BV-one", 19, 41, 7, true))
        assertFalse(target.matches("BV-one", 17, 43, 7, true))
        assertFalse(target.matches("BV-one", 17, 41, 8, true))
        assertFalse(target.matches("BV-one", 17, 41, 7, false))
        assertFalse(target.matches(null, null, null, 7, true))
    }
}
