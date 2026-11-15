package com.bilipai.desktop.settings

import android.graphics.Color
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DesktopSubtitleColorTest {
    @Test fun subtitleDefaultsUseOpaqueArgbWhiteAndBlack() {
        assertEquals(0xFFFFFFFF.toInt(), Color.WHITE)
        assertEquals(0xFF000000.toInt(), Color.BLACK)
        assertEquals(255, Color.alpha(Color.WHITE))
        assertEquals(255, Color.alpha(Color.BLACK))
    }
}
