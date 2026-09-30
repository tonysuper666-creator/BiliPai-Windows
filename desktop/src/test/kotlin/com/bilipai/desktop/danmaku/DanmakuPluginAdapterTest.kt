package com.bilipai.desktop.danmaku

import com.android.purebilibili.core.plugin.DanmakuStyle
import androidx.compose.ui.graphics.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DanmakuPluginAdapterTest {
    @Test fun `original item fields are supplied and transformed content timing type color and style reach native comment`() {
        val original = DanmakuComment(5, 2.0, 1, 25, 0xffffff, "original", serverId = 77, userHash = "hash")
        val changed = assertNotNull(applyDesktopDanmakuPlugin(original) { item ->
            assertEquals(77L, item.id); assertEquals("hash", item.userId); assertEquals(2_000L, item.timeMs)
            item.copy(content = "translated", timeMs = 3_250, type = 5, color = 0x336699) to
                DanmakuStyle(textColor = Color(0xffffaa00), bold = true, scale = 1.5f)
        })
        assertEquals("translated", changed.comment.text)
        assertEquals(3.25, changed.comment.timeSeconds)
        assertEquals(5, changed.comment.mode)
        assertEquals(0xffaa00, changed.comment.color)
        val style = assertNotNull(changed.style)
        assertTrue(style.bold)
        assertEquals(1.5f, style.scale)
        assertEquals(original.serverId, changed.comment.serverId)
    }

    @Test fun `null blocks actual display while plugin failure falls back to the original comment`() {
        val comment = DanmakuComment(1, 1.0, 1, 25, 0xffffff, "visible")
        assertNull(applyDesktopDanmakuPlugin(comment) { null })
        assertEquals(comment, applyDesktopDanmakuPlugin(comment) { error("plugin error") }?.comment)
    }

    @Test fun `eye protection paints actual dim and warm pixels and preserves the base opacity`() {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            graphics.color = java.awt.Color.WHITE; graphics.fillRect(0, 0, 8, 8)
            val tint = DesktopEyeTint(0.25f, 0.25f)
            assertTrue(tint.visible)
            tint.paint(graphics, 8, 8)
            val result = java.awt.Color(image.getRGB(4, 4), true)
            assertEquals(255, result.alpha)
            assertTrue(result.red < 255 && result.red > result.green && result.green > result.blue)
            assertFalse(DesktopEyeTint().visible)
        } finally { graphics.dispose() }
    }
}
