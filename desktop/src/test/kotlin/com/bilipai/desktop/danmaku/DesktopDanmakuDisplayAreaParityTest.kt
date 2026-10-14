package com.bilipai.desktop.danmaku

import kotlin.test.Test
import kotlin.test.assertEquals
import com.android.purebilibili.core.store.normalizeDanmakuDisplayArea

class DesktopDanmakuDisplayAreaParityTest {
    @Test
    fun everyOriginalPanelChoiceSurvivesPreferenceAndRendererNormalization() {
        for (choice in listOf(0.125f, 0.25f, 0.375f, 0.5f, 0.625f, 0.75f, 0.875f, 1f)) {
            assertEquals(choice, normalizeDanmakuDisplayArea(choice))
            assertEquals(choice, DanmakuSettings(displayAreaRatio = choice).normalized().displayAreaRatio)
        }
        assertEquals(0.5f, DanmakuSettings(displayAreaRatio = Float.NaN).normalized().displayAreaRatio)
    }
}
