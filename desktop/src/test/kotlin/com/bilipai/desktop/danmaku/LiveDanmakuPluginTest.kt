package com.bilipai.desktop.danmaku

import com.android.purebilibili.core.plugin.DanmakuStyle
import com.android.purebilibili.feature.live.LiveDanmakuItem
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveDanmakuPluginTest {
    @Test fun `live plugin blocking applies to ordinary and paid messages and removing processor restores them`() = onSwing {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val renderer = LiveDanmakuRenderer(scope)
            renderer.setProcessor { null }
            renderer.add(LiveDanmakuItem("ordinary", uid = 42))
            renderer.add(LiveDanmakuItem("paid", isSuperChat = true, superChatId = 12, superChatDuration = 60))
            assertEquals(0, renderer.size)
            renderer.setProcessor(null)
            assertEquals(2, renderer.size)
            renderer.removeSuperChats(listOf(12))
            assertEquals(1, renderer.size)
        } finally { scope.cancel() }
    }

    @Test fun `live styles draw text border and background pixels through real AWT renderer`() = onSwing {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val renderer = LiveDanmakuRenderer(scope)
            renderer.setProcessor { item -> item.copy(content = "PLUGIN", type = 5) to
                DanmakuStyle(textColor = Color.Red, borderColor = Color.Blue, backgroundColor = Color.Green, bold = true, scale = 1.5f) }
            renderer.add(LiveDanmakuItem("raw", mode = 1))
            val image = BufferedImage(640, 360, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try { renderer.paint(graphics, 640, 360, 1f, DanmakuSettings(opacity = 1f, fontWeight = 1)) }
            finally { graphics.dispose() }
            val pixels = image.getRGB(0, 0, 640, 360, null, 0, 640).map { it and 0xffffff }.toSet()
            assertTrue(0xff0000 in pixels, "plugin text color must be painted")
            assertTrue(0x0000ff in pixels, "plugin border color must be painted")
            assertTrue(0x00ff00 in pixels, "plugin background color must be painted")
        } finally { scope.cancel() }
    }

    private fun onSwing(action: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { try { action() } catch (caught: Throwable) { failure = caught } }
        failure?.let { throw it }
    }
}
