package com.bilipai.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import com.bilipai.desktop.player.DesktopNativeVideoCanvasClip
import com.bilipai.desktop.player.DesktopNativeVideoCanvasMetrics
import com.bilipai.desktop.player.DesktopNativeVideoCanvasPixels
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class DesktopWindowsNativeVideoGeometryTest {
    @Test fun `a partly offscreen render keeps full measurement and negative placement`() {
        assertEquals(DesktopWindowsNativeVideoPlacement(-300, -40, 1920, 1080),
            resolveDesktopWindowsNativeVideoPlacement(Rect(-300f, -40f, 1620f, 1040f), Offset.Zero))
    }

    @Test fun `root origin changes placement exactly once without another density conversion`() {
        assertEquals(DesktopWindowsNativeVideoPlacement(100, 50, 1920, 1080),
            resolveDesktopWindowsNativeVideoPlacement(Rect(130f, 110f, 2050f, 1190f), Offset(30f, 60f)))
    }

    @Test fun `host edge clips a fourth of the video instead of shrinking its render width`() {
        val render = Rect(-100f, 0f, 300f, 200f)
        assertEquals(400, resolveDesktopWindowsNativeVideoPlacement(render, Offset.Zero)?.width)
        val clip = resolveDesktopWindowsNativeVideoClip(render, Rect(0f, 0f, 600f, 400f), IntSize(600, 400))
        assertEquals(DesktopNativeVideoCanvasPixels(100, 0, 400, 200), clip?.inWindow(
            DesktopNativeVideoCanvasMetrics(400, 200, 0, 0, 400, 200,
                clientScreenLeft = -100, hostClientWidth = 600, hostClientHeight = 400)))
    }

    @Test fun `independent media slot clips the full retained playback surface`() {
        val render = Rect(0f, 0f, 400f, 200f)
        assertEquals(DesktopNativeVideoCanvasClip(.25, .25, .75, .75),
            resolveDesktopWindowsNativeVideoClip(Rect(100f, 50f, 300f, 150f), render, IntSize(400, 200)))
    }

    @Test fun `completely outside and zero size intersections are empty rather than unrestricted`() {
        val render = Rect(0f, 0f, 400f, 200f)
        assertEquals(DesktopNativeVideoCanvasClip.Empty,
            resolveDesktopWindowsNativeVideoClip(render, Rect(500f, 0f, 600f, 200f), IntSize(600, 200)))
        assertEquals(DesktopNativeVideoCanvasClip.Empty,
            resolveDesktopWindowsNativeVideoClip(Rect(200f, 0f, 200f, 200f), render, IntSize(400, 200)))
    }

    @Test fun `ordinary full viewport restores a previously restricted native window`() {
        val render = Rect(50f, 100f, 1970f, 1180f)
        val clip = resolveDesktopWindowsNativeVideoClip(render, Rect(0f, 0f, 2560f, 1440f), IntSize(2560, 1440))
        assertNull(assertNotNull(clip).inWindow(DesktopNativeVideoCanvasMetrics(1920, 1080, 0, 0, 1920, 1080,
            clientScreenLeft = 50, clientScreenTop = 100, hostClientWidth = 2560, hostClientHeight = 1440)))
    }

    @Test fun `clip larger than the render cannot grow a native window region`() {
        val render = Rect(0f, 0f, 400f, 200f)
        assertEquals(DesktopNativeVideoCanvasClip.Full,
            resolveDesktopWindowsNativeVideoClip(Rect(-100f, -100f, 1000f, 1000f), render, IntSize(400, 200)))
    }

    @Test fun `application geometry maps through actual native client pixels at another display scale`() {
        val render = Rect(0f, 0f, 400f, 200f)
        val clip = resolveDesktopWindowsNativeVideoClip(Rect(100f, 50f, 300f, 150f), render, IntSize(400, 200))
        assertEquals(DesktopNativeVideoCanvasPixels(150, 75, 450, 225),
            clip?.inWindow(DesktopNativeVideoCanvasMetrics(600, 300, 0, 0, 600, 300)))
    }

    @Test fun `invalid metadata cannot enter retained native measurement or clip mapping`() {
        assertNull(resolveDesktopWindowsNativeVideoPlacement(null, Offset.Zero))
        assertNull(resolveDesktopWindowsNativeVideoPlacement(Rect(0f, 0f, 0f, 20f), Offset.Zero))
        assertNull(resolveDesktopWindowsNativeVideoPlacement(Rect(Float.NaN, 0f, 40f, 20f), Offset.Zero))
        assertNull(resolveDesktopWindowsNativeVideoPlacement(Rect(0f, 0f, 40f, 20f), Offset.Unspecified))
        assertNull(resolveDesktopWindowsNativeVideoClip(Rect(0f, 0f, Float.POSITIVE_INFINITY, 20f),
            Rect(0f, 0f, 40f, 20f), IntSize(40, 20)))
    }

    @Test fun `unrepresentable transition constraints fail closed before the Layout measure pass`() {
        assertNull(resolveDesktopWindowsNativeVideoPlacement(Rect(0f, 0f, Float.MAX_VALUE, Float.MAX_VALUE), Offset.Zero))
    }
}
