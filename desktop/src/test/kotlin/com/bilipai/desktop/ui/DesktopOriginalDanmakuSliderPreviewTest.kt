package com.bilipai.desktop.ui

import com.android.purebilibili.feature.video.ui.components.DanmakuSliderParameter
import com.android.purebilibili.feature.video.ui.components.applyDanmakuSliderPreview
import com.bilipai.desktop.danmaku.DanmakuSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopOriginalDanmakuSliderPreviewTest {
    @Test fun originalNineParameterDispatchChangesExistingRendererWithoutReplacingOtherSettings() {
        var renderer = DanmakuSettings(enabled = false)
        var writes = 0
        val target = DesktopOriginalDanmakuSettingsPreviewTarget({ renderer }, {
            renderer = it
            writes++
        }, { action -> action(); true })
        val previews = listOf(
            DanmakuSliderParameter.OPACITY to 0.65f,
            DanmakuSliderParameter.FONT_SCALE to 1.4f,
            DanmakuSliderParameter.FONT_WEIGHT to 5.6f,
            DanmakuSliderParameter.SPEED to 0.9f,
            DanmakuSliderParameter.DISPLAY_AREA to 0.125f,
            DanmakuSliderParameter.STROKE_WIDTH to 0f,
            DanmakuSliderParameter.LINE_HEIGHT to 1.3f,
            DanmakuSliderParameter.SCROLL_DURATION to 7f,
            DanmakuSliderParameter.STATIC_DURATION to 4f,
        )
        previews.forEach { (parameter, value) -> applyDanmakuSliderPreview(target, parameter, value) }
        assertEquals(9, writes)
        assertEquals(0.65f, renderer.opacity)
        assertEquals(1.4f, renderer.fontScale)
        assertEquals(6, renderer.fontWeight)
        assertEquals(0.9f, renderer.speedFactor)
        assertEquals(0.125f, renderer.displayAreaRatio)
        assertEquals(0f, renderer.strokeWidth)
        assertFalse(renderer.strokeEnabled)
        assertEquals(1.3f, renderer.lineHeight)
        assertEquals(7f, renderer.scrollDurationSeconds)
        assertEquals(4f, renderer.staticDurationSeconds)
        assertFalse(renderer.enabled)
    }

    @Test fun retiredPresentationRejectsEveryOriginalSliderBeforeRendererReadOrWrite() {
        var reads = 0
        var writes = 0
        val target = DesktopOriginalDanmakuSettingsPreviewTarget({ reads++; DanmakuSettings() },
            { writes++ }, { false })
        DanmakuSliderParameter.entries.forEach { applyDanmakuSliderPreview(target, it, 1f) }
        assertEquals(0, reads)
        assertEquals(0, writes)
    }

    @Test fun previewReadsLatestRendererInsideAdmissionAndPreservesConcurrentChanges() {
        var renderer = DanmakuSettings()
        val target = DesktopOriginalDanmakuSettingsPreviewTarget({ renderer }, { renderer = it }, { action ->
            renderer = renderer.copy(enabled = false, opacity = 0.7f)
            action()
            true
        })
        applyDanmakuSliderPreview(target, DanmakuSliderParameter.FONT_SCALE, 1.5f)
        assertFalse(renderer.enabled)
        assertEquals(0.7f, renderer.opacity)
        assertEquals(1.5f, renderer.fontScale)
        applyDanmakuSliderPreview(target, DanmakuSliderParameter.STROKE_WIDTH, 1.5f)
        assertTrue(renderer.strokeEnabled)
        assertEquals(1.5f, renderer.strokeWidth)
        assertEquals(1.5f, renderer.fontScale)
    }
}
