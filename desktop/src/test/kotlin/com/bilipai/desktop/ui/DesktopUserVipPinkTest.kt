package com.bilipai.desktop.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopUserVipPinkTest {
    @Test fun `VIP pink keeps the two upstream values for dark and light surfaces`() {
        assertEquals(Color(0xFFD44E7D), resolveDesktopUserVipPink(isDark = true))
        assertEquals(Color(0xFFFF6699), resolveDesktopUserVipPink(isDark = false))
    }
}
