package com.bilipai.desktop.danmaku

import com.android.purebilibili.danmaku.parser.bas.BasDanmaku
import com.android.purebilibili.danmaku.parser.bas.BasScriptParser
import com.android.purebilibili.danmaku.parser.bas.BasTarget
import java.awt.Canvas
import java.awt.image.BufferedImage
import kotlin.test.*

class DesktopBasOverlayInputTest {
    private class Fixture : AutoCloseable {
        val renderer = DesktopBasRenderer(4f)
        var owned = true
        var owner: Any = Any()
        var installation: Any = Any()
        var geometry = DesktopBasInputGeometry(-200, 100, 180, 100, 1.25, 1.25)
        val activated = mutableListOf<BasTarget>()
        var published: DesktopBasInputFrame? = null
        val input = DesktopBasOverlayInput(renderer) { published }
        init {
            val source = """def button b {text="GO" x=10 y=40 target=seek {time=2s}}"""
            renderer.configure(listOf(BasDanmaku(1, 0, source, BasScriptParser.parse(source))), 225, 125)
            paint(0)
        }
        fun paint(time: Long) {
            renderer.frame(time)
            val image = BufferedImage(225, 125, BufferedImage.TYPE_INT_ARGB_PRE)
            val graphics = image.createGraphics()
            try { assertTrue(renderer.paint(graphics)) } finally { graphics.dispose(); image.flush() }
            val capturedOwner = owner
            val capturedInstallation = installation
            val capturedGeometry = geometry
            published = DesktopBasInputFrame(owner, installation, geometry,
                isCurrent = { owned && owner === capturedOwner && installation === capturedInstallation && geometry == capturedGeometry },
                activate = { activated += it; true })
        }
        fun press() = input.pressAt(-188, 136) // one actual system-DPI conversion: (15px,45px).
        fun release() = input.releaseAt(-188, 136)
        override fun close() { input.close(); renderer.close() }
    }

    @Test fun playingTimelineAdvancesWithoutInvalidatingSameButton() {
        Fixture().use { f ->
            assertTrue(f.press()); f.paint(1_000); assertTrue(f.release())
            assertEquals(listOf<BasTarget>(BasTarget.Seek(2_000)), f.activated)
        }
    }

    @Test fun retiredSourceSettingsDocumentOrGeometryCannotUseOldDown() {
        listOf<(Fixture) -> Unit>({ it.owned = false }, { it.owner = Any() }, { it.installation = Any() },
            { it.geometry = it.geometry.copy(screenX = -190) }, { it.geometry = it.geometry.copy(scaleX = 1.5) })
            .forEach { change -> Fixture().use { f ->
                assertTrue(f.press()); change(f); f.paint(1_000)
                assertFalse(f.release()); assertTrue(f.activated.isEmpty())
            } }
    }

    @Test fun dragAwayAndBackStillCancelsOriginalPress() {
        Fixture().use { f ->
            assertTrue(f.press()); f.input.moveAt(-170, 136); f.input.moveAt(-188, 136)
            assertFalse(f.release()); assertTrue(f.activated.isEmpty())
        }
    }

    @Test fun expiredOrUnpaintedSceneCannotActivateRetainedButton() {
        Fixture().use { f ->
            assertTrue(f.press()); f.paint(5_000); assertFalse(f.release())
            f.paint(1_000); assertTrue(f.press()); f.renderer.frame(1_100)
            assertFalse(f.release()); assertTrue(f.activated.isEmpty())
        }
    }

    @Test fun actualCanvasListenerMovesOnceAndRetirementRemovesIt() {
        Fixture().use { f ->
            val first = Canvas(); val second = Canvas()
            f.input.attach(first); f.input.attach(first)
            assertEquals(1, first.mouseListeners.size); assertEquals(1, first.mouseMotionListeners.size)
            assertTrue(f.press()); f.input.attach(second)
            assertEquals(0, first.mouseListeners.size); assertEquals(0, first.mouseMotionListeners.size)
            assertEquals(1, second.mouseListeners.size); assertFalse(f.release())
            f.input.close()
            assertEquals(0, second.mouseListeners.size); assertEquals(0, second.mouseMotionListeners.size)
        }
    }

    @Test fun viewportConversionRejectsOutsideAndInvalidDensity() {
        val geometry = DesktopBasInputGeometry(-200, 100, 180, 100, 1.25, 1.5)
        assertEquals(15f to 54f, geometry.physical(-188, 136))
        assertNull(geometry.physical(-201, 136)); assertNull(geometry.physical(-20, 136))
        assertNull(geometry.copy(scaleX = Double.NaN).physical(-188, 136))
        assertNull(geometry.physical(Int.MAX_VALUE, Int.MIN_VALUE))
    }
}
